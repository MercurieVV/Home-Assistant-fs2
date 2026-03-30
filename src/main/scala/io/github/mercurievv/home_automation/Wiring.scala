package io.github.mercurievv.home_automation

import io.github.mercurievv.cats.arrow.kleisli.*
import io.github.mercurievv.home_automation.TypeSystem.StatesT
import io.github.mercurievv.home_automation.impl.TypesWiring
import io.github.mercurievv.home_automation.state.DeviceStateAcessor
import io.github.mercurievv.home_automation.state.KVStore
import io.github.mercurievv.home_automation.state.StateServer
import io.github.mercurievv.home_automation.state.StateUpdate

import scala.language.experimental.pureFunctions

import cats.arrow.{Arrow, FunctionK}
import cats.data.Kleisli
import cats.implicits.*
import cats.kernel.{Monoid, Semigroup}
import cats.{Applicative, Id, Monad, MonadThrow, ~>}

import cats.effect.kernel.{Async, Ref, Resource}
import cats.effect.std.MapRef

import io.circe.Encoder
import io.circe.syntax.*

import fs2.*

import doobie.util.meta.Meta
import fetch.DataSource
import net.sigusr.mqtt.api.{Message, Session}
import org.typelevel.log4cats.{SelfAwareStructuredLogger, StructuredLogger}

object Wiring extends BackwardAutoArrow[Kleisli[Id, _, _]] {

  type TypeSystemWithStates[F[_]] = TypeSystem {
    type States = StatesT[F, EventId, EventState]
  }

  def wireRessources[F[_]: {SelfAwareStructuredLogger, Async}, K: Meta, V: {Semigroup, Encoder, Meta}](
    using i2f: Id ~> F,
  ): Kleisli[Resource[F, *], (Map[K, V], (String, DataSource[F, K, V])), StatesT[F, K, V]] =
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
        def apply[A](fa: F[A]): Resource[F, A] = fa.toResource
      }

      val apply: Kleisli[RF, (MAPKV, (String, DataSource[F, K, V])), StatesT[F, K, V]] =
        given map: ~~>[MAPKV, Ref[F, MAPKV]] = (m => Ref.of[F, MAPKV](m)).k.mapK(f2r)
        val mapRef: Ref[F, MAPKV] ~~> MapRef[F, K, Option[V]] = Kleisli.fromFunction(MapRef.fromSingleImmutableMapRef)
        val stateServer: Kleisli[RF, Ref[F, MAPKV], Unit] =
          StateServer.createStateServer[F, K, V].lmap[Ref[F, MAPKV]](_.get)
        type ~~>[a, b] = Kleisli[RF, a, b]
        import _root_.io.github.mercurievv.minuscles.tuples.transformers.all.*
        ((map >>> (
          stateServer
            &&&
              (Arrow[~~>].id[Ref[F, MAPKV]] >>> mapRef)
        )
          .map(_._2)) *** KVStore.create[F, K, V].first[DataSource[F, K, V]])
          .map(_.toFlatten) >>> DeviceStateAcessor.createStates[F, K, V].mapK(i2f).mapK(f2r)
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
      val f2fs: F ~> FS = new (F ~> FS) { def apply[A](fa: F[A]): F[SQ[A]] = fa.map(_.pure) }
      new EventProcessing[-->, EPTTS](
        t            = epti,
        updateState  = (inputEventWithStates >>> stateUpdate.apply).as(mapRef).mapK(f2fs),
        makeDecision = decisionMaking,
      ) {
        override def run: InputEvent --> OutputEvent =
          Kleisli[FS, InputEvent, InputEvent] { (inputEvent: InputEvent) =>
            val messageJson = inputEvent.value._2.asJson.noSpaces
            StructuredLogger[F]
              .addContext(addLogContext(inputEvent.value._1) ++ Map("event" -> "consuming_message"))
              .info(Map("payload" -> messageJson))(
                s"Consuming message. source: \"${inputEvent.value._1}\" message: ${messageJson.take(500)}",
              )
              .as(inputEvent.pure[SQ])
          } >>> super.run >>> { (oe: OutputEvent) =>
            val messageJson = oe.value._2.asJson.noSpaces
            StructuredLogger[F]
              .addContext(addLogContext(oe.value._1) ++ Map("event" -> "producing_message"))
              .info(Map("payload" -> messageJson))(
                s"Producing message. source: \"${oe.value._1}\" message: ${messageJson.take(500)}",
              )
              .as(oe.pure[SQ])
          }.k
      }
    }

    type ESP = EventsStreamProcessing[==>, -->, ESPTTS, EPTTS, EP]
    given Kleisli[Id, EP, ESP] = Kleisli((epp: EP) =>
      new EventsStreamProcessing[==>, -->, ESPTTS, EPTTS, EP](espti, epp) {
        import espt.*

        override val consume: Consumer ==> ep.t.InputEvent = Kleisli((c: Consumer) => c.messages)
          .map(decodeMessage)

        override val produce: Producer ==> (ep.t.OutputEvent --> Unit) =
          Kleisli(producer =>
            Kleisli[FS, ts.OutputEvent, Unit]((oe: ts.OutputEvent) =>
              val msg = encodeMessage(oe)
              producer.publish(msg.topic, msg.payload).map(_.pure[SQ]),
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
