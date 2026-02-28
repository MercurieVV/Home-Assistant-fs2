package io.github.mercurievv.knn.has

import io.github.mercurievv.knn.has.impl.TypesWiring
import io.github.mercurievv.knn.has.state.StateUpdate

import cats.arrow.Arrow
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
    type TS = ts.type
    val tw = new TypesWiring[F, ts.type](ts)
    import tw.*
    val epti = eventProcessingTypes
    val espti = eventStreamProcessingTypes

    type --->[A, B] = Kleisli[F, A, B]
    type S[b] = Stream[F, b]
    type ===>[A, B] = Kleisli[S, A, B]

    given Kleisli[Id, Unit, EPTTS] = Kleisli.pure[Id, Unit, EPTTS](epti)
    given Kleisli[Id, Unit, ESPTTS] = Kleisli.pure[Id, Unit, ESPTTS](espti)
    given Kleisli[Id, Unit, TS] = Kleisli.pure[Id, Unit, TS](ts)

    given Kleisli[Id, Unit, ts.States] = ???

    type StateUpdateTSI = StateUpdateTS[--->, ts.States]
    given Kleisli[Id, TS, StateUpdateTSI] = Kleisli[Id, TS, StateUpdateTSI]((ts: TS) =>
      StateUpdate.refMapStateUpdate[F, ts.InputEvent, ts.EventId, ts.EventState, ts.States](
        getEventId     = Kleisli.fromFunction(_.eventId),
        getEntityState = Kleisli.fromFunction(_.eventState),
      ),
    )

    type EP = EventProcessing[--->, EPTTS]
    given Kleisli[Id, (ts.States, StateUpdateTSI), EP] = Kleisli { case (mapRef, stateUpdate) =>
      new EventProcessing[--->, EPTTS](epti) {

        import epti.*

        private val value: Kleisli[F, InputEvent, (ts.States, InputEvent)] =
          Kleisli.pure[F, InputEvent, ts.States](mapRef) &&& Arrow[--->].id
        override val updateState: InputEvent ---> States = (value >>> stateUpdate.apply).as(mapRef)
        override val makeDecision: (InputEvent, States) ---> Option[OutputEvent] = ???
      }
    }

    type ESP = EventsStreamProcessing[===>, --->, ESPTTS, EPTTS, EP]
    given Kleisli[Id, EP, ESP] = Kleisli((epp: EP) =>
      new EventsStreamProcessing[===>, --->, ESPTTS, EPTTS, EP](espti, epp) {
        import espt.*

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

    given mergeArrow: [G[_, _]: Arrow, A, B, C] => (k1: G[A, B]) => (k2: G[A, C]) => G[A, (B, C)] = k1 &&& k2

    given composeArrow: [G[_, _]: Arrow, A, B, C] => (k2: G[B, C]) => (k1: G[A, B]) => G[A, C] = k1 >>> k2

    val workStream: Stream[F, Unit] =
      summon[Kleisli[Id, Unit, ESP]].apply(()).run(???, ???).evalMap { case (event, processor) =>
        processor(event)
      }
  }
}
