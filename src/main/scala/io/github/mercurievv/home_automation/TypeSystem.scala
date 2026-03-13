package io.github.mercurievv.home_automation

import cats.Functor
import io.github.mercurievv.home_automation.rules.EventTypes.{In, Out}

trait TypeSystem {
  type EventId
  type EventState
  type Event[F[_]] = F[(EventId, EventState)]
  type InputEvent = Event[In]
  type OutputEvent = Event[Out]
  type States

  extension [F[_]: Functor](e: Event[F]) {
    def eventId: F[EventId]
    def eventState: F[EventState]
  }
}
