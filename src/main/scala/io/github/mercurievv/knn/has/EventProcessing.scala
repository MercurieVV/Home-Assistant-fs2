package io.github.mercurievv.knn.has

import cats.Monad
import cats.arrow.{Arrow, ArrowChoice, Category}
import cats.data.{Kleisli, State}
import cats.effect.kernel.RefSink
import cats.effect.std.MapRef
import cats.implicits.*
import cats.kernel.{Monoid, Semigroup}
import cats.syntax.option.*

trait EventProcessing[-->[_, _]: Arrow, EPT <: EventProcessing.Types] {
  val t: EPT
  import t.*

  val updateState: InputEvent --> States
  val makeDecision: (InputEvent, States) --> Option[OutputEvent]

  val run: InputEvent --> Option[OutputEvent] = (Arrow[-->].id &&& updateState) >>> makeDecision
}

object EventProcessing:

  trait Types:
    type InputEvent
    type States
    type OutputEvent

trait BusinessLogic[
  -->[_, _]: Arrow,
  ==>[_, _]: ArrowChoice,
  BLT <: BusinessLogic.Types,
  EPT <: EventProcessing.Types] {
  
  val blt: BLT
  import blt.*
  
  val ep: EventProcessing[==>, EPT]
  import ep.*
  import ep.given
  import ep.t.*
  
  val consume: Consumer --> (Unit ==> InputEvent)
  val produce: Producer --> (OutputEvent ==> Unit)

  val run: (Consumer, Producer) --> (Unit ==> Unit) = (consume *** produce) >>>
    Arrow[-->].lift { case (c, p) =>
      c >>> ep.run.map(Either.fromOption(_, ()))
        >>> (Arrow[==>].id ||| p)
    }
}

object BusinessLogic:

  trait Types:
    type Consumer
    type Producer

