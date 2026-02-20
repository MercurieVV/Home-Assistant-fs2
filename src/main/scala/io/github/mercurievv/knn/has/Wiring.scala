package io.github.mercurievv.knn.has

import cats.arrow.Arrow
import io.github.mercurievv.knn.has.state.StateUpdate
import cats.data.Kleisli
import cats.effect.std.MapRef
import cats.implicits.*
import cats.kernel.Monoid
import cats.{Applicative, CommutativeMonad}
import fs2.*
import net.sigusr.mqtt.api.{Message, Session}

object Wiring {

  def wire[F[_]: {CommutativeMonad, Applicative}](
    ts: TypeSystem,
  )(
    decodeMessage: Message => ts.InputEvent,
    encodeMessage: ts.InputEvent => Message,
  )(using Monoid[ts.EventState],
  ) = {
    import ts.*
    type StatesS = MapRef[F, EventId, Option[EventState]]
    type --->[A, B] = Kleisli[F, A, B]
    type S[b] = Stream[F, b]
    type ===>[A, B] = Kleisli[S, A, B]

    val stateUpdate: StateUpdate[--->, InputEvent, EventId, EventState, StatesS] =
      StateUpdate.refMapStateUpdate[F, InputEvent, EventId, EventState, StatesS](
        getEventId     = Kleisli.fromFunction(_.eventId),
        getEntityState = Kleisli.fromFunction(_.eventState),
      )
    val ept = new EventProcessing.Types {
      override type InputEvent = ts.InputEvent
      override type States = StatesS
      override type OutputEvent = ts.OutputEvent
    }
    val ep = (mapRef: StatesS) =>
      new EventProcessing[--->, ept.type] {
        override val t: ept.type = ept

        // import t.*

        private val value: Kleisli[F, InputEvent, (StatesS, InputEvent)] =
          Kleisli.pure[F, InputEvent, StatesS](mapRef) &&& Arrow[--->].id
        override val updateState: InputEvent ---> t.States = (value >>> stateUpdate.apply).as(mapRef)
        override val makeDecision: (InputEvent, t.States) ---> Option[OutputEvent] = ???
      }
    val blti = new EventsStreamProcessing.Types {
      override type Consumer = Session[F]
      override type Producer = Session[F]
    }
    val bl = new EventsStreamProcessing[===>, --->, blti.type, ept.type] {
      override val blt = blti
      import this.blt.*

      override val ep: EventProcessing[--->, ept.type] = ep

      override val consume: Consumer ===> ep.t.InputEvent = Kleisli((_: Consumer).messages).map(decodeMessage)
      override val produce: Producer ===> (ep.t.OutputEvent ---> Unit) =
        Kleisli(producer =>
          Kleisli((oe: ts.OutputEvent) =>
            val msg = encodeMessage(oe)
            producer.publish(msg.topic, msg.payload),
          ).pure,
        )
    }

    bl.run.apply(???, ???).evalMap { case (inputEvent, publish) => publish.apply(inputEvent) }
  }
}
