package http4s.extend

import cats.effect.util.CompositeException
import cats.effect.{ Concurrent, ContextShift, IO, Timer }
import cats.instances.parallel._
import cats.syntax.parallel._
import cats.{ Applicative, Id, Monoid, Semigroup, Traverse }
import scala.concurrent.duration.FiniteDuration

/**
  * The semantic of this type-class is slightly different from the Parallel
  * implementation for IO. The main difference is that in case of failure
  * the other actions will not be cancelled. This is the expected behaviour for
  *
  * def parallelMap2[A, B, R](fa: =>IO[A], fb: =>IO[B])(t: FiniteDuration)(f: (A, B) => R): IO[R]
  *
  * - fa succeeds, fb fails     <-> IO.raiseError(fb failure)                         [and vice versa]
  * - fa succeeds, fb timeouts  <-> IO.raiseError(fb TimeoutException)                [and vice versa]
  * - fa fails,    fb timeouts  <-> IO.raiseError((fa failure, fb TimeoutException))  [and vice versa]
  * - fa fails,    fb fails     <-> IO.raiseError((fa failure, fb failure))
  * - fa succeeds, fb succeeds  <-> IO[R]
  */
trait ParallelEffect[F[_]] {

  def parallelMap2[A, B, R](fa: =>F[A], fb: =>F[B])(t: FiniteDuration)(f: (A, B) => R): F[R]

  def parallelRun[A, B](fa: =>F[A], fb: =>F[B])(t: FiniteDuration): F[(A, B)] =
    parallelMap2(fa, fb)(t)(Tuple2.apply)
}

object ParallelEffect
    extends ParallelEffectInstances
    with ParallelEffectFunctions
    with ParallelEffectArityFunctions {
  @inline def apply[F[_]](implicit F: ParallelEffect[F]): ParallelEffect[F] = implicitly
}

sealed private[extend] trait ParallelEffectInstances {

  implicit def ioParallelEffect(
    implicit
    cs: ContextShift[IO],
    ti: Timer[IO]
  ): ParallelEffect[IO] =
    new ParallelEffect[IO] {

      implicit private def tS: Semigroup[Throwable] =
        new Semigroup[Throwable] {
          def combine(x: Throwable, y: Throwable): Throwable = CompositeException(x, y, Nil)
        }

      def parallelMap2[A, B, R](fa: =>IO[A], fb: =>IO[B])(t: FiniteDuration)(f: (A, B) => R): IO[R] =
        (for {
          fibA <- fa.timeout(t).start
          fibB <- fb.timeout(t).start
        } yield
          (fibA.join.attempt, fibB.join.attempt) parMapN {
            case (eta, etb) => (eta, etb) parMapN f
          }) flatMap Concurrent[IO].rethrow
    }
}

sealed private[extend] trait ParallelEffectFunctions {

  /**
    * Traverse derived from ParallelEffect parallelMap2.
    *
    * If used with IO in `F[_]` position it will wait for all the effectful
    * computations to complete up tp a timeout and will aggregate all the
    * eventual errors through the `Semigroup[Throwable]` provided.
    */
  def parallelTraverse[A, B, T[_], F[_]](ta: T[A])(f: A => F[B])(t: FiniteDuration)(
    implicit
    TR: Traverse[T],
    TM: Monoid[T[B]],
    TA: Applicative[T],
    FE: ParallelEffect[F],
    FA: Applicative[F]
  ): F[T[B]] =
    TR.foldM[Id, A, F[T[B]]](ta, FA.pure(TM.empty)) { (ftb, a) =>
      FE.parallelMap2(f(a), ftb)(t) { (b, tb) =>
        TM.combine(TA.pure(b), tb)
      }
    }
}
