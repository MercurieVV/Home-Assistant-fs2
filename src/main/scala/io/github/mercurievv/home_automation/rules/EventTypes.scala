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

  opaque type Out[a] = a

  object Out:
    def apply[A](a: A): Out[A] = a
    def unapply[A](a: Out[A]): A = a
    extension [A](o: Out[A]) def value: A = o

    given Applicative[Out] = new Applicative[Out]:
      override def pure[A](x: A): Out[A] = x
      override def ap[A, B](ff: Out[A => B])(fa: Out[A]): Out[B] = ff(fa)

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
}
