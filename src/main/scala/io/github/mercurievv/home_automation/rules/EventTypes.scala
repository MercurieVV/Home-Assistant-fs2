package io.github.mercurievv.home_automation.rules

import io.github.mercurievv.minuscles.opaques.Opaque

import cats.Applicative

import monocle.{Iso, Prism}

object EventTypes {
  opaque type In[a] = a

  object In:
    def apply[A](a: A): In[A] = a
    def unapply[A](a: In[A]): A = a
    extension [A](o: In[A]) def value: A = o

    given Applicative[In] = new Applicative[In]:
      override def pure[A](x: A): In[A] = x
      override def ap[A, B](ff: In[A => B])(fa: In[A]): In[B] = ff(fa)

  object Out extends OpaqueFunctor
  type Out[a] = Out.F[a]

  opaque type St[a] = a

  object St: // entity from state holder
    def apply[A](a: A): St[A] = a
    def unapply[A](a: St[A]): A = a
    extension [A](o: St[A]) def value: A = o

    given Applicative[St] = new Applicative[St]:
      override def pure[A](x: A): St[A] = x
      override def ap[A, B](ff: St[A => B])(fa: St[A]): St[B] = ff(fa)

  object EntityId extends Opaque[String]
  type EntityId = EntityId.Opq

  object OnOffState extends Opaque[Boolean]:
    import implicits.opqToRaw
    val On: OnOffState = OnOffState.apply(true)
    val Off: OnOffState = OnOffState.apply(false)

    val prism: Prism[String, OnOffState] =
      Iso[String, String](_.toUpperCase)(identity) andThen Prism.partial[String, OnOffState] {
        case "ON"  => On
        case "OFF" => Off
      }(o => if o.isOn then "ON" else "OFF")

    extension (o: Opq) {
      def isOn: Boolean = o
      def isOff: Boolean = !o
      def toggle: Opq = apply(!o)
    }

  type OnOffState = OnOffState.Opq

  trait OpaqueFunctor:
    opaque type F[a] = a
    def apply[A](a: A): F[A] = a
    def unapply[A](a: F[A]): A = a
    extension [A](o: F[A]) def value: A = o

    given Applicative[F] = new Applicative[F]:
      override def pure[A](x: A): F[A] = x
      override def ap[A, B](ff: F[A => B])(fa: F[A]): F[B] = ff(fa)
}
