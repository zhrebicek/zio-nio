package zio.nio.channels

import zio._
import zio.nio.{BaseSpec, Buffer, EffectOps, InetSocketAddress, SocketAddress}
import zio.test.Assertion._
import zio.test._

import java.io.{EOFException, FileNotFoundException, IOException}
import java.nio.channels
import java.{nio => jnio}

object ChannelSpec extends BaseSpec {

  override def spec: Spec[
    Annotations with Live with Sized with TestClock with TestConfig with TestConsole with TestRandom with TestSystem with Clock with zio.Console with zio.System with Random,
    TestFailure[Any],
    TestSuccess
  ] =
    suite("Channel")(
      test("localAddress") {
        SocketChannel.open.use { con =>
          for {
            _            <- con.bindAuto
            localAddress <- con.localAddress
          } yield assert(localAddress)(isSome(isSubtype[InetSocketAddress](anything)))
        }
      },
      suite("AsynchronousSocketChannel")(
        test("read/write") {
          def echoServer(started: Promise[Nothing, SocketAddress])(implicit trace: ZTraceElement): IO[Exception, Unit] =
            for {
              sink <- Buffer.byte(3)
              _ <- AsynchronousServerSocketChannel.open.use { server =>
                     for {
                       _    <- server.bindAuto()
                       addr <- server.localAddress.flatMap(opt => IO.attempt(opt.get).orDie)
                       _    <- started.succeed(addr)
                       _ <- server.accept.use { worker =>
                              worker.read(sink) *>
                                sink.flip *>
                                worker.write(sink)
                            }
                     } yield ()
                   }.fork
            } yield ()

          def echoClient(address: SocketAddress)(implicit trace: ZTraceElement): IO[Exception, Boolean] =
            for {
              src <- Buffer.byte(3)
              result <- AsynchronousSocketChannel.open.use { client =>
                          for {
                            _        <- client.connect(address)
                            sent     <- src.array
                            _         = sent.update(0, 1)
                            _        <- client.write(src)
                            _        <- src.flip
                            _        <- client.read(src)
                            received <- src.array
                          } yield sent.sameElements(received)
                        }
            } yield result

          for {
            serverStarted <- Promise.make[Nothing, SocketAddress]
            _             <- echoServer(serverStarted)
            address       <- serverStarted.await
            same          <- echoClient(address)
          } yield assert(same)(isTrue)
        },
        test("read should fail when connection close") {
          def server(started: Promise[Nothing, SocketAddress])(implicit
            trace: ZTraceElement
          ): IO[Exception, Fiber[Exception, Boolean]] =
            for {
              result <- AsynchronousServerSocketChannel.open.use { server =>
                          for {
                            _    <- server.bindAuto()
                            addr <- server.localAddress.flatMap(opt => IO.attempt(opt.get).orDie)
                            _    <- started.succeed(addr)
                            result <- server.accept
                                        .use(worker => worker.readChunk(3) *> worker.readChunk(3) *> ZIO.succeed(false))
                                        .catchSome { case _: java.io.EOFException =>
                                          ZIO.succeed(true)
                                        }
                          } yield result
                        }.fork
            } yield result

          def client(address: SocketAddress)(implicit trace: ZTraceElement): IO[Exception, Unit] =
            for {
              _ <- AsynchronousSocketChannel.open.use { client =>
                     for {
                       _ <- client.connect(address)
                       _  = client.writeChunk(Chunk.fromArray(Array[Byte](1, 1, 1)))
                     } yield ()
                   }
            } yield ()

          for {
            serverStarted <- Promise.make[Nothing, SocketAddress]
            serverFiber   <- server(serverStarted)
            addr          <- serverStarted.await
            _             <- client(addr)
            same          <- serverFiber.join
          } yield assert(same)(isTrue)
        },
        test("close channel unbind port") {
          def client(address: SocketAddress)(implicit trace: ZTraceElement): IO[Exception, Unit] =
            AsynchronousSocketChannel.open.use {
              _.connect(address)
            }

          def server(
            started: Promise[Nothing, SocketAddress]
          )(implicit trace: ZTraceElement): Managed[IOException, Fiber[Exception, Unit]] =
            for {
              server <- AsynchronousServerSocketChannel.open
              _      <- server.bindAuto().toManaged
              addr   <- server.localAddress.someOrElseZIO(IO.die(new NoSuchElementException)).toManaged
              _      <- started.succeed(addr).toManaged
              worker <- server.accept.unit.fork
            } yield worker

          for {
            serverStarted1 <- Promise.make[Nothing, SocketAddress]
            _ <- server(serverStarted1).use { s1 =>
                   serverStarted1.await.flatMap(client).zipRight(s1.join)
                 }
            serverStarted2 <- Promise.make[Nothing, SocketAddress]
            _ <- server(serverStarted2).use { s2 =>
                   serverStarted2.await.flatMap(client).zipRight(s2.join)
                 }
          } yield assertCompletes
        },
        test("read can be interrupted") {
          live {
            AsynchronousServerSocketChannel.open
              .tapZIO(_.bindAuto())
              .use { serverChannel =>
                for {
                  serverAddress <-
                    serverChannel.localAddress.someOrElseZIO(ZIO.dieMessage("Local address must be bound"))
                  promise <- Promise.make[Nothing, Unit]
                  fiber <- AsynchronousSocketChannel.open
                             .tapZIO(_.connect(serverAddress))
                             .use(channel => promise.succeed(()) *> channel.readChunk(1))
                             .fork
                  _    <- promise.await
                  _    <- ZIO.sleep(500.milliseconds)
                  exit <- fiber.interrupt
                } yield assert(exit)(isInterrupted)

              }
          }
        },
        test("accept can be interrupted") {
          live {
            AsynchronousServerSocketChannel.open.tapZIO(_.bindAuto()).use { serverChannel =>
              for {
                fiber <- serverChannel.accept.useNow.fork
                _     <- ZIO.sleep(500.milliseconds)
                exit  <- fiber.interrupt
              } yield assert(exit)(isInterrupted)
            }
          }
        }
      ),
      suite("explicit end-of-stream")(
        test("converts EOFException to None") {
          assertM(IO.fail(new EOFException).eofCheck.exit)(fails(isNone))
        },
        test("converts non EOFException to Some") {
          val e: IOException = new FileNotFoundException()
          assertM(IO.fail(e).eofCheck.exit)(fails(isSome(equalTo(e))))
        },
        test("passes through success") {
          assertM(IO.succeed(42).eofCheck.exit)(succeeds(equalTo(42)))
        }
      ),
      suite("blocking operations")(
        test("read can be interrupted") {
          live {
            for {
              promise <- Promise.make[Nothing, Unit]
              fiber <- Pipe.open.toManaged
                         .flatMap(_.source)
                         .useNioBlockingOps(ops => promise.succeed(()) *> ops.readChunk(1))
                         .fork
              _    <- promise.await
              _    <- ZIO.sleep(500.milliseconds)
              exit <- fiber.interrupt
            } yield assert(exit)(isInterrupted)
          }
        },
        test("write can be interrupted") {
          val hangingOps: GatheringByteOps = new GatheringByteOps {
            override protected[channels] val channel = new jnio.channels.GatheringByteChannel {

              @volatile private var _closed = false

              private def hang(): Nothing = {
                while (!_closed)
                  Thread.sleep(10L)
                throw new jnio.channels.AsynchronousCloseException()
              }

              override def write(srcs: Array[jnio.ByteBuffer], offset: Int, length: Int): Long = hang()

              override def write(srcs: Array[jnio.ByteBuffer]): Long = hang()

              override def write(src: jnio.ByteBuffer): Int = hang()

              override def isOpen: Boolean = !_closed

              override def close(): Unit = _closed = true
            }
          }
          val hangingChannel = new BlockingChannel {
            override type BlockingOps = GatheringByteOps

            override def useBlocking[R, E >: IOException, A](
              f: GatheringByteOps => ZIO[R, E, A]
            )(implicit trace: ZTraceElement): ZIO[R, E, A] = nioBlocking(f(hangingOps))

            override protected val channel: channels.Channel = hangingOps.channel
          }

          live {
            for {
              promise <- Promise.make[Nothing, Unit]
              fiber <-
                hangingChannel.useBlocking(ops => promise.succeed(()) *> ops.writeChunk(Chunk.single(42.toByte))).fork
              _    <- promise.await
              _    <- ZIO.sleep(500.milliseconds)
              exit <- fiber.interrupt
            } yield assert(exit)(isInterrupted)
          }
        },
        test("accept can be interrupted") {
          live {
            ServerSocketChannel.open.tapZIO(_.bindAuto()).use { serverChannel =>
              for {
                promise <- Promise.make[Nothing, Unit]
                fiber   <- serverChannel.useBlocking(ops => promise.succeed(()) *> ops.accept.useNow).fork
                _       <- promise.await
                _       <- ZIO.sleep(500.milliseconds)
                exit    <- fiber.interrupt
              } yield assert(exit)(isInterrupted)
            }
          }
        }
      )
    )
}
