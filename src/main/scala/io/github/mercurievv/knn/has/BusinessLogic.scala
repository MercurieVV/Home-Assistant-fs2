package io.github.mercurievv.knn.has

import cats.Monad
import cats.arrow.{Arrow, ArrowChoice, Category}
import cats.data.{Kleisli, State}
import cats.effect.kernel.RefSink
import cats.effect.std.MapRef
import cats.implicits._
import cats.kernel.{Monoid, Semigroup}

trait BusinessLogic[T <: BusinessLogic.Dsl](using val dsl: T) {

  import dsl.*

  def businessLogic: Unit --> Option[OutputEvent] =
    eventSource >>> (A.id &&& updateState) >>> processEvent
}

object BusinessLogic:

  trait Dsl {
    type InputEvent
    type States
    type -->[_, _]
    type OutputEvent

    val A = ArrowChoice[-->]

    given ArrowChoice[-->] = scala.compiletime.deferred

    def eventSource: Unit --> InputEvent

    //  def getStateFetcher: Unit --> StateFetcher
    def updateState: InputEvent --> States
    // def filter: Unit  --> InputEvent

    def processEvent: (InputEvent, States) --> Option[OutputEvent]
  }
