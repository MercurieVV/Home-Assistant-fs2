package io.github.mercurievv.home_automation.rules

import io.github.mercurievv.home_automation.rules.EventTypes.{EntityId, In, OnOffState, Out}
import io.github.mercurievv.home_automation.rules.Devices.StudioLightSwitch_single_right_action

import cats.Monad
import cats.data.Kleisli

import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.optics.JsonPath.root
import io.circe.{Codec, Json, JsonObject}

import monocle.{Fold, Getter, Optional}

import language.experimental.pureFunctions

type Maybe[A] = Either[Unit, A]

object Devices:
  case class InputAction[Action](
    id: In[EntityId],
    action: In[JsonObject] => Option[Action])

  case class OutputAction[OutT](
    id: Out[EntityId],
    encoder: Encoder[OutT],
    decoder: Decoder[OutT])

  private val zigbee2mqttTopic = "zigbee2mqtt/"

  val StudioLightSwitch_single_right_action = InputAction[Unit](
    id     = In(EntityId(zigbee2mqttTopic + "Workroom switch")),
    action = v => Option.when(composed.headOption(v.value).contains("single_right"))(()),
  )

  val StudioLights = OutputAction[LightState](
    id      = Out(EntityId(zigbee2mqttTopic + "studio_lights/set")),
    decoder = summon,
    encoder = summon,
  )
  val jsonObject: Getter[JsonObject, Json] = Getter(Json.fromJsonObject)
  private val sla: Optional[Json, String] = root.action.string
  val composed: Fold[JsonObject, String] = Devices.jsonObject andThen sla

import io.github.mercurievv.monocle.circe.*

given Codec[OnOffState] = OnOffState.prism.toCodec
case class LightState(state: OnOffState)
given Codec[LightState] = deriveCodec

object Bindings:

  def create[F[_]: Monad](bt: BindingsTooling[F]) = {
    import bt.*
    type -->[a, b] = Kleisli[F, a, b]

    val studioLight
      : (In[EntityId], (Out[EntityId], (EntityId --> JsonObject) => (In[JsonObject]) --> Maybe[Out[JsonObject]])) =
      bindAction[-->, Unit, LightState](StudioLightSwitch_single_right_action, Devices.StudioLights, toggle)

    val map: Map[
      In[EntityId],
      (Out[EntityId], (EntityId --> JsonObject) => In[JsonObject] --> Maybe[Out[JsonObject]]),
    ] = Map(
      studioLight,
    )

    map
  }
