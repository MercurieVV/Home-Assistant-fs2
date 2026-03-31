package io.github.mercurievv.home_automation.rules

import io.github.mercurievv.cats.arrow.kleisli.*
import io.github.mercurievv.home_automation.rules.Devices.InputAction
import io.github.mercurievv.home_automation.rules.EventTypes.*
import io.github.mercurievv.home_automation.rules.EventTypes.given

import cats.arrow.Arrow
import cats.data.Kleisli
import cats.derived.semiauto
import cats.implicits.*
import cats.{Applicative, Functor, Monad, MonoidK, ~>}

import io.circe.*
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.{Codec, JsonObject}

import language.experimental.pureFunctions

type Maybe[A] = List[A]

object Devices:

  case class InputAction[Action](
    id: In[Topic],
    action: Action)
  given Functor[InputAction] = semiauto.functor

  case class OutputAction[OutT](
    id: Out[Topic],
    encoder: Encoder[OutT],
    decoder: Decoder[OutT])

object Zigbee2Mqtt:

  import Devices.*

  def d(deviceName: String): DeviceBuilder = DeviceBuilder(deviceName)

  case class DeviceBuilder(deviceName: String):

    def ia[F[_]: Applicative, S[_], T: Decoder](o2S: Option ~> S)
      : InputAction[Kleisli[[a] =>> F[S[a]], In[JsonObject], T]] =
      InputAction[Kleisli[[a] =>> F[S[a]], In[JsonObject], T]](
        id     = In(Topic("zigbee2mqtt", EntityId(deviceName), None)),
        action = (
          (v: In[JsonObject]) => o2S(v.value.toJson.as[T].toOption).pure[F],
        ).k,
      )

    def oa[T: {Decoder, Encoder}]: OutputAction[T] =
      OutputAction[T](
        id      = Out(Topic("zigbee2mqtt", EntityId(deviceName), Some("set"))),
        decoder = summon,
        encoder = summon,
      )

import io.github.mercurievv.monocle.circe.*

given Configuration = Configuration.default.withDefaults

given Codec[OnOffState] = OnOffState.prism.toCodec

case class PowerState(
  state: OnOffState = OnOffState.Off,
  brightness: Int = 255)
given Codec[PowerState] = ConfiguredCodec.derived

given Toggleable[PowerState] = new Toggleable[PowerState]:
  extension (ls: PowerState)
    def toggle: PowerState = PowerState(
      state      = ls.state.toggle,
      brightness = 255,
    )

given Codec[Closeable] = Codec.from(
  Decoder.decodeString.emap(s => Closeable.values.find(_.toString == s).toRight(s"Unknown Closeable value: $s")),
  Encoder.encodeString.contramap(_.toString),
)

case class BlindsState(position: Int = 0)
given Codec[BlindsState] = ConfiguredCodec.derived

given Toggleable[BlindsState] = new Toggleable[BlindsState]:
  val doOpen = BlindsState(99)
  val doClose = BlindsState(0)
  extension (a: BlindsState)
    def toggle: BlindsState = a match {
      case BlindsState(position) => if position < 50 then doOpen else doClose
    }

case class TemperatureHumiditySensor(
  temperature: Float = -1000,
  humidity: Float = -1)
given Codec[TemperatureHumiditySensor] = ConfiguredCodec.derived

case class SwitchAction(action: String)
given Codec[SwitchAction] = ConfiguredCodec.derived

object Bindings:

  def create[F[_]: Applicative, S[_]: Applicative](
    bt: BindingsTooling[[a] =>> F[S[a]]],
    o2s: Option ~> S,
    l2s: List ~> S,
  )(using MFS: Monad[[a] =>> F[S[a]]],
    MSU: MonoidK[S],
  ): Map[
    In[Topic],
    S[
      (
        Out[Topic],
        Kleisli[[a] =>> F[S[a]], Topic, JsonObject] => Kleisli[[a] =>> F[S[a]], In[JsonObject], Out[JsonObject]],
      ),
    ],
  ] = {
    import bt.*
    type -->[a, b] = Kleisli[[x] =>> F[S[x]], a, b]

    extension [A](sa: InputAction[In[JsonObject] --> A])
      def toSU(f: A => Boolean): InputAction[In[JsonObject] --> Unit] = sa.map(
        _.map(f)
          .ifM(().pure[S].pure[F].k, MSU.empty.pure[F].k),
      )

    List(
      bindStatefulAction[-->, Unit, PowerState](
        Zigbee2Mqtt.d("Bedroom switch").ia[F, S, SwitchAction](o2s).toSU(_.action == "single_left"),
        Zigbee2Mqtt.d("bedroom_lights").oa[PowerState],
        toggle,
      ),
      bindStatefulAction[-->, Unit, PowerState](
        Zigbee2Mqtt.d("Bedroom switch").ia[F, S, SwitchAction](o2s).toSU(_.action == "double_left"),
        Zigbee2Mqtt.d("bedroom_lights").oa[PowerState],
        Out(PowerState(state = OnOffState.On, brightness = 1)).pure,
      ),
      bindStatefulAction[-->, Unit, BlindsState](
        Zigbee2Mqtt.d("Bedroom switch").ia[F, S, SwitchAction](o2s).toSU(_.action == "single_right"),
        Zigbee2Mqtt.d("Bedroom blinds").oa[BlindsState],
        toggle,
      ),
      bindStatefulAction[-->, Unit, PowerState](
        Zigbee2Mqtt.d("Kids room switch").ia[F, S, SwitchAction](o2s).toSU(_.action == "single_left"),
        Zigbee2Mqtt.d("kids_room_lights").oa[PowerState],
        toggle,
      ),
      bindStatefulAction[-->, Unit, BlindsState](
        Zigbee2Mqtt.d("Kids room switch").ia[F, S, SwitchAction](o2s).toSU(_.action == "single_right"),
        Zigbee2Mqtt.d("Kids room blinds").oa[BlindsState],
        toggle,
      ),
      bindStatefulAction[-->, Float, PowerState](
        Zigbee2Mqtt.d("Bathroom TH sensor").ia[F, S, TemperatureHumiditySensor](o2s).map(_.map(_.humidity)),
        Zigbee2Mqtt.d("Bathroom fan plug").oa,
        Arrow[-->].lift { case (_, h) =>
          if h > 70 then Out(PowerState(state = OnOffState.On)) else Out(PowerState(state = OnOffState.Off))
        },
      ),
    ).groupMap(_._1)(_._2).view.mapValues(l2s.apply).toMap
  }
