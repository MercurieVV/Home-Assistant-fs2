package io.github.mercurievv.home_automation.rules

import io.github.mercurievv.minuscles.opaques.Opaque

import scala.compiletime.*
import scala.deriving.*

import cats.Applicative

import monocle.{Iso, Lens, Prism}

object EventTypes {

  object In extends OpaqueFunctor
  type In[a] = In.F[a]

  object Out extends OpaqueFunctor
  type Out[a] = Out.F[a]

  object St extends OpaqueFunctor // entity from state holder
  type St[a] = St.F[a]

  object EntityId extends Opaque[String]
  type EntityId = EntityId.Opq

  trait Toggleable[A]:
    extension (a: A) def toggle: A

  object Toggleable:
    inline def apply[A](using t: Toggleable[A]): Toggleable[A] = t

    def fromLens[A, B: Toggleable](l: Lens[A, B]): Toggleable[A] = new Toggleable[A]:
      val mod = l.modify(Toggleable[B].toggle)
      extension (a: A)
        def toggle: A =
          mod(a)

    private inline def summonCases[T <: Tuple]: List[Any] =
      inline erasedValue[T] match
        case _: EmptyTuple => Nil
        case _: (h *: t)   => summonInline[ValueOf[h]].value :: summonCases[t]

    private def fromCases[A](
      allCases: List[A],
      ordinal: A => Int,
    ): Toggleable[A] =
      new Toggleable[A]:
        extension (a: A) def toggle: A = allCases((ordinal(a) + 1) % allCases.size)

    inline def derived[A](using m: Mirror.SumOf[A]): Toggleable[A] =
      fromCases(summonCases[m.MirroredElemTypes].asInstanceOf[List[A]], m.ordinal)

  enum Closeable:
    case OPEN, CLOSE, STOP

  given Toggleable[OnOffState]:
    extension (a: OnOffState) def toggle: OnOffState = OnOffState.toggle(a)

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
