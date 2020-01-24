package zio.test.environment

import scala.util.{ Random => SRandom }

import zio._
import zio.random.Random
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._
import zio.test.environment.TestRandom.{ DefaultData, Test => ZRandom }

object RandomSpec extends ZIOBaseSpec {

  def spec = suite("RandomSpec")(
    testM("check clearBooleans")(checkClear(_.nextBoolean)(_.feedBooleans(_: _*))(_.clearBooleans)(_.nextBoolean)),
    testM("check clearBytes")(checkClear(nextBytes(1))(_.feedBytes(_: _*))(_.clearBytes)(_.nextBytes(1))),
    testM("check clearChars")(checkClear(_.nextPrintableChar)(_.feedChars(_: _*))(_.clearChars)(_.nextPrintableChar)),
    testM("check clearDoubles")(checkClear(_.nextDouble)(_.feedDoubles(_: _*))(_.clearDoubles)(_.nextDouble)),
    testM("check clearFloats")(checkClear(_.nextFloat)(_.feedFloats(_: _*))(_.clearFloats)(_.nextFloat)),
    testM("check clearInts")(checkClear(_.nextInt)(_.feedInts(_: _*))(_.clearInts)(_.nextInt)),
    testM("check clearLongs")(checkClear(_.nextLong)(_.feedLongs(_: _*))(_.clearLongs)(_.nextLong)),
    testM("check clearStrings")(checkClear(_.nextString(1))(_.feedStrings(_: _*))(_.clearStrings)(_.nextString(1))),
    testM("check feedBooleans")(checkFeed(_.nextBoolean)(_.feedBooleans(_: _*))(_.nextBoolean)),
    testM("check feedBytes")(checkFeed(nextBytes(1))(_.feedBytes(_: _*))(_.nextBytes(1))),
    testM("check feedChars")(checkFeed(_.nextPrintableChar)(_.feedChars(_: _*))(_.nextPrintableChar)),
    testM("check feedDoubles")(checkFeed(_.nextDouble)(_.feedDoubles(_: _*))(_.nextDouble)),
    testM("check feedFloats")(checkFeed(_.nextFloat)(_.feedFloats(_: _*))(_.nextFloat)),
    testM("check feedInts")(checkFeed(_.nextInt)(_.feedInts(_: _*))(_.nextInt)),
    testM("check feedLongs")(checkFeed(_.nextLong)(_.feedLongs(_: _*))(_.nextLong)),
    testM("check feedStrings")(checkFeed(_.nextString(1))(_.feedStrings(_: _*))(_.nextString(1))),
    testM("check nextBoolean")(forAllEqual(_.nextBoolean)(_.nextBoolean())),
    testM("check nextBytes")(forAllEqualBytes),
    testM("check nextDouble")(forAllEqual(_.nextDouble)(_.nextDouble())),
    testM("check nextFloat")(forAllEqual(_.nextFloat)(_.nextFloat())),
    testM("check nextGaussian")(forAllEqualGaussian),
    testM("check nextInt")(forAllEqual(_.nextInt)(_.nextInt())),
    testM("check nextLong")(forAllEqual(_.nextLong)(_.nextLong())),
    testM("check nextPrintableChar")(forAllEqual(_.nextPrintableChar)(_.nextPrintableChar())),
    testM("check nextString")(forAllEqualN(_.nextString(_))(_.nextString(_))),
    testM("bounded nextInt")(forAllEqualN(_.nextInt(_))(_.nextInt(_))),
    testM("bounded nextInt generates values within the bounds")(forAllBounded(Gen.anyInt)(_.nextInt(_))),
    testM("bounded nextLong generates values within the bounds")(forAllBounded(Gen.anyLong)(_.nextLong(_))),
    testM("shuffle")(forAllEqualShuffle(_.shuffle(_))(_.shuffle(_))),
    testM("referential transparency") {
      val test = TestRandom.makeTest(DefaultData)
      ZIO
        .runtime[Any]
        .map(rt => {
          val x = rt.unsafeRun(test.flatMap[Any, Nothing, Int](_.nextInt))
          val y = rt.unsafeRun(test.flatMap[Any, Nothing, Int](_.nextInt))
          assert(x)(equalTo(y))
        })
    },
    testM("check fed ints do not survive repeating tests") {
      for {
        _      <- ZIO.accessM[TestRandom](_.get[TestRandom.Service].setSeed(5))
        value  <- zio.random.nextInt
        value2 <- zio.random.nextInt
        _      <- ZIO.accessM[TestRandom](_.get[TestRandom.Service].feedInts(1, 2))
      } yield assert(value)(equalTo(-1157408321)) && assert(value2)(equalTo(758500184))
    } @@ nonFlaky
  )

