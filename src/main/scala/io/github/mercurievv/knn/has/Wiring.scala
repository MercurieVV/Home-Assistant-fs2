package io.github.mercurievv.knn.has

import cats.arrow.Arrow
import io.github.mercurievv.knn.has.impl.TypesWiring
import io.github.mercurievv.knn.has.state.StateUpdate
import cats.data.Kleisli
import cats.effect.std.MapRef
import cats.implicits.*
import cats.kernel.Monoid
import cats.{Applicative, CommutativeMonad, Id, Monad}
import fs2.*
import net.sigusr.mqtt.api.{Message, Session}

object Wiring {

  type TypeSystemWithStates[F[_]] = TypeSystem {
    type States = MapRef[F, EventId, Option[EventState]]
  }

  def wire[F[_]: {CommutativeMonad, Applicative}](
    ts: TypeSystemWithStates[F],
  )(
    decodeMessage: Message => ts.InputEvent,
    encodeMessage: ts.InputEvent => Message,
  )(using MES: Monoid[ts.EventState],
  ): Unit = {
    val tw = new TypesWiring[F](ts)
    import tw.*
    val epti = eventProcessingTypes
    val espti = eventStreamProcessingTypes

    type StatesS = MapRef[F, ts.EventId, Option[ts.EventState]]
    type --->[A, B] = Kleisli[F, A, B]
    type S[b] = Stream[F, b]
    type ===>[A, B] = Kleisli[S, A, B]

    type StateUpdateTSI = StateUpdateTS[--->, StatesS]
    val stateUpdate = Kleisli[Id, ts.type, StateUpdateTSI]((tss: ts.type) =>
      StateUpdate.refMapStateUpdate[F, tss.InputEvent, tss.EventId, tss.EventState, StatesS](
        getEventId     = Kleisli.fromFunction(_.eventId),
        getEntityState = Kleisli.fromFunction(_.eventState),
      ),
    )

    type EP = EventProcessing[--->](epti)
    val epk: Kleisli[Id, (StatesS, StateUpdateTSI), EP] = Kleisli { case (mapRef, stateUpdate) =>
      new EventProcessing[--->](epti) {

        import epti.*

        private val value: Kleisli[F, InputEvent, (StatesS, InputEvent)] =
          Kleisli.pure[F, InputEvent, StatesS](mapRef) &&& Arrow[--->].id
        override val updateState: InputEvent ---> States = (value >>> stateUpdate.apply).as(mapRef)
        override val makeDecision: (InputEvent, States) ---> Option[OutputEvent] = ???
      }
    }

    type ESP = EventsStreamProcessing[===>, --->, ESPTTS, EP]
    val processing: Kleisli[Id, EP, ESP] = Kleisli((epp: EP) =>
      new EventsStreamProcessing[===>, --->, ESPTTS, EP](espti, epp) {
        import espt.*

        summon[ep.t.InputEvent =:= ts.InputEvent]

        override val consume: Consumer ===> ep.t.InputEvent = Kleisli((_: Consumer).messages).map(decodeMessage)
        override val produce: Producer ===> (ep.t.OutputEvent ---> Unit) =
          Kleisli(producer =>
            Kleisli((oe: ts.OutputEvent) =>
              val msg = encodeMessage(oe)
              producer.publish(msg.topic, msg.payload),
            ).pure,
          )
      },
    )

    processing.run.apply(???) // .evalMap { case (inputEvent, publish) => publish.apply(inputEvent) }
  }
}
