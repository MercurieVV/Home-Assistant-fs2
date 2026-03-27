package io.github.mercurievv.home_automation.mqtt

import io.github.mercurievv.home_automation.AppConfig.MqttSettings

import scala.concurrent.duration.{FiniteDuration, SECONDS}

import cats.effect.Resource
import cats.effect.kernel.Async
import cats.effect.std.Console

import fs2.Stream

import net.sigusr.mqtt.api.QualityOfService.AtMostOnce
import net.sigusr.mqtt.api.RetryConfig.Custom
import net.sigusr.mqtt.api.{Session, SessionConfig, TransportConfig}
import org.typelevel.log4cats.Logger
import retry.RetryPolicies

object Mqtt {

  def create[F[_]: {Async, Console}](s: MqttSettings): Resource[F, Session[F]] = {
    val retryConfig: Custom[F] = Custom[F](
      RetryPolicies
        .limitRetries[F](5)
        .join(RetryPolicies.fullJitter[F](FiniteDuration(2, SECONDS))),
    )
    val transportConfig =
      TransportConfig[F](
        s.host,
        s.port,
        retryConfig   = retryConfig,
        traceMessages = false,
      )
    val sessionConfig =
      SessionConfig(
        s.clientId,
        cleanSession = s.cleanSession,
        user         = s.user,
        password     = s.password,
        keepAlive    = s.keepAliveSeconds,
      )
    Session[F](transportConfig, sessionConfig)
  }

  /** Subscribe to the given topic and return the message stream. */
  def subscribedMessages[F[_]: Async](
    session: Session[F],
    topic: String,
  ): Stream[F, net.sigusr.mqtt.api.Message] =
    Stream.eval(session.subscribe(Vector(topic -> AtMostOnce))) >> session.messages

  /** Subscribe to `#` and log every distinct topic seen on the broker. */
  def logAllTopics[F[_]: {Async, Logger}](session: Session[F]): Stream[F, Nothing] =
    Stream.eval(session.subscribe(Vector("#" -> AtMostOnce))) >>
      session.messages
        .map(_.topic)
        .changes
        .evalMap(t => Logger[F].info(s"MQTT topic: $t"))
        .drain
}
