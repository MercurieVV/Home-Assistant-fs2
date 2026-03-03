package io.github.mercurievv.knn.has

import cats.arrow.{Arrow, ArrowChoice}
import cats.implicits.*

case class EventProcessing[-->[_, _]: Arrow, T <: EventProcessing.Types](
  t: T,
  updateState: t.InputEvent --> t.States,
  makeDecision: (t.InputEvent, t.States) --> Option[t.OutputEvent]) {
  import t.*

  val run: InputEvent --> Option[OutputEvent] = (Arrow[-->].id &&& updateState) >>> makeDecision
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

  import espt.*

  import ep.t.*

  val consume: Consumer ==> InputEvent
  val produce: Producer ==> (OutputEvent --> Unit)

  type EventProcessor = (InputEvent, InputEvent --> Unit)

  val run: (Consumer, Producer) ==> EventProcessor = (consume *** produce) >>>
    Arrow[==>].lift { case (inputEvent, publish) =>
      val processInputAndPublish = ep.run.map(Either.fromOption(_, ())) >>> (Arrow[-->].id[Unit] ||| publish)
      (inputEvent, processInputAndPublish)
    }
}

object EventsStreamProcessing:

  trait Types:
    type Consumer
    type Producer