  def checkClear[A, B <: Random](generate: SRandom => A)(feed: (ZRandom, List[A]) => UIO[Unit])(
    clear: ZRandom => UIO[Unit]
  )(extract: ZRandom => UIO[A]): ZIO[Random, Nothing, TestResult] =
    checkM(Gen.anyLong) { seed =>
      for {
        sRandom    <- ZIO.effectTotal(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        value      <- ZIO.effectTotal(generate(sRandom))
        _          <- feed(testRandom, List(value))
        _          <- clear(testRandom)
        random     <- extract(testRandom)
        expected   <- ZIO.effectTotal(generate(new SRandom(seed)))
      } yield assert(random)(equalTo(expected))
    }

  def checkFeed[A, B >: Random](generate: SRandom => A)(
    feed: (ZRandom, List[A]) => UIO[Unit]
  )(extract: ZRandom => UIO[A]): ZIO[Random, Nothing, TestResult] =
    checkM(Gen.anyLong) { seed =>
      for {
        sRandom    <- ZIO.effectTotal(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        values     <- ZIO.effectTotal(List.fill(100)(generate(sRandom)))
        _          <- feed(testRandom, values)
        results    <- UIO.foreach(List.range(0, 100))(_ => extract(testRandom))
        random     <- extract(testRandom)
        expected   <- ZIO.effectTotal(generate(new SRandom(seed)))
      } yield {
        assert(results)(equalTo(values)) &&
        assert(random)(equalTo(expected))
      }
    }

  def nextBytes(n: Int)(random: SRandom): Chunk[Byte] = {
    val arr = new Array[Byte](n)
    random.nextBytes(arr)
    Chunk.fromArray(arr)
  }

  def forAllEqual[A](
    f: ZRandom => UIO[A]
  )(g: SRandom => A): ZIO[Random, Nothing, TestResult] =
    checkM(Gen.anyLong) { seed =>
      for {
        sRandom    <- ZIO.effectTotal(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        actual     <- UIO.foreach(List.fill(100)(()))(_ => f(testRandom))
        expected   <- ZIO.effectTotal(List.fill(100)(g(sRandom)))
      } yield assert(actual)(equalTo(expected))
    }

  def forAllEqualBytes: ZIO[Random, Nothing, TestResult] =
    checkM(Gen.anyLong) { seed =>
      for {
        sRandom    <- ZIO.effectTotal(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        actual     <- UIO.foreach(0 until 100)(testRandom.nextBytes(_))
        expected <- ZIO.effectTotal(List.range(0, 100).map(new Array[Byte](_)).map { arr =>
                     sRandom.nextBytes(arr)
                     Chunk.fromArray(arr)
                   })
      } yield assert(actual)(equalTo(expected))
    }

  def forAllEqualGaussian: ZIO[Random, Nothing, TestResult] =
    checkM(Gen.anyLong) { seed =>
      for {
        sRandom    <- ZIO.effectTotal(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        actual     <- testRandom.nextGaussian
        expected   <- ZIO.effectTotal(sRandom.nextGaussian)
      } yield assert(actual)(approximatelyEquals(expected, 0.01))
    }

  def forAllEqualN[A](
    f: (ZRandom, Int) => UIO[A]
  )(g: (SRandom, Int) => A): ZIO[Random, Nothing, TestResult] =
    checkM(Gen.anyLong, Gen.int(1, 100)) { (seed, size) =>
      for {
        sRandom    <- ZIO.effectTotal(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        actual     <- f(testRandom, size)
        expected   <- ZIO.effectTotal(g(sRandom, size))
      } yield assert(actual)(equalTo(expected))
    }

  def forAllEqualShuffle(
    f: (ZRandom, List[Int]) => UIO[List[Int]]
  )(g: (SRandom, List[Int]) => List[Int]): ZIO[Random with Sized, Nothing, TestResult] =
    checkM(Gen.anyLong, Gen.listOf(Gen.anyInt)) { (seed, testList) =>
      for {
        sRandom    <- ZIO.effectTotal(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        actual     <- f(testRandom, testList)
        expected   <- ZIO.effectTotal(g(sRandom, testList))
      } yield assert(actual)(equalTo(expected))
    }

  def forAllBounded[A: Numeric](gen: Gen[Random, A])(
    next: (Random.Service, A) => UIO[A]
  ): ZIO[Random, Nothing, TestResult] = {
    val num = implicitly[Numeric[A]]
    import num._
    checkM(gen.map(num.abs(_))) { upper =>
      for {
        testRandom <- ZIO.environment[Random].map(_.get[Random.Service])
        nextRandom <- next(testRandom, upper)
      } yield assert(nextRandom)(isWithin(zero, upper))
    }
  }
}
