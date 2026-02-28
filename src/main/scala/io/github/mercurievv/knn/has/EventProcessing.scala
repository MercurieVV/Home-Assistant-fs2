package io.github.mercurievv.knn.has

import cats.Monad
import cats.arrow.{Arrow, ArrowChoice, Category}
import cats.data.{Kleisli, State}
import cats.effect.kernel.RefSink
import cats.effect.std.MapRef
import cats.implicits.*
import cats.kernel.{Monoid, Semigroup}
import cats.syntax.option.*

trait EventProcessing[-->[_, _]: Arrow](tracked val t: EventProcessing.Types) {
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

trait EventsStreamProcessing[
  ==>[_, _]: Arrow,
  -->[_, _]: ArrowChoice,
  T <: EventsStreamProcessing.Types,
  EP <: EventProcessing[-->],
](
  val espt: T,
  val ep: EP) {

  import espt.*

  import ep.*
  import ep.given
  import ep.t.*

  val consume: Consumer ==> InputEvent
  val produce: Producer ==> (OutputEvent --> Unit)

  val run: (Consumer, Producer) ==> (InputEvent, InputEvent --> Unit) = (consume *** produce) >>>
    Arrow[==>].lift { case (inputEvent, publish) =>
      val processInputAndPublish = ep.run.map(Either.fromOption(_, ())) >>> (Arrow[-->].id[Unit] ||| publish)
      (inputEvent, processInputAndPublish)
    }
}

object EventsStreamProcessing:

  trait Types:
    type Consumer
    type Producer
