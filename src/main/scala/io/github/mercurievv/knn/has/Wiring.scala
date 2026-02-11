package io.github.mercurievv.knn.has

import io.github.mercurievv.knn.has.state.StateUpdate

import cats.data.Kleisli
import cats.effect.std.MapRef
import cats.implicits._
import cats.kernel.Monoid
import cats.{Applicative, CommutativeMonad}

object Wiring {

  def wire[F[_]: CommutativeMonad](ts: TypeSystem)(using Monoid[ts.EventState]) = {
    import ts.*
    type States = MapRef[F, EventId, Option[EventState]]
    type -->[A, B] = Kleisli[F, A, B]

    val inputEvents: Unit --> InputEvent = Kleisli(_ => ???)
    val states: States = ??? // ] = MapRef.ofSingleImmutableMap[F, EventId, EventState]()
    val stateUpdate = StateUpdate.refMapStateUpdate[F, InputEvent, EventId, EventState, States](
      getStates      = Kleisli.pure(states),
      getEventId     = Kleisli.fromFunction(_.eventId),
      getEntityState = Kleisli.fromFunction(_.eventState),
    )
    new BusinessLogic[-->, InputEvent, States, OutputEvent](
      inputEvents,
      stateUpdate.apply.as(states),
      ???,
      ???,
    ).businessLogic.apply(())
  }
}
