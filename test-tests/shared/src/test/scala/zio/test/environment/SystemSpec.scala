package zio.test.environment

import zio.system
import zio.test.Assertion._
import zio.test.TestAspect.nonFlaky
import zio.test._
import zio.test.environment.TestSystem._

object SystemSpec extends ZIOBaseSpec {

  def spec = suite("SystemSpec")(
    testM("check set values are cleared at the start of repeating tests") {
      for {
        env <- system.env("k1")
        _   <- putEnv("k1", "v1")
      } yield assert(env)(isNone)
    } @@ nonFlaky,
    testM("fetch an environment variable and check that if it exists, return a reasonable value") {
      for {
        _   <- putEnv("k1", "v1")
        env <- system.env("k1")
      } yield assert(env)(isSome(equalTo("v1")))
    },
    testM("fetch an environment variable and check that if it does not exist, return None") {
      for {
        env <- system.env("k1")
      } yield assert(env)(isNone)
    },
    testM("fetch an environment variable and check that if it is set, return the set value") {
      for {
        _   <- putEnv("k1", "v1")
        env <- system.env("k1")
      } yield assert(env)(isSome(equalTo("v1")))
    },
    testM("fetch an environment variable and check that if it is cleared, return None") {
      for {
        _   <- putEnv("k1", "v1")
        _   <- clearEnv("k1")
        env <- system.env("k1")
      } yield assert(env)(isNone)
    },
    testM("fetch a VM property and check that if it exists, return a reasonable value") {
      for {
        _    <- putProperty("k1", "v1")
        prop <- system.property("k1")
      } yield assert(prop)(isSome(equalTo("v1")))
    },
    testM("fetch a VM property and check that if it does not exist, return None") {
      for {
        prop <- system.property("k1")
      } yield assert(prop)(isNone)
    },
    testM("fetch a VM property and check that if it is set, return the set value") {
      for {
        _    <- putProperty("k1", "v1")
        prop <- system.property("k1")
      } yield assert(prop)(isSome(equalTo("v1")))
    },
    testM("fetch a VM property and check that if it is cleared, return None") {
      for {
        _    <- putProperty("k1", "v1")
        _    <- clearProperty("k1")
        prop <- system.property(("k1"))
      } yield assert(prop)(isNone)
    },
    testM("fetch the system's line separator and check that it is identical to Data.lineSeparator") {
      TestSystem.live(Data(lineSeparator = ",")).build.map(_.get[zio.system.System.Service]).use { testSystem =>
        assertM(testSystem.lineSeparator)(equalTo(","))
      }
    },
    testM("fetch the system's line separator and check that if it is set, return the set value") {
      for {
        _       <- setLineSeparator(",")
        lineSep <- system.lineSeparator
      } yield assert(lineSep)(equalTo(","))
    }
  )
}
