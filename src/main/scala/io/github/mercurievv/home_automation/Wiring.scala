package io.github.mercurievv.home_automation

import io.github.mercurievv.cats.arrow.kleisli.*
import io.github.mercurievv.home_automation.TypeSystem.StatesT
import io.github.mercurievv.home_automation.impl.TypesWiring
import io.github.mercurievv.home_automation.state.DeviceStateAcessor
import io.github.mercurievv.home_automation.state.StateServer
import io.github.mercurievv.home_automation.state.StateUpdate

import scala.language.experimental.pureFunctions

import cats.arrow.{Arrow, FunctionK}
import cats.data.Kleisli
import cats.implicits.*
import cats.kernel.{Monoid, Semigroup}
import cats.{Applicative, Id, Monad, MonadThrow, ~>}

import cats.effect.kernel.{Async, Ref, Resource}

import io.circe.Encoder
import io.circe.syntax.*

import fs2.*

import fetch.DataSource
import net.sigusr.mqtt.api.{Message, Session}
import org.typelevel.log4cats.{SelfAwareStructuredLogger, StructuredLogger}

object Wiring extends BackwardAutoArrow[Kleisli[Id, _, _]] {

  type TypeSystemWithStates[F[_]] = TypeSystem {
    type States = StatesT[F, EventId, EventState]
  }

  def wireRessources[F[_]: {SelfAwareStructuredLogger, Async}, K, V: {Semigroup, Encoder}](
    using i2f: Id ~> F,
  ): Kleisli[Resource[F, *], DataSource[F, K, V], (StatesT[F, K, V], Unit)] =
    type RF[a] = Resource[F, a]
    type MAPKV = Map[K, V]
    type -->[a, b] = Kleisli[F, a, b]
    import cats.effect.implicits.*

    object ResourceInit extends BackwardAutoArrow[-->] {
      given f2g: [F[_], G[_], a, b] => (
        k: Kleisli[F, a, b],
        f2g: F ~> G,
      ) => Kleisli[G, a, b] = k.mapK(f2g)

      given f2r: F ~> RF = new (F ~> RF) {
        def apply[A](fa: F[A]) = fa.toResource
      }

      val apply =
        given map: -->[DataSource[F, K, V], Ref[F, MAPKV]] = Ref.of[F, MAPKV](Map.empty).k
        val stateServer = StateServer.createStateServer[F, K, V].lmap[Ref[F, MAPKV]](_.get)

        //        summon[DataSource[F, K, V] --> (StatesT[F, K, V], Ref[F, MAPKV])].mapK(f2r) >>> stateServer
        ((Arrow[-->].id[DataSource[F, K, V]] &&& map) >>> (DeviceStateAcessor
          .createStates[F, K, V]
          .mapK(i2f) &&& Arrow[-->].id[(DataSource[F, K, V], Ref[F, Map[K, V]])].map(_._2))).mapK(f2r) >>> stateServer
          .second[StatesT[F, K, V]]
    }
    ResourceInit.apply

  def wire[F[_]: {MonadThrow, SelfAwareStructuredLogger}, SQ[_]: Applicative](
    ts: TypeSystemWithStates[F],
  )(
    decodeMessage: Message => ts.InputEvent,
    encodeMessage: ts.OutputEvent => Message,
    decisionMaking: Kleisli[[a] =>> F[SQ[a]], (ts.InputEvent, ts.States), ts.OutputEvent],
    addLogContext: ts.EventId => Map[String, String],
  )(using MES: Monoid[ts.EventState],
    MFS: Monad[[a] =>> F[SQ[a]]],
    ESE: _root_.io.circe.Encoder[ts.EventState],
  ): Kleisli[
    Stream[F, _],
    ((ts.type, ts.States), Session[F]),
    (ts.InputEvent, Kleisli[[a] =>> F[SQ[a]], ts.InputEvent, Unit]),
  ] = {
    type FS[a] = F[SQ[a]]
    type TS = ts.type
    val tw = new TypesWiring[F, ts.type](ts)
    import tw.*
    val epti = eventProcessingTypes
    val espti = eventStreamProcessingTypes

    type -->[A, B] = Kleisli[FS, A, B]
    type S[b] = Stream[F, b]
    type ==>[A, B] = Kleisli[S, A, B]

    type StateUpdateTSI = StateUpdateTS[Kleisli[F, *, *], ts.States]
    given Kleisli[Id, TS, StateUpdateTSI] = Kleisli[Id, TS, StateUpdateTSI]((ts: TS) =>
      StateUpdate.refMapStateUpdate[F, ts.InputEvent, ts.EventId, ts.EventState, ts.States](
        getEventId     = Arrow[Kleisli[F, *, *]].lift(_.eventId.value),
        getEntityState = Arrow[Kleisli[F, *, *]].lift(_.eventState.value),
      ),
    )

    type EP = EventProcessing[-->, EPTTS]
    given Kleisli[Id, (ts.States, StateUpdateTSI), EP] = Kleisli { case (mapRef, stateUpdate) =>
      import epti.*
      type ~~>[a, b] = Kleisli[F, a, b]
      val inputEventWithStates: InputEvent ~~> (ts.States, InputEvent) = Arrow[~~>].lift(_ => mapRef) &&& Arrow[~~>].id
      val f2fs: F ~> FS = new (F ~> FS) { def apply[A](fa: F[A]) = fa.map(_.pure) }
      new EventProcessing[-->, EPTTS](
        t            = epti,
        updateState  = (inputEventWithStates >>> stateUpdate.apply).as(mapRef).mapK(f2fs),
        makeDecision = decisionMaking,
      )
    }

    type ESP = EventsStreamProcessing[==>, -->, ESPTTS, EPTTS, EP]
    given Kleisli[Id, EP, ESP] = Kleisli((epp: EP) =>
      new EventsStreamProcessing[==>, -->, ESPTTS, EPTTS, EP](espti, epp) {
        import espt.*

        override val consume: Consumer ==> ep.t.InputEvent = Kleisli((c: Consumer) => c.messages)
          .map(decodeMessage)
          .flatMapF { v =>
            val messageJson = v.value._2.asJson.noSpaces
            Stream.eval(
              StructuredLogger[F]
                .addContext(addLogContext(v.value._1) ++ Map("event" -> "consuming_message"))
                .info(Map("payload" -> messageJson))(
                  s"Consuming message. source: \"${v.value._1}\" message: ${messageJson.take(500)}",
                )
                .as(v),
            )
          }
        override val produce: Producer ==> (ep.t.OutputEvent --> Unit) =
          Kleisli(producer =>
            Kleisli[FS, ts.OutputEvent, Unit]((oe: ts.OutputEvent) =>
              val msg = encodeMessage(oe)
              val messageJson = oe.value._2.asJson.noSpaces
              StructuredLogger[F]
                .addContext(addLogContext(oe.value._1) ++ Map("event" -> "producing_message"))
                .info(Map("payload" -> messageJson))(
                  s"Producing message. source: \"${oe.value._1}\" message: ${messageJson.take(500)}",
                ) *> producer.publish(msg.topic, msg.payload).map(_.pure[SQ]),
            ).pure[S],
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
