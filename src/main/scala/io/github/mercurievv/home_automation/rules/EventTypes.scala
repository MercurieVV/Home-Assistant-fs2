package io.github.mercurievv.home_automation.rules

import io.github.mercurievv.minuscles.opaques.{Opaque, OpaqueFunctor}

import scala.compiletime.*
import scala.deriving.*

import cats.Applicative
import cats.implicits.catsSyntaxEitherId

import monocle.{Iso, Lens, Prism}

object EventTypes {

  object In extends OpaqueApplicative
  type In[a] = In.Opq[a]

  object Out extends OpaqueApplicative
  type Out[a] = Out.Opq[a]

  object St extends OpaqueApplicative // entity from state holder
  type St[a] = St.Opq[a]

  object EntityId extends Opaque[String]
  type EntityId = EntityId.Opq

  case class Topic(
    service: String,
    entityId: EntityId,
    eventType: Option[String]):
    val value: String = service + "/" + entityId + eventType.map("/" + _).getOrElse("")

  object Topic:

    def parse(s: String): Either[Throwable, Topic] =
      s.split("/", 3) match
        case Array(service, entityId)         => Topic(service, EntityId(entityId), None).asRight
        case Array(service, entityId, suffix) => Topic(service, EntityId(entityId), Some(suffix)).asRight
        case _                                => Left(new RuntimeException(s"Can't parse $s"))

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

  trait OpaqueApplicative extends OpaqueFunctor:

    given Applicative[Opq] = new Applicative[Opq]:
      override def pure[A](x: A): Opq[A] = apply(x)
      override def ap[A, B](ff: Opq[A => B])(fa: Opq[A]): Opq[B] = OpaqueApplicative.this.ap(ff)(fa)
}
