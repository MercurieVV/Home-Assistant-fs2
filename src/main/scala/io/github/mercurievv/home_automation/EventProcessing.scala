package io.github.mercurievv.home_automation

import cats.arrow.{Arrow, ArrowChoice}
import cats.implicits.*

case class EventProcessing[-->[_, _]: Arrow, T <: EventProcessing.Types](
  t: T,
  updateState: t.InputEvent --> t.States,
  makeDecision: (t.InputEvent, t.States) --> t.OutputEvent) {
  import t.*

  def run: InputEvent --> OutputEvent = (Arrow[-->].id &&& updateState) >>> makeDecision
}

object EventProcessing:

  trait Types:
    type InputEvent
    type States
    type OutputEvent

trait EventsStreamProcessing[
  ==>[_, _]: Arrow,
  -->[_, _]: ArrowChoice,
  T <: EventsStreamProcessing.Types,
  TT <: EventProcessing.Types,
  EP <: EventProcessing[-->, TT],
](
  val espt: T,
  val ep: EP) {

  import ep.t.*
  import espt.*

  val consume: Consumer ==> InputEvent
  val produce: Producer ==> (OutputEvent --> Unit)

  type EventProcessor = (InputEvent, InputEvent --> Unit)

  lazy val run: (Consumer, Producer) ==> EventProcessor = (consume *** produce) >>>
    Arrow[==>].lift { case (inputEvent, publish) =>
      (
        inputEvent,
        ep.run >>> publish,
      )
    }
}

object EventsStreamProcessing:

  trait Types:
    type Consumer
    type Producer
