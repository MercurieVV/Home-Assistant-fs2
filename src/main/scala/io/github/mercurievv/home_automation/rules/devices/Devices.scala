package io.github.mercurievv.home_automation.rules.devices

import io.github.mercurievv.cats.arrow.kleisli.*
import io.github.mercurievv.home_automation.rules.EventTypes.{EntityId, In, Out, Topic}

import cats.data.Kleisli
import cats.derived.semiauto
import cats.implicits.catsSyntaxApplicativeId
import cats.{Applicative, Functor, ~>}

import io.circe.{Decoder, Encoder, JsonObject}

object Devices:

  case class InputAction[Action](
    id: In[Topic],
    action: Action)
  given Functor[InputAction] = semiauto.functor

  case class OutputAction[OutT](
    id: Out[Topic],
    encoder: Encoder[OutT],
    decoder: Decoder[OutT])

  case class DeviceBuilder(service: String, deviceName: String):

    def ia[F[_]: Applicative, S[_], T: Decoder](o2S: Option ~> S)
      : InputAction[Kleisli[[a] =>> F[S[a]], In[JsonObject], T]] =
      InputAction[Kleisli[[a] =>> F[S[a]], In[JsonObject], T]](
        id     = In(Topic(service, EntityId(deviceName), None)),
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
