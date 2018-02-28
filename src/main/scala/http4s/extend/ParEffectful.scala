package http4s.extend

import cats.Semigroup
import cats.effect.{Effect, IO}
import cats.syntax.apply._
import cats.syntax.either._

import scala.concurrent.ExecutionContext

/**
  * parMap2 and parMap3, parTupled2 and parTupled3 describe the parallel execution of F[_]
  * in parallel. The IO instance is implemented in terms of fs2 async.start. This bit will
  * be simplified a lot when an instance for cats Parallel wil be available to the public
  */
trait ParEffectful[F[_]] {
  def parMap[A, B, R](fa: =>F[A], fb: =>F[B])(f: (A, B) => R): F[R]
}

sealed trait ParEffectfulInstances {

  implicit def ioEffectfulOp(implicit ev2: Semigroup[Throwable], ec: ExecutionContext): ParEffectful[IO] =
    new ParEffectful[IO] {

      val evidence = Effect[IO]

      def parMap[A, B, R](fa: =>IO[A], fb: =>IO[B])(f: (A, B) => R): IO[R] =
        (fs2.async.start(fa), fs2.async.start(fb)) mapN {
          (ioa, iob) =>
            evidence.rethrow(
              (ioa.attempt, iob.attempt) mapN {
                case (Right(a), Right(b)) => f(a, b).asRight
                case (Left(ea), Left(eb)) => ev2.combine(ea, eb).asLeft
                case (Left(ea), _)        => ea.asLeft
                case (_, Left(eb))        => eb.asLeft
              }
            )
        } flatMap identity
    }
}

sealed trait ParEffectfulFunctions {

  def parMap2[F[_], A, B, R](fa: =>F[A], fb: =>F[B])(f: (A, B) => R)(implicit ev: ParEffectful[F]): F[R] =
    ev.parMap(fa, fb)(f)

  def parTupled2[F[_], A, B](fa: =>F[A], fb: =>F[B])(implicit ev: ParEffectful[F]): F[(A, B)] =
    ev.parMap(fa, fb)(Tuple2.apply)

  def parMap3[F[_], A, B, C, R](fa: =>F[A], fb: =>F[B], fc: =>F[C])(f: (A, B, C) => R)(implicit ev: ParEffectful[F]): F[R] =
    ev.parMap(fa, parMap2(fb, fc)(Tuple2.apply))((a, bc) => f(a, bc._1, bc._2))

  def parTupled3[F[_], A, B, C](fa: =>F[A], fb: =>F[B], fc: =>F[C])(implicit ev: ParEffectful[F]): F[(A, B, C)] =
    parMap3(fa, fb, fc)(Tuple3.apply)
}

object ParEffectful extends ParEffectfulInstances with ParEffectfulFunctions {
  @inline def apply[F[_]](implicit F: ParEffectful[F]): ParEffectful[F] = F
}