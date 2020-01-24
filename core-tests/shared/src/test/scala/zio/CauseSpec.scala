package zio

import zio.Cause.{ Both, Then }
import zio.random.Random
import zio.test.Assertion._
import zio.test._

object CauseSpec extends ZIOBaseSpec {

  def spec = suite("CauseSpec")(
    suite("Cause")(
      testM("`Cause#died` and `Cause#stripFailures` are consistent") {
        check(causes) { c =>
          assert(c.stripFailures)(if (c.died) isSome(anything) else isNone)
        }
      },
      testM("`Cause.equals` is symmetric") {
        check(causes, causes) { (a, b) =>
          assert(a == b)(equalTo(b == a))
        }
      },
      testM("`Cause.equals` and `Cause.hashCode` satisfy the contract") {
        check(equalCauses) {
          case (a, b) =>
            assert(a.hashCode)(equalTo(b.hashCode))
        }
      },
      testM("`Cause#untraced` removes all traces") {
        check(causes) { c =>
          assert(c.untraced.traces.headOption)(isNone)
        }
      },
      zio.test.test("`Cause.failures is stack safe") {
        val n     = 100000
        val cause = List.fill(n)(Cause.fail("fail")).reduce(_ && _)
        assert(cause.failures.length)(equalTo(n))
      }
    ),
    suite("Then")(
      testM("`Then.equals` satisfies associativity") {
        check(causes, causes, causes) { (a, b, c) =>
          assert(Then(Then(a, b), c))(equalTo(Then(a, Then(b, c)))) &&
          assert(Then(a, Then(b, c)))(equalTo(Then(Then(a, b), c)))
        }
      },
      testM("`Then.equals` satisfies distributivity") {
        check(causes, causes, causes) { (a, b, c) =>
          assert(Then(a, Both(b, c)))(equalTo(Both(Then(a, b), Then(a, c)))) &&
          assert(Then(Both(a, b), c))(equalTo(Both(Then(a, c), Then(b, c))))
        }
      }
    ),
    suite("Both")(
      testM("`Both.equals` satisfies associativity") {
        check(causes, causes, causes) { (a, b, c) =>
          assert(Both(Both(a, b), c))(equalTo(Both(a, Both(b, c)))) &&
          assert(Both(a, Both(b, c)))(equalTo(Both(Both(a, b), c)))
        }
      },
      testM("`Both.equals` satisfies distributivity") {
        check(causes, causes, causes) { (a, b, c) =>
          assert(Both(Then(a, b), Then(a, c)))(equalTo(Then(a, Both(b, c)))) &&
          assert(Both(Then(a, c), Then(b, c)))(equalTo(Then(Both(a, b), c)))
        }
      },
      testM("`Both.equals` satisfies commutativity") {
        check(causes, causes) { (a, b) =>
          assert(Both(a, b))(equalTo(Both(b, a)))
        }
      }
    ),
    suite("Meta")(
      testM("`Meta` is excluded from equals") {
        check(causes) { c =>
          assert(Cause.stackless(c))(equalTo(c)) &&
          assert(c)(equalTo(Cause.stackless(c)))
        }
      },
      testM("`Meta` is excluded from hashCode") {
        check(causes) { c =>
          assert(Cause.stackless(c).hashCode)(equalTo(c.hashCode))
        }
      }
    ),
    suite("Empty")(
      testM("`Empty` is empty element for `Then`") {
        check(causes) { c =>
          assert(Then(c, Cause.empty))(equalTo(c)) &&
          assert(Then(Cause.empty, c))(equalTo(c))
        }
      },
      testM("`Empty` is empty element for `Both`") {
        check(causes) { c =>
          assert(Both(c, Cause.empty))(equalTo(c)) &&
          assert(Both(Cause.empty, c))(equalTo(c))
        }
      }
    ),
    suite("Monad Laws:")(
      testM("Left identity") {
        check(causes) { c =>
          assert(c.flatMap(Cause.fail))(equalTo(c))
        }
      },
      testM("Right identity") {
        check(errors, errorCauseFunctions) { (e, f) =>
          assert(Cause.fail(e).flatMap(f))(equalTo(f(e)))
        }
      },
      testM("Associativity") {
        check(causes, errorCauseFunctions, errorCauseFunctions) { (c, f, g) =>
          assert(c.flatMap(f).flatMap(g))(equalTo(c.flatMap(e => f(e).flatMap(g))))
        }
      }
    ),
    suite("Extractors")(
      testM("Fail") {
        check(errors) { e1 =>
          val result = Cause.Fail(e1) match {
            case Cause.Fail(e2) => e1 == e2
            case _              => false
          }
          assert(result)(isTrue)
        }
      },
      testM("Die") {
        check(throwables) { t1 =>
          val result = Cause.Die(t1) match {
            case Cause.Die(t2) => t1 == t2
            case _             => false
          }
          assert(result)(isTrue)
        }
      },
      testM("Interrupt") {
        check(fiberIds) { fiberId1 =>
          val result = Cause.Interrupt(fiberId1) match {
            case Cause.Interrupt(fiberId2) => fiberId1 == fiberId2
            case _                         => false
          }
          assert(result)(isTrue)
        }
      },
      testM("Traced") {
        check(causes) { cause1 =>
          val trace1 = ZTrace(Fiber.Id(0L, 0L), Nil, Nil, None)
          val result = Cause.traced(cause1, trace1) match {
            case Cause.Traced(cause2, trace2) => cause1 == cause2 && trace1 == trace2
            case _                            => false
          }
          assert(result)(isTrue)
        }
      },
      testM("Meta") {
        check(causes) {
          cause =>
            val result = (cause, Cause.stackless(cause)) match {
              case (Cause.Empty(), Cause.Empty())                               => true
              case (Cause.Fail(e1), Cause.Fail(e2))                             => e1 == e2
              case (Cause.Die(t1), Cause.Die(t2))                               => t1 == t2
              case (Cause.Interrupt(fiberId1), Cause.Interrupt(fiberId2))       => fiberId1 == fiberId2
              case (Cause.Traced(cause1, trace1), Cause.Traced(cause2, trace2)) => cause1 == cause2 && trace1 == trace2
              case (Cause.Then(left1, right1), Cause.Then(left2, right2))       => left1 == left2 && right1 == right2
              case (Cause.Both(left1, right1), Cause.Both(left2, right2))       => left1 == left2 && right1 == right2
              case _                                                            => false
            }
            assert(result)(isTrue)
        }
      },
      testM("Then") {
        check(causes, causes) { (left1, right1) =>
          val result = Cause.Then(left1, right1) match {
            case Cause.Then(left2, right2) => left1 == left2 && right1 == right2
            case e =>
              println(e)
              println(Cause.Then(left1, right1))
              println("WARNING!!!")
              false
          }
          assert(result)(isTrue)
        }
      },
      testM("Both") {
        check(causes, causes) { (left1, right1) =>
          val result = Cause.Both(left1, right1) match {
            case Cause.Both(left2, right2) => left1 == left2 && right1 == right2
            case _                         => false
          }
          assert(result)(isTrue)
        }
      }
    ),
    suite("squashWithTrace")(
      testM("converts Cause to original exception with ZTraces in root cause") {
        val throwable = (Gen.alphaNumericString <*> Gen.alphaNumericString).flatMap {
          case (msg1, msg2) =>
            Gen
              .elements(
                new IllegalArgumentException(msg2),
                // null cause can't be replaced using Throwable.initCause() on the JVM
                new IllegalArgumentException(msg2, null)
              )
              .map(new Throwable(msg1, _))
        }
        val failOrDie = Gen.elements[Throwable => Cause[Throwable]](Cause.fail, Cause.die)
        check(throwable, failOrDie) { (e, makeCause) =>
          val rootCause        = makeCause(e)
          val cause            = Cause.traced(rootCause, ZTrace(Fiber.Id(0L, 0L), Nil, Nil, None))
          val causeMessage     = e.getCause.getMessage
          val throwableMessage = e.getMessage
          val renderedCause    = Cause.stackless(cause).prettyPrint
          val squashed         = cause.squashWithTrace(identity)

          assert(squashed)(
            equalTo(e) &&
              hasMessage(equalTo(throwableMessage)) &&
              hasThrowableCause(
                isSubtype[IllegalArgumentException](
                  hasMessage(equalTo(causeMessage)) &&
                    hasThrowableCause(hasMessage(equalTo(renderedCause)))
                )
              )
          )
        }
      }
    )
  )

