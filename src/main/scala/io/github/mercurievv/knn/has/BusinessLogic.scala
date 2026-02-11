package io.github.mercurievv.knn.has

import cats.Monad
import cats.arrow.{Arrow, ArrowChoice, Category}
import cats.data.{Kleisli, State}
import cats.effect.kernel.RefSink
import cats.effect.std.MapRef
import cats.implicits._
import cats.kernel.{Monoid, Semigroup}
import cats.syntax.option._

case class BusinessLogic[-->[_, _]: ArrowChoice, InputEvent, States, OutputEvent](
  eventSource: Unit --> InputEvent,
  updateState: InputEvent --> States,
  processEvent: (InputEvent, States) --> Option[OutputEvent],
  sendEvent: OutputEvent --> Unit) {

  def businessLogic: Unit --> Unit = {
    val processingOutput: Unit --> Option[OutputEvent] =
      eventSource >>> (Arrow[-->].id &&& updateState) >>> processEvent

    processingOutput.map(Either.fromOption(_, ())) >>> (Arrow[-->].id ||| sendEvent)
  }
}
