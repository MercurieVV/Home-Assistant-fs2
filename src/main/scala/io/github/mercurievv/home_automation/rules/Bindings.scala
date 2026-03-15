package io.github.mercurievv.home_automation.rules

import io.github.mercurievv.home_automation.rules.EventTypes.{EntityId, In, OnOffState, Out}

import cats.Functor
import cats.Monad
import cats.data.Kleisli
import cats.derived.semiauto
import cats.implicits.*

import io.circe.*
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.{Codec, JsonObject}

import language.experimental.pureFunctions

type Maybe[A] = Either[Unit, A]

object Devices:

  case class InputAction[Action](
    id: In[EntityId],
    action: In[JsonObject] => Action)
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

    def iao[T: Decoder]: InputAction[Option[T]] =
      InputAction[Option[T]](
        id     = In(EntityId("zigbee2mqtt/" + deviceName)),
        action = v => v.value.toJson.as[T].toOption, // Option.when(fold.headOption(v.value).contains(eventValue))(()),
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
case class SwitchAction(action: String)
given Codec[SwitchAction] = ConfiguredCodec.derived

object Bindings:

  def create[F[_]: Monad](bt: BindingsTooling[F]): Map[
    In[EntityId],
    (Out[EntityId], Kleisli[F, EntityId, JsonObject] => Kleisli[F, In[JsonObject], Maybe[Out[JsonObject]]]),
  ] = {
    import bt.*
    type -->[a, b] = Kleisli[F, a, b]

    Map(
      bindAction[-->, Unit, LightState](
        Zigbee2Mqtt.d("Bedroom switch").iao[SwitchAction].map(_.map(_.action == "single_left").ifM(Some(()), None)),
        Zigbee2Mqtt.d("bedroom_lights").oa[LightState],
        toggle,
      ),
    )
  }
