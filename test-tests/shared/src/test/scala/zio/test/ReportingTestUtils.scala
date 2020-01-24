package zio.test

import scala.{ Console => SConsole }

import zio.clock.Clock
import zio.test.Assertion.{ equalTo, isGreaterThan, isLessThan, isRight, isSome, not }
import zio.test.environment.{ testEnvironmentManaged, TestClock, TestConsole, TestEnvironment }
import zio.test.mock.ExpectationSpecUtils.Module
import zio.test.mock.MockException.{
  InvalidArgumentsException,
  InvalidMethodException,
  UnexpectedCallExpection,
  UnmetExpectationsException
}
import zio.{ Cause, Managed, ZIO }

object ReportingTestUtils {

  def expectedSuccess(label: String): String =
    green("+") + " " + label + "\n"

  def expectedFailure(label: String): String =
    red("- " + label) + "\n"

  def withOffset(n: Int)(s: String): String =
    " " * n + s

  def green(s: String): String =
    SConsole.GREEN + s + SConsole.RESET

  def red(s: String): String =
    SConsole.RED + s + SConsole.RESET

  def blue(s: String): String =
    SConsole.BLUE + s + SConsole.RESET

  def cyan(s: String): String =
    SConsole.CYAN + s + SConsole.RESET

  def yellow(s: String): String =
    SConsole.YELLOW + s + SConsole.RESET

  def reportStats(success: Int, ignore: Int, failure: Int) = {
    val total = success + ignore + failure
    cyan(
      s"Ran $total test${if (total == 1) "" else "s"} in 0 ns: $success succeeded, $ignore ignored, $failure failed"
    ) + "\n"
  }

  def runLog[E](spec: ZSpec[TestEnvironment, String, String, Unit]) =
    for {
      _ <- TestTestRunner(testEnvironmentManaged)
            .run(spec)
            .provideLayer[Nothing, TestEnvironment, TestLogger with Clock](TestLogger.fromConsole ++ TestClock.default)
      output <- TestConsole.output
    } yield output.mkString

  def runSummary[E](spec: ZSpec[TestEnvironment, String, String, Unit]) =
    for {
      results <- TestTestRunner(testEnvironmentManaged)
                  .run(spec)
                  .provideLayer[Nothing, TestEnvironment, TestLogger with Clock](
                    TestLogger.fromConsole ++ TestClock.default
                  )
      actualSummary <- SummaryBuilder.buildSummary(results)
    } yield actualSummary.summary

  private[this] def TestTestRunner(testEnvironment: Managed[Nothing, TestEnvironment]) =
    TestRunner[TestEnvironment, String, String, Unit, Unit](
      executor = TestExecutor.managed[TestEnvironment, String, String, Unit](testEnvironment),
      reporter = DefaultTestReporter(TestAnnotationRenderer.default)
    )

  val test1         = zio.test.test("Addition works fine")(assert(1 + 1)(equalTo(2)))
  val test1Expected = expectedSuccess("Addition works fine")

  val test2         = zio.test.test("Subtraction works fine")(assert(1 - 1)(equalTo(0)))
  val test2Expected = expectedSuccess("Subtraction works fine")

  val test3 = zio.test.test("Value falls within range")(assert(52)(equalTo(42) || (isGreaterThan(5) && isLessThan(10))))
  val test3Expected = Vector(
    expectedFailure("Value falls within range"),
    withOffset(2)(s"${blue("52")} did not satisfy ${cyan("equalTo(42)")}\n"),
    withOffset(2)(
      s"${blue("52")} did not satisfy ${cyan("(") + yellow("equalTo(42)") + cyan(" || (isGreaterThan(5) && isLessThan(10)))")}\n"
    ),
    withOffset(2)(s"${blue("52")} did not satisfy ${cyan("isLessThan(10)")}\n"),
    withOffset(2)(
      s"${blue("52")} did not satisfy ${cyan("(equalTo(42) || (isGreaterThan(5) && ") + yellow("isLessThan(10)") + cyan("))")}\n"
    )
  )

  val test4 = Spec.test("Failing test", failed(Cause.fail("Fail")))
  val test4Expected = Vector(
    expectedFailure("Failing test"),
    withOffset(2)("Fiber failed.\n") +
      withOffset(2)("A checked error was not handled.\n") +
      withOffset(2)("Fail\n") +
      withOffset(2)("No ZIO Trace available.\n")
  )

  val test5 = zio.test.test("Addition works fine")(assert(1 + 1)(equalTo(3)))
  val test5Expected = Vector(
    expectedFailure("Addition works fine"),
    withOffset(2)(s"${blue("2")} did not satisfy ${cyan("equalTo(3)")}\n")
  )

