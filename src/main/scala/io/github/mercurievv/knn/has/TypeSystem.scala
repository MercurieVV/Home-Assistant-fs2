package io.github.mercurievv.knn.has

import io.circe.Json

trait TypeSystem {
  type EventId
  type EventState
  type InputEvent = (EventId, EventState)
  type OutputEvent = (EventId, EventState)

  extension (e: InputEvent) {
    def eventId: EventId
    def eventState: EventState
  }
}

object TypeSystem:

  val Impl = new TypeSystem {

    override type EventId = this.type
    override type EventState = this.type
    extension (e: (this.type, this.type)) {
      override def eventId: this.type = ???
      override def eventState: this.type = ???
    }
  }