  val causes: Gen[Random with Sized, Cause[String]] =
    Gen.causes(Gen.anyString, Gen.anyString.map(s => new RuntimeException(s)))

  val equalCauses: Gen[Random with Sized, (Cause[String], Cause[String])] =
    (causes <*> causes <*> causes).flatMap {
      case ((a, b), c) =>
        Gen.elements(
          (a, a),
          (a, Cause.traced(a, ZTrace(Fiber.Id(0L, 0L), Nil, Nil, None))),
          (Then(Then(a, b), c), Then(a, Then(b, c))),
          (Then(a, Both(b, c)), Both(Then(a, b), Then(a, c))),
          (Both(Both(a, b), c), Both(a, Both(b, c))),
          (Both(Then(a, c), Then(b, c)), Then(Both(a, b), c)),
          (Both(a, b), Both(b, a)),
          (a, Cause.stackless(a)),
          (a, Then(a, Cause.empty)),
          (a, Both(a, Cause.empty))
        )
    }

  val errorCauseFunctions: Gen[Random with Sized, String => Cause[String]] =
    Gen.function(causes)

  val errors: Gen[Random with Sized, String] =
    Gen.anyString

  val fiberIds: Gen[Random, Fiber.Id] =
    Gen.anyLong.zipWith(Gen.anyLong)(Fiber.Id(_, _))

  val throwables: Gen[Random, Throwable] =
    Gen.throwable
}