  val test6 = zio.test.test("Multiple nested failures")(assert(Right(Some(3)))(isRight(isSome(isGreaterThan(4)))))
  val test6Expected = Vector(
    expectedFailure("Multiple nested failures"),
    withOffset(2)(s"${blue("3")} did not satisfy ${cyan("isGreaterThan(4)")}\n"),
    withOffset(2)(
      s"${blue("Some(3)")} did not satisfy ${cyan("isSome(") + yellow("isGreaterThan(4)") + cyan(")")}\n"
    ),
    withOffset(2)(
      s"${blue("Right(Some(3))")} did not satisfy ${cyan("isRight(") + yellow("isSome(isGreaterThan(4))") + cyan(")")}\n"
    )
  )

  val test7 = testM("labeled failures") {
    for {
      a <- ZIO.effectTotal(Some(1))
      b <- ZIO.effectTotal(Some(1))
      c <- ZIO.effectTotal(Some(0))
      d <- ZIO.effectTotal(Some(1))
    } yield assert(a)(isSome(equalTo(1)).label("first")) &&
      assert(b)(isSome(equalTo(1)).label("second")) &&
      assert(c)(isSome(equalTo(1)).label("third")) &&
      assert(d)(isSome(equalTo(1)).label("fourth"))
  }
  val test7Expected = Vector(
    expectedFailure("labeled failures"),
    withOffset(2)(s"${blue("0")} did not satisfy ${cyan("equalTo(1)")}\n"),
    withOffset(2)(
      s"${blue("Some(0)")} did not satisfy ${cyan("(isSome(") + yellow("equalTo(1)") + cyan(") ?? \"third\")")}\n"
    )
  )

  val test8 = zio.test.test("Not combinator") {
    assert(100)(not(equalTo(100)))
  }
  val test8Expected = Vector(
    expectedFailure("Not combinator"),
    withOffset(2)(s"${blue("100")} satisfied ${cyan("equalTo(100)")}\n"),
    withOffset(2)(
      s"${blue("100")} did not satisfy ${cyan("not(") + yellow("equalTo(100)") + cyan(")")}\n"
    )
  )

  val suite1 = suite("Suite1")(test1, test2)
  val suite1Expected = Vector(
    expectedSuccess("Suite1"),
    withOffset(2)(test1Expected),
    withOffset(2)(test2Expected)
  )

  val suite2 = suite("Suite2")(test1, test2, test3)
  val suite2Expected = Vector(
    expectedFailure("Suite2"),
    withOffset(2)(test1Expected),
    withOffset(2)(test2Expected)
  ) ++ test3Expected.map(withOffset(2)(_))

  val suite3 = suite("Suite3")(suite1, suite2, test3)
  val suite3Expected = Vector(expectedFailure("Suite3")) ++
    suite1Expected.map(withOffset(2)) ++
    suite2Expected.map(withOffset(2)) ++
    test3Expected.map(withOffset(2))

  val suite4 = suite("Suite4")(suite1, suite("Empty")(), test3)
  val suite4Expected = Vector(expectedFailure("Suite4")) ++
    suite1Expected.map(withOffset(2)) ++
    test3Expected.map(withOffset(2))

  val mock1 = zio.test.test("Expected method, wrong arguments") {
    throw InvalidArgumentsException(Module.command, 2, equalTo(1))
  }

  val mock1Expected = Vector(
    expectedFailure("Expected method, wrong arguments"),
    withOffset(2)(s"${red("- zio.test.mock.ExpectationSpecUtils.Module.command called with invalid arguments")}\n"),
    withOffset(4)(s"${blue("2")} did not satisfy ${cyan("equalTo(1)")}\n")
  )

  val mock2 = zio.test.test("Wrong method") {
    throw InvalidMethodException(Module.singleParam, Module.command, equalTo(1))
  }

  val mock2Expected = Vector(
    expectedFailure("Wrong method"),
    withOffset(2)(s"${red("- invalid call to zio.test.mock.ExpectationSpecUtils.Module.singleParam")}\n"),
    withOffset(4)(s"expected zio.test.mock.ExpectationSpecUtils.Module.command with arguments ${cyan("equalTo(1)")}\n")
  )

  val mock3 = zio.test.test("Unsatisfied expectations") {
    throw UnmetExpectationsException(
      List(
        Module.command -> (equalTo(2)),
        Module.command -> (equalTo(3))
      )
    )
  }

  val mock3Expected = Vector(
    expectedFailure("Unsatisfied expectations"),
    withOffset(2)(s"${red("- unmet expectations")}\n"),
    withOffset(4)(s"expected zio.test.mock.ExpectationSpecUtils.Module.command with arguments ${cyan("equalTo(2)")}\n"),
    withOffset(4)(s"expected zio.test.mock.ExpectationSpecUtils.Module.command with arguments ${cyan("equalTo(3)")}\n")
  )

  val mock4 = zio.test.test("Extra calls") {
    throw UnexpectedCallExpection(Module.manyParams, (2, "3", 4L))
  }

  val mock4Expected = Vector(
    expectedFailure("Extra calls"),
    withOffset(2)(
      s"${red("- unexpected call to zio.test.mock.ExpectationSpecUtils.Module.manyParams with arguments")}\n"
    ),
    withOffset(4)(s"${cyan("(2,3,4)")}\n")
  )
}
