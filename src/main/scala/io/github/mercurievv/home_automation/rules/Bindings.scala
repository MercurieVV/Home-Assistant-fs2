package io.github.mercurievv.home_automation.rules

import io.github.mercurievv.cats.arrow.kleisli.*
import io.github.mercurievv.home_automation.rules.EventTypes.{Closeable, EntityId, In, OnOffState, Out, Toggleable}

import cats.data.Kleisli
import cats.derived.semiauto
import cats.implicits.*
import cats.{Applicative, Functor, Monad, MonoidK, ~>}

import io.circe.*
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.{Codec, JsonObject}

import monocle.macros.GenLens

import language.experimental.pureFunctions

type Maybe[A] = List[A]

object Devices:

  case class InputAction[Action](
    id: In[EntityId],
    action: Action)
  given Functor[InputAction] = semiauto.functor

  case class OutputAction[OutT](
    id: Out[EntityId],
    encoder: Encoder[OutT],
    decoder: Decoder[OutT])

  val zigbee2mqttTopic = "zigbee2mqtt/"

object Zigbee2Mqtt:

  import Devices.*

  def d(deviceName: String): DeviceBuilder = DeviceBuilder(deviceName)

  case class DeviceBuilder(deviceName: String):

    def iao[F[_]: Applicative, S[_], T: Decoder](o2S: Option ~> S)(using Monad[[a] =>> F[S[a]]])
      : InputAction[Kleisli[[a] =>> F[S[a]], In[JsonObject], T]] =
      InputAction[Kleisli[[a] =>> F[S[a]], In[JsonObject], T]](
        id     = In(EntityId("zigbee2mqtt/" + deviceName)),
        action = (
          (v: In[JsonObject]) => o2S(v.value.toJson.as[T].toOption).pure[F],
        ).k, // Option.when(fold.headOption(v.value).contains(eventValue))(()),
      )

    def oa[T: {Decoder, Encoder}]: OutputAction[T] =
      OutputAction[T](
        id      = Out(EntityId("zigbee2mqtt/" + deviceName + "/set")),
        decoder = summon,
        encoder = summon,
      )

import io.github.mercurievv.monocle.circe.*

given Configuration = Configuration.default.withDefaults

given Codec[OnOffState] = OnOffState.prism.toCodec

case class LightState(state: OnOffState = OnOffState.Off)
given Codec[LightState] = ConfiguredCodec.derived
given Toggleable[LightState] = Toggleable.fromLens(GenLens[LightState](_.state))

given Codec[Closeable] = Codec.from(
  Decoder.decodeString.emap(s => Closeable.values.find(_.toString == s).toRight(s"Unknown Closeable value: $s")),
  Encoder.encodeString.contramap(_.toString),
)

case class BlindsState(
  state: Closeable = Closeable.STOP,
  position: Int = 99)
given Codec[BlindsState] = ConfiguredCodec.derived

given Toggleable[BlindsState] = new Toggleable[BlindsState]:
  val doOpen = BlindsState(Closeable.OPEN, 99)
  val doClose = BlindsState(Closeable.CLOSE, 0)
  extension (a: BlindsState)
    def toggle: BlindsState = a match {
      case BlindsState(Closeable.STOP, position) =>
        if position < 50 then doOpen else doClose
      case BlindsState(Closeable.OPEN, _)  => doClose
      case BlindsState(Closeable.CLOSE, _) => doOpen
    }

case class SwitchAction(action: String)
given Codec[SwitchAction] = ConfiguredCodec.derived

object Bindings:

  def create[F[_]: Applicative, S[_]: Applicative](
    bt: BindingsTooling[[a] =>> F[S[a]]],
    o2s: Option ~> S,
  )(using MFS: Monad[[a] =>> F[S[a]]],
    MSU: MonoidK[S],
  ): Map[
    In[EntityId],
    List[
      (
        Out[EntityId],
        Kleisli[[a] =>> F[S[a]], EntityId, JsonObject] => Kleisli[[a] =>> F[S[a]], In[JsonObject], Out[JsonObject]],
      ),
    ],
  ] = {
    import bt.*
    type -->[a, b] = Kleisli[[x] =>> F[S[x]], a, b]

    List(
      bindStatefulAction[-->, Unit, LightState](
        Zigbee2Mqtt
          .d("Bedroom switch")
          .iao[F, S, SwitchAction](o2s)
          .map(
            _.map(_.action == "single_left")
              .ifM(().pure[S].pure[F].k, MSU.empty.pure[F].k),
          ),
        Zigbee2Mqtt.d("bedroom_lights").oa[LightState],
        toggle,
      ),
      bindStatefulAction[-->, Unit, BlindsState](
        Zigbee2Mqtt
          .d("Bedroom switch")
          .iao[F, S, SwitchAction](o2s)
          .map(
            _.map(_.action == "single_right").flatMapF(v =>
              if v then ().pure[S].pure[F]
              else MSU.empty.pure[F],
            ),
          ),
        Zigbee2Mqtt.d("Bedroom blinds").oa[BlindsState],
        toggle,
      ),
      bindStatefulAction[-->, Unit, LightState](
        Zigbee2Mqtt
          .d("Kids room switch")
          .iao[F, S, SwitchAction](o2s)
          .map(
            _.map(_.action == "single_left").flatMapF(v =>
              if v then ().pure[S].pure[F]
              else MSU.empty.pure[F],
            ),
          ),
        Zigbee2Mqtt.d("kids_room_lights").oa[LightState],
        toggle,
      ),
      bindStatefulAction[-->, Unit, BlindsState](
        Zigbee2Mqtt
          .d("Kids room switch")
          .iao[F, S, SwitchAction](o2s)
          .map(
            _.map(_.action == "single_right").flatMapF(v =>
              if v then ().pure[S].pure[F]
              else MSU.empty.pure[F],
            ),
          ),
        Zigbee2Mqtt.d("Kids room blinds").oa[BlindsState],
        toggle,
      ),
    ).groupMap(_._1)(_._2)
  }
