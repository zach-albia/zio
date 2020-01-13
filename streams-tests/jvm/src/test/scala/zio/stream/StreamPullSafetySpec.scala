package zio.stream

import StreamUtils.nPulls
import ZStream.Pull

import zio._
import zio.test.Assertion.{ equalTo, isFalse, isTrue }
import zio.test._

object StreamPullSafetySpec extends ZIOBaseSpec {

  def spec = suite("StreamPullSafetySpec")(combinators, constructors)

  def combinators = suite("Combinators")(
    suite("Stream.aggregate")(
      testM("is safe to pull again after success") {
        Stream(1, 2, 3, 4, 5, 6)
          .aggregate(ZSink.collectAllN[Int](2).map(_.sum))
          .process
          .use(nPulls(_, 5))
          .map(assert(_)(equalTo(List(Right(3), Right(7), Right(11), Left(None), Left(None)))))
      },
      testM("is safe to pull again after upstream failure") {
        (Stream(1, 2) ++ Stream.fail("Ouch") ++ Stream(3, 4))
          .aggregate(ZSink.collectAllN[Int](2).map(_.sum))
          .process
          .use(nPulls(_, 5))
          .map(assert(_)(equalTo(List(Right(3), Left(Some("Ouch")), Right(7), Left(None), Left(None)))))
      },
      testM("is safe to pull again after initial failure") {
        def initFailureSink(ref: Ref[(Int, Boolean)]): Sink[String, Nothing, Int, String] =
          new Sink[String, Nothing, Int, String] {
            type State = Option[Int]

            def initial: IO[String, State] =
              ref.modify {
                case (n, failed) =>
                  if (failed) (UIO.succeed(None), (n + 1, false)) else (IO.fail("Ouch"), (n, true))
              }.flatten

            def step(s: State, a: Int): UIO[State] = UIO.succeed(Some(a))

            def extract(s: State): IO[String, (String, Chunk[Nothing])] =
              IO.fromOption(s).map(n => (n.toString, Chunk.empty)).asError("Empty")

            def cont(s: State): Boolean = s.isEmpty
          }

        for {
          ref <- Ref.make((1, false))
          pulls <- Stream(1, 2, 3)
                    .aggregate(initFailureSink(ref))
                    .process
                    .use(nPulls(_, 9))
        } yield assert(pulls)(
          equalTo(
            List(
              Left(Some("Ouch")),
              Right("1"),
              Left(Some("Ouch")),
              Right("2"),
              Left(Some("Ouch")),
              Right("3"),
              Left(Some("Ouch")),
              Left(None),
              Left(None)
            )
          )
        )
      },
      testM("is safe to pull again after sink step failure") {
        Stream(1, 2, 3, 4)
          .aggregate(ZSink.identity[Int].contramapM { (n: Int) =>
            if (n % 2 == 0) IO.fail("Ouch") else UIO.succeed(n)
          })
          .process
          .use(nPulls(_, 6))
          .map(
            assert(_)(equalTo(List(Right(1), Left(Some("Ouch")), Right(3), Left(Some("Ouch")), Left(None), Left(None))))
          )
      },
      testM("is safe to pull again after sink extraction failure") {
        assertM(
          Stream(1, 2, 3, 4)
            .aggregate(ZSink.fromFunctionM { (n: Int) =>
              if (n % 2 == 0) IO.fail("Ouch") else UIO.succeed(n)
            })
            .process
            .use(nPulls(_, 6))
        )(
          equalTo(
            List(Right(1), Left(Some(Some("Ouch"))), Right(3), Left(Some(Some("Ouch"))), Left(None), Left(None))
          )
        )
      }
    ),
    suite("Stream.aggregateManaged")(
      testM("is safe to pull again after success") {
        Stream(1, 2, 3, 4, 5, 6)
          .aggregateManaged(Managed.succeed(ZSink.collectAllN[Int](2).map(_.sum)))
          .process
          .use(nPulls(_, 5))
          .map(assert(_)(equalTo(List(Right(3), Right(7), Right(11), Left(None), Left(None)))))
      },
      testM("is safe to pull again from a failed Managed") {
        Stream(1, 2, 3, 4, 5, 6)
          .aggregateManaged(Managed.fail("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    ),
    testM("Stream.drop is safe to pull again") {
      assertM(
        Stream(1, 2, 3, 4, 5, 6, 7)
          .mapM(n => if (n % 2 == 0) IO.fail(s"Ouch $n") else UIO.succeed(n))
          .drop(3)
          .process
          .use(nPulls(_, 6))
      )(
        equalTo(
          List(
            Left(Some("Ouch 2")), // dropped 1 up to here
            Left(Some("Ouch 4")), // dropped 2 up to here
            Left(Some("Ouch 6")), // dropped 3 up to here
            Right(7),
            Left(None),
            Left(None)
          )
        )
      )
    },
    suite("Stream.dropWhile")(
      testM("ZStream#dropWhile is safe to pull again") {
        assertM(
          Stream(1, 2, 3, 4, 5)
            .mapM(n => if (n % 2 == 0) IO.fail(s"Ouch $n") else UIO.succeed(n))
            .dropWhile(_ < 3)
            .process
            .use(nPulls(_, 6))
        )(equalTo(List(Left(Some("Ouch 2")), Right(3), Left(Some("Ouch 4")), Right(5), Left(None), Left(None))))
      },
      testM("StreamEffect#dropWhile is safe to pull again") {
        val stream = StreamEffect[Any, String, Int] {
          Managed.effectTotal {
            var counter = 0

            () => {
              counter += 1
              if (counter >= 6) StreamEffect.end[Int]
              else if (counter % 2 == 0) StreamEffect.fail[String, Int](s"Ouch $counter")
              else counter
            }
          }
        }

        assertM(
          stream
            .dropWhile(_ < 3)
            .process
            .use(nPulls(_, 6))
        )(equalTo(List(Left(Some("Ouch 2")), Right(3), Left(Some("Ouch 4")), Right(5), Left(None), Left(None))))
      }
    ),
    testM("Stream.mapAccumM is safe to pull again") {
      assertM(
        Stream(1, 2, 3, 4, 5)
          .mapAccumM(0)((sum, n) => if (n % 2 == 0) IO.fail("Ouch") else UIO.succeed((sum + n, sum + n)))
          .process
          .use(nPulls(_, 8))
      )(
        equalTo(
          List(
            Right(1),
            Left(Some("Ouch")),
            Right(4),
            Left(Some("Ouch")),
            Right(9),
            Left(None),
            Left(None),
            Left(None)
          )
        )
      )
    },
    suite("Stream.take") {
      testM("ZStream#take is safe to pull again") {
        assertM(
          Stream(1, 2, 3, 4, 5)
            .mapM(n => if (n % 2 == 0) IO.fail(s"Ouch $n") else UIO.succeed(n))
            .take(3)
            .process
            .use(nPulls(_, 7))
        )(
          equalTo(
            List(
              Right(1), // took 1 up to here
              Left(Some("Ouch 2")),
              Right(3), // took 2 up to here
              Left(Some("Ouch 4")),
              Right(5), // took 3 up to here
              Left(None),
              Left(None)
            )
          )
        )
      }
    },
    testM("StreamEffect#take is safe to pull again") {
      val stream = StreamEffect[Any, String, Int] {
        Managed.effectTotal {
          var counter = 0

          () => {
            counter += 1
            if (counter >= 6) StreamEffect.end[Int]
            else if (counter % 2 == 0) StreamEffect.fail[String, Int](s"Ouch $counter")
            else counter
          }
        }
      }

      assertM(
        stream.take(3).process.use(nPulls(_, 7))
      )(
        equalTo(
          List(
            Right(1), // took 1 up to here
            Left(Some("Ouch 2")),
            Right(3), // took 2 up to here
            Left(Some("Ouch 4")),
            Right(5), // took 3 up to here
            Left(None),
            Left(None)
          )
        )
      )
    }
  )

  def constructors = suite("Constructors")(
    suite("Stream.bracket")(
      testM("is safe to pull again after success") {
        for {
          ref   <- Ref.make(false)
          pulls <- Stream.bracket(UIO.succeed(5))(_ => ref.set(true)).process.use(nPulls(_, 3))
          fin   <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(5), Left(None), Left(None))))
      },
      testM("is safe to pull again after failed acquisition") {
        for {
          ref   <- Ref.make(false)
          pulls <- Stream.bracket(IO.fail("Ouch"))(_ => ref.set(true)).process.use(nPulls(_, 3))
          fin   <- ref.get
        } yield assert(fin)(isFalse) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      },
      testM("is safe to pull again after inner failure") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .bracket(UIO.succeed(5))(_ => ref.set(true))
                    .flatMap(_ => Stream.fail("Ouch"))
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      }
    ),
    suite("Stream.bracketExit")(
      testM("is safe to pull again after success") {
        for {
          ref   <- Ref.make(false)
          pulls <- Stream.bracketExit(UIO.succeed(5))((_, _) => ref.set(true)).process.use(nPulls(_, 3))
          fin   <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(5), Left(None), Left(None))))
      },
      testM("is safe to pull again after failed acquisition") {
        for {
          ref   <- Ref.make(false)
          pulls <- Stream.bracketExit(IO.fail("Ouch"))((_, _) => ref.set(true)).process.use(nPulls(_, 3))
          fin   <- ref.get
        } yield assert(fin)(isFalse) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      },
      testM("is safe to pull again after inner failure") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .bracketExit(UIO.succeed(5))((_, _) => ref.set(true))
                    .flatMap(_ => Stream.fail("Ouch"))
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      }
    ),
    suite("Stream.flatten")(
      testM("is safe to pull again after success") {
        Stream
          .flatten(Stream(Stream.fromEffect(UIO.succeed(5))))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(5), Left(None), Left(None)))))
      },
      testM("is safe to pull again after inner failure") {
        Stream
          .flatten(Stream(Stream.fail("Ouch")))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      },
      testM("is safe to pull again after outer failure") {
        Stream
          .flatten(Stream.fail("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    ),
    testM("Stream.empty is safe to pull again") {
      Stream.empty.process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Left(None), Left(None), Left(None)))))
    },
    suite("Stream.effectAsync")(
      testM("is safe to pull again after error") {
        Stream
          .effectAsync[String, Int] { k =>
            List(1, 2, 3).foreach { n =>
              k(if (n % 2 == 0) Pull.fail("Ouch") else Pull.emit(n))
            }
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(1), Left(Some("Ouch")), Right(3)))))
      },
      testM("is safe to pull again after end") {
        Stream
          .effectAsync[String, Int] { k =>
            k(Pull.emit(1))
            k(Pull.end)
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(1), Left(None), Left(None)))))
      }
    ),
    suite("Stream.effectAsyncM")(
      testM("is safe to pull again after error") {
        Stream
          .effectAsyncM[String, Int] { k =>
            List(1, 2, 3).foreach { n =>
              k(if (n % 2 == 0) Pull.fail("Ouch") else Pull.emit(n))
            }
            UIO.unit
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(1), Left(Some("Ouch")), Right(3)))))
      },
      testM("is safe to pull again after end") {
        Stream
          .effectAsyncM[String, Int] { k =>
            k(Pull.emit(1))
            k(Pull.end)
            UIO.unit
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(1), Left(None), Left(None)))))
      }
    ),
    suite("Stream.effectAsyncMaybe")(
      testM("is safe to pull again after error async case") {
        Stream
          .effectAsyncMaybe[String, Int] { k =>
            List(1, 2, 3).foreach { n =>
              k(if (n % 2 == 0) Pull.fail("Ouch") else Pull.emit(n))
            }
            None
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(1), Left(Some("Ouch")), Right(3)))))
      },
      testM("is safe to pull again after error sync case") {
        Stream
          .effectAsyncMaybe[String, Int] { k =>
            k(Pull.fail("Ouch async"))
            Some(Stream.fail("Ouch sync"))
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch sync")), Left(None), Left(None)))))
      },
      testM("is safe to pull again after end async case") {
        Stream
          .effectAsyncMaybe[String, Int] { k =>
            k(Pull.emit(1))
            k(Pull.end)
            None
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(1), Left(None), Left(None)))))
      },
      testM("is safe to pull again after end sync case") {
        Stream
          .effectAsyncMaybe[String, Int] { k =>
            k(Pull.fail("Ouch async"))
            Some(Stream.empty)
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(None), Left(None), Left(None)))))
      }
    ),
    suite("Stream.effectAsyncInterrupt")(
      testM("is safe to pull again after error async case") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .effectAsyncInterrupt[String, Int] { k =>
                      List(1, 2, 3).foreach { n =>
                        k(if (n % 2 == 0) Pull.fail("Ouch") else Pull.emit(n))
                      }
                      Left(ref.set(true))
                    }
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(1), Left(Some("Ouch")), Right(3))))
      },
      testM("is safe to pull again after error sync case") {
        Stream
          .effectAsyncInterrupt[String, Int] { k =>
            k(IO.fail(Some("Ouch async")))
            Right(Stream.fail("Ouch sync"))
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch sync")), Left(None), Left(None)))))
      },
      testM("is safe to pull again after end async case") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .effectAsyncInterrupt[String, Int] { k =>
                      k(Pull.emit(1))
                      k(Pull.end)
                      Left(ref.set(true))
                    }
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(1), Left(None), Left(None))))
      },
      testM("is safe to pull again after end sync case") {
        Stream
          .effectAsyncInterrupt[String, Int] { k =>
            k(IO.fail(Some("Ouch async")))
            Right(Stream.empty)
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(None), Left(None), Left(None)))))
      }
    ),
    testM("Stream.fail is safe to pull again") {
      Stream
        .fail("Ouch")
        .process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
    },
    testM("Stream.finalizer is safe to pull again") {
      for {
        ref   <- Ref.make(0)
        pulls <- Stream.finalizer(ref.update(_ + 1)).process.use(nPulls(_, 3))
        fin   <- ref.get
      } yield assert(fin)(equalTo(1)) && assert(pulls)(equalTo(List(Left(None), Left(None), Left(None))))
    },
    testM("Stream.fromChunk is safe to pull again") {
      Stream
        .fromChunk(Chunk(1))
        .process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Right(1), Left(None), Left(None)))))
    },
    suite("Stream.fromEffect")(
      testM("is safe to pull again after success") {
        Stream
          .fromEffect(UIO.succeed(5))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(5), Left(None), Left(None)))))
      },
      testM("is safe to pull again after failure") {
        Stream
          .fromEffect(IO.fail("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    ),
    testM("Stream.fromInputStream is safe to pull again") {
      val error = new java.io.IOException()
      val is = new java.io.InputStream {
        var state = 0

        def read(): Int = {
          state += 1
          if (state == 2) throw error
          else if (state == 4) -1
          else state
        }
      }

      Stream
        .fromInputStream(is, 1)
        .process
        .use(nPulls(_, 5))
        .map(assert(_)(equalTo(List(Right(1.toByte), Left(Some(error)), Right(3.toByte), Left(None), Left(None)))))
    },
    testM("Stream.fromIterable is safe to pull again") {
      Stream
        .fromIterable(List(1))
        .process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Right(1), Left(None), Left(None)))))
    },
    suite("Stream.fromIterator")(
      testM("is safe to pull again after success") {
        Stream
          .fromIterator(UIO.succeed(List(1, 2).iterator))
          .process
          .use(nPulls(_, 4))
          .map(assert(_)(equalTo(List(Right(1), Right(2), Left(None), Left(None)))))
      },
      testM("is safe to pull again after failure") {
        Stream
          .fromIterator(IO.fail("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    ),
    suite("Stream.fromIteratorManaged")(
      testM("is safe to pull again after success") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .fromIteratorManaged(Managed.make(UIO.succeed(List(1, 2).iterator))(_ => ref.set(true)))
                    .process
                    .use(nPulls(_, 4))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(1), Right(2), Left(None), Left(None))))
      },
      testM("is safe to pull again after failed acquisition") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .fromIteratorManaged(Managed.make(IO.fail("Ouch"))(_ => ref.set(true)))
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isFalse) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      },
      testM("is safe to pull again after inner failure") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .fromIteratorManaged(Managed.make(UIO.succeed(List(1, 2).iterator))(_ => ref.set(true)))
                    .flatMap(
                      n => Stream.succeed((n * 2).toString) ++ Stream.fail("Ouch") ++ Stream.succeed((n * 3).toString)
                    )
                    .process
                    .use(nPulls(_, 8))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(
          equalTo(
            List(
              Right("2"),
              Left(Some("Ouch")),
              Right("3"),
              Right("4"),
              Left(Some("Ouch")),
              Right("6"),
              Left(None),
              Left(None)
            )
          )
        )
      },
      testM("is safe to pull again from a failed Managed") {
        Stream
          .fromIteratorManaged(Managed.fail("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    ),
    testM("Stream.fromQueue is safe to pull again") {
      for {
        queue <- Queue.bounded[Int](1)
        pulls <- Stream
                  .fromQueue(queue)
                  .process
                  .use { pull =>
                    for {
                      _  <- queue.offer(1)
                      e1 <- pull.either
                      _  <- queue.offer(2)
                      e2 <- pull.either
                      _  <- queue.shutdown
                      e3 <- pull.either
                      e4 <- pull.either
                    } yield List(e1, e2, e3, e4)
                  }
      } yield assert(pulls)(equalTo(List(Right(1), Right(2), Left(None), Left(None))))
    },
    testM("Stream.fromQueueWithShutdown is safe to pull again") {
      for {
        queue <- Queue.bounded[Int](1)
        pulls <- Stream
                  .fromQueueWithShutdown(queue)
                  .process
                  .use { pull =>
                    for {
                      _  <- queue.offer(1)
                      e1 <- pull.either
                      _  <- queue.offer(2)
                      e2 <- pull.either
                    } yield List(e1, e2)
                  }
        fin <- queue.isShutdown
      } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(1), Right(2))))
    },
    testM("Stream.halt is safe to pull again if failing with a checked error") {
      Stream
        .halt(Cause.fail("Ouch"))
        .process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
    },
    suite("Stream.managed")(
      testM("is safe to pull again after success") {
        for {
          ref   <- Ref.make(false)
          pulls <- Stream.managed(Managed.make(UIO.succeed(5))(_ => ref.set(true))).process.use(nPulls(_, 3))
          fin   <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(5), Left(None), Left(None))))
      },
      testM("is safe to pull again after failed acquisition") {
        for {
          ref   <- Ref.make(false)
          pulls <- Stream.managed(Managed.make(IO.fail("Ouch"))(_ => ref.set(true))).process.use(nPulls(_, 3))
          fin   <- ref.get
        } yield assert(fin)(isFalse) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      },
      testM("is safe to pull again after inner failure") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .managed(Managed.make(UIO.succeed(5))(_ => ref.set(true)))
                    .flatMap(_ => Stream.fail("Ouch"))
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      },
      testM("is safe to pull again from a failed Managed") {
        Stream
          .managed(Managed.fail("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    ),
    testM("Stream.paginate is safe to pull again") {
      Stream
        .paginate(0)(n => (n, None))
        .process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Right(0), Left(None), Left(None)))))
    },
    suite("Stream.paginateM")(
      testM("is safe to pull again after success") {
        Stream
          .paginateM(0)(n => UIO.succeed((n, None)))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(0), Left(None), Left(None)))))
      },
      testM("is safe to pull again after failure") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .paginateM(1) { n =>
                      ref.get.flatMap { done =>
                        if (n == 2 && !done) ref.set(true) *> IO.fail("Ouch")
                        else UIO.succeed((n, Some(n + 1)))
                      }
                    }
                    .process
                    .use(nPulls(_, 3))
        } yield assert(pulls)(equalTo(List(Right(1), Left(Some("Ouch")), Right(2))))
      }
    ),
    testM("Stream.range is safe to pull again") {
      Stream
        .range(1, 4)
        .process
        .use(nPulls(_, 5))
        .map(assert(_)(equalTo(List(Right(1), Right(2), Right(3), Left(None), Left(None)))))
    },
    testM("Stream.repeatEffect is safe to pull again after error") {
      Stream
        .repeatEffect(IO.fail("Ouch"))
        .process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(Some("Ouch")), Left(Some("Ouch"))))))
    },
    testM("Stream.repeatEffectWith is safe to pull again") {
      def effect(ref: Ref[Int]): IO[String, Int] =
        for {
          cnt <- ref.update(_ + 1)
          res <- if (cnt == 2) IO.fail("Ouch") else UIO.succeed(cnt)
        } yield res

      for {
        ref <- Ref.make(0)
        pulls <- Stream
                  .repeatEffectWith(effect(ref), Schedule.recurs(2))
                  .process
                  .use(nPulls(_, 5))
      } yield assert(pulls)(equalTo(List(Right(1), Left(Some("Ouch")), Right(3), Left(None), Left(None))))
    },
    testM("Stream.succeed is safe to pull again") {
      Stream
        .succeed(5)
        .process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Right(5), Left(None), Left(None)))))
    },
    testM("Stream.unfold is safe to pull again") {
      Stream
        .unfold(0) { n =>
          if (n > 2) None
          else Some((n, n + 1))
        }
        .process
        .use(nPulls(_, 5))
        .map(assert(_)(equalTo(List(Right(0), Right(1), Right(2), Left(None), Left(None)))))
    },
    suite("Stream.unfoldM")(
      testM("is safe to pull again after success") {
        Stream
          .unfoldM(0) { n =>
            if (n == 1) UIO.succeed(None)
            else UIO.succeed(Some((n, n + 1)))
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(0), Left(None), Left(None)))))
      },
      testM("is safe to pull again after failure") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .unfoldM(1) { n =>
                      ref.get.flatMap { done =>
                        if (n == 2 && !done) ref.set(true) *> IO.fail("Ouch")
                        else if (n > 2) UIO.succeed(None)
                        else UIO.succeed(Some((n, n + 1)))
                      }
                    }
                    .process
                    .use(nPulls(_, 5))
        } yield assert(pulls)(equalTo(List(Right(1), Left(Some("Ouch")), Right(2), Left(None), Left(None))))
      }
    ),
    suite("Stream.unwrap")(
      testM("is safe to pull again after success") {
        Stream
          .unwrap(UIO.succeed(Stream.fromEffect(UIO.succeed(5))))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(5), Left(None), Left(None)))))
      },
      testM("is safe to pull again after inner failure") {
        Stream
          .unwrap(UIO.succeed(Stream.fail("Ouch")))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      },
      testM("is safe to pull again after outer failure") {
        Stream
          .unwrap(IO.fail("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    ),
    suite("Stream.unwrapManaged")(
      testM("is safe to pull again after success") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .unwrapManaged(Managed.make(UIO.succeed(Stream.fromEffect(UIO.succeed(5))))(_ => ref.set(true)))
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(5), Left(None), Left(None))))
      },
      testM("is safe to pull again after failed acquisition") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .unwrapManaged(Managed.make(IO.fail("Ouch"))(_ => ref.set(true)))
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isFalse) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      },
      testM("is safe to pull again after inner failure") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .unwrapManaged(Managed.make(UIO.succeed(Stream.fromEffect(UIO.succeed(5))))(_ => ref.set(true)))
                    .flatMap(_ => Stream.fail("Ouch"))
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      },
      testM("is safe to pull again from a failed Managed") {
        Stream
          .unwrapManaged(Managed.fail("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    )
  )
}
