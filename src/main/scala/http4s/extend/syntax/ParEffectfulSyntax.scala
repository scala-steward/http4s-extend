package http4s.extend.syntax

import cats.{Applicative, Monoid, Traverse}
import http4s.extend.ParEffectful

private[syntax] trait ParEffectfulSyntax extends ParEffectfulAritySyntax {
  implicit def parEffectfulTraverseSyntax[T[_] : Traverse : Applicative, A](t: T[A]) = new TraverseParEffectfulOps(t)
}

private[syntax] final class TraverseParEffectfulOps[T[_] : Traverse : Applicative, A](t: T[A]) {
  def parTraverse[F[_] : ParEffectful : Applicative, B](f: A => F[B])(implicit ev: Monoid[T[B]]): F[T[B]] =
    ParEffectful.parTraverse(t)(f)
}