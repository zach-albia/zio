package zio.test

import zio.Exit.{ Failure, Success }
import zio.random.Random
import zio.stream.ZStream
import zio.test.Assertion.{ equalTo, forall }
import zio.test.environment.TestRandom
import zio.{ Exit, Managed, UIO, ZIO }

object GenUtils {

  def alwaysShrinksTo[R, A](gen: Gen[R, A])(a: A): ZIO[R, Nothing, TestResult] = {
    val shrinks = if (TestPlatform.isJS) 1 else 100
    ZIO.collectAll(List.fill(shrinks)(shrinksTo(gen))).map(assert(_)(forall(equalTo(a))))
  }

  def checkFinite[A, B](
    gen: Gen[Random, A]
  )(assertion: Assertion[B], f: List[A] => B = (a: List[A]) => a): ZIO[Random, Nothing, TestResult] =
    assertM(gen.sample.map(_.value).runCollect.map(f))(assertion)

  def checkSample[A, B](
    gen: Gen[Random with Sized, A],
    size: Int = 100
  )(assertion: Assertion[B], f: List[A] => B = (a: List[A]) => a): ZIO[Random, Nothing, TestResult] =
    assertM(provideSize(sample100(gen).map(f))(size))(assertion)

  def checkShrink[A](gen: Gen[Random with Sized, A])(a: A): ZIO[Random, Nothing, TestResult] =
    provideSize(alwaysShrinksTo(gen)(a: A))(100)

  val deterministic: Gen[Random with Sized, Gen[Any, Int]] =
    Gen.listOf1(Gen.int(-10, 10)).map(as => Gen.fromIterable(as))

  def equal[A](left: Gen[Random, A], right: Gen[Random, A]): UIO[Boolean] =
    equalSample(left, right).zipWith(equalShrink(left, right))(_ && _)

  def equalShrink[A](left: Gen[Random, A], right: Gen[Random, A]): UIO[Boolean] = {
    val testRandom = Managed.fromEffect(TestRandom.make(TestRandom.DefaultData))
    for {
      leftShrinks  <- ZIO.collectAll(List.fill(100)(shrinks(left))).provideManaged(testRandom)
      rightShrinks <- ZIO.collectAll(List.fill(100)(shrinks(right))).provideManaged(testRandom)
    } yield leftShrinks == rightShrinks
  }

  def equalSample[A](left: Gen[Random, A], right: Gen[Random, A]): UIO[Boolean] = {
    val testRandom = Managed.fromEffect(TestRandom.make(TestRandom.DefaultData))
    for {
      leftSample  <- sample100(left).provideManaged(testRandom)
      rightSample <- sample100(right).provideManaged(testRandom)
    } yield leftSample == rightSample
  }

  val genIntList: Gen[Random, List[Int]] = Gen.oneOf(
    Gen.const(List.empty),
    for {
      tail <- Gen.suspend(genIntList)
      head <- Gen.int(-10, 10)
    } yield head :: tail
  )

  val genStringIntFn: Gen[Random, String => Int] = Gen.function(Gen.int(-10, 10))

  def partitionExit[E, A](eas: List[Exit[E, A]]): (List[Failure[E]], List[A]) =
    ZIO.partitionMap(eas) {
      case Success(a)     => Right(a)
      case e @ Failure(_) => Left(e)
    }

  def provideSize[A](zio: ZIO[Random with Sized, Nothing, A])(n: Int): ZIO[Random, Nothing, A] =
    Sized.makeService(n).flatMap { service =>
      zio.provideSome[Random] { r =>
        new Random with Sized {
          val random = r.random
          val sized  = service
        }
      }
    }

  val random: Gen[Any, Gen[Random, Int]] =
    Gen.const(Gen.int(-10, 10))

  def shrinks[R, A](gen: Gen[R, A]): ZIO[R, Nothing, List[A]] =
    gen.sample.forever.take(1).flatMap(_.shrinkSearch(_ => true)).take(1000).runCollect

  def shrinksTo[R, A](gen: Gen[R, A]): ZIO[R, Nothing, A] =
    shrinks(gen).map(_.reverse.head)

  val smallInt = Gen.int(-10, 10)

  def sample[R, A](gen: Gen[R, A]): ZIO[R, Nothing, List[A]] =
    gen.sample.map(_.value).runCollect

  def sample100[R, A](gen: Gen[R, A]): ZIO[R, Nothing, List[A]] =
    gen.sample.map(_.value).forever.take(100).runCollect

  def sampleEffect[E, A](
    gen: Gen[Random with Sized, ZIO[Random with Sized, E, A]],
    size: Int = 100
  ): ZIO[Random, Nothing, List[Exit[E, A]]] =
    provideSize(sample100(gen).flatMap(effects => ZIO.collectAll(effects.map(_.run))))(size)

  def shrink[R, A](gen: Gen[R, A]): ZIO[R, Nothing, A] =
    gen.sample.take(1).flatMap(_.shrinkSearch(_ => true)).take(1000).runLast.map(_.get)

  val shrinkable: Gen[Random, Int] =
    Gen.fromRandomSample(_.nextInt(90).map(_ + 10).map(Sample.shrinkIntegral(0)))

  def shrinkWith[R, A](gen: Gen[R, A])(f: A => Boolean): ZIO[R, Nothing, List[A]] =
    gen.sample.take(1).flatMap(_.shrinkSearch(!f(_))).take(1000).filter(!f(_)).runCollect

  val three = Gen(ZStream(Sample.unfold[Any, Int, Int](3) { n =>
    if (n == 0) (n, ZStream.empty)
    else (n, ZStream(n - 1))
  }))
}
