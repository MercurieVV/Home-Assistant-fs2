package io.github.mercurievv.home_automation

import io.github.mercurievv.home_automation.impl.TypesWiring
import io.github.mercurievv.home_automation.state.StateUpdate

import scala.language.experimental.pureFunctions

import cats.arrow.{Arrow, FunctionK}
import cats.data.Kleisli
import cats.implicits.*
import cats.kernel.Monoid
import cats.{Id, Monad, ~>}

import cats.effect.std.MapRef

import fs2.*

import net.sigusr.mqtt.api.{Message, Session}
import org.typelevel.log4cats.Logger

object Wiring extends BackwardAutoArrow[Kleisli[Id, _, _]] {

  type TypeSystemWithStates[F[_]] = TypeSystem {
    type States = MapRef[F, EventId, Option[EventState]]
  }

  def wire[F[_]: {Monad, Logger}](
    ts: TypeSystemWithStates[F],
  )(
    decodeMessage: Message => ts.InputEvent,
    encodeMessage: ts.OutputEvent => Message,
    decisionMaking: Kleisli[F, (ts.InputEvent, ts.States), Option[ts.OutputEvent]],
  )(using MES: Monoid[ts.EventState],
  ): Kleisli[
    Stream[F, _],
    ((ts.type, ts.States), Session[F]),
    (ts.InputEvent, Kleisli[F, ts.InputEvent, Unit]),
  ] = {
    type TS = ts.type
    val tw = new TypesWiring[F, ts.type](ts)
    import tw.*
    val epti = eventProcessingTypes
    val espti = eventStreamProcessingTypes

    type -->[A, B] = Kleisli[F, A, B]
    type S[b] = Stream[F, b]
    type ==>[A, B] = Kleisli[S, A, B]

    type StateUpdateTSI = StateUpdateTS[-->, ts.States]
    given Kleisli[Id, TS, StateUpdateTSI] = Kleisli[Id, TS, StateUpdateTSI]((ts: TS) =>
      StateUpdate.refMapStateUpdate[F, ts.InputEvent, ts.EventId, ts.EventState, ts.States](
        getEventId     = Arrow[-->].lift(_.eventId.value),
        getEntityState = Arrow[-->].lift(_.eventState.value),
      ),
    )

    type EP = EventProcessing[-->, EPTTS]
    given Kleisli[Id, (ts.States, StateUpdateTSI), EP] = Kleisli { case (mapRef, stateUpdate) =>
      import epti.*
      val value: InputEvent --> (ts.States, InputEvent) = Arrow[-->].lift(_ => mapRef) &&& Arrow[-->].id
      new EventProcessing[-->, EPTTS](
        t            = epti,
        updateState  = (value >>> stateUpdate.apply).as(mapRef),
        makeDecision = decisionMaking,
      )
    }

    type ESP = EventsStreamProcessing[==>, -->, ESPTTS, EPTTS, EP]
    given Kleisli[Id, EP, ESP] = Kleisli((epp: EP) =>
      new EventsStreamProcessing[==>, -->, ESPTTS, EPTTS, EP](espti, epp) {
        import espt.*

        override val consume: Consumer ==> ep.t.InputEvent = Kleisli((c: Consumer) => c.messages)
          .map(decodeMessage)
          .flatMapF(v => Stream.eval(Logger[F].info(s"Decoded message ${v.toString.take(500)}").as(v)))
        override val produce: Producer ==> (ep.t.OutputEvent --> Unit) =
          Kleisli(producer =>
            Kleisli((oe: ts.OutputEvent) =>
              val msg = encodeMessage(oe)
              producer.publish(msg.topic, msg.payload),
            ).pure,
          )
      },
    )

    given processStream: Kleisli[
      S,
      (ESP, (espti.Consumer, espti.Producer)),
      (epti.InputEvent, epti.InputEvent --> Unit),
    ] = Kleisli(_.run(_))

    given idToStream: Id ~> S = FunctionK.lift([A] => (a: A) => Stream.emit(a))

    summon[Kleisli[Id, ((TS, ts.States), espti.Consumer), (ESP, (espti.Consumer, espti.Producer))]]
      .mapK[S](idToStream) >>> processStream
  }
}

trait BackwardAutoArrow[G[_, _]: Arrow]:
  given idrrow: [A] => G[A, A] = Arrow[G].id

  given mergeArrow: [A, B, C] => (k1: G[A, B]) => (k2: G[A, C]) => G[A, (B, C)] = k1 &&& k2

  given flipTupleArrow: [A, B, C] => (k: G[(A, B), C]) => G[(B, A), C] = k.lmap { case (a, b) => (b, a) }

  given parallelArrow: [A, B, C, D] => (k1: G[A, B]) => (k2: G[C, D]) => G[(A, C), (B, D)] = k1 *** k2

  given composeArrow: [A, B, C] => (k2: G[B, C]) => (k1: G[A, B]) => G[A, C] = k1 >>> k2
