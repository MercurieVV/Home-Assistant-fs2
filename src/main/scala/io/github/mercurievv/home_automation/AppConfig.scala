package io.github.mercurievv.home_automation

import java.nio.file.{Path, Paths}

import cats.syntax.all.*

import cats.effect.kernel.Async

import com.comcast.ip4s.{Host, Port}
import pureconfig.*
import pureconfig.error.CannotConvert
import pureconfig.generic.derivation.default.*

object AppConfig {

  val configPath: Path =
    Paths.get(Option.apply(System.getenv(s"PLUGINS_DIR") + "/ha-java.conf").getOrElse("./local.conf"))

  // Custom readers defined before the case classes that use them
  private given ConfigReader[Host] = ConfigReader[String].emap(s =>
    Host.fromString(s).toRight(CannotConvert(s, "Host", "invalid hostname or IP address")),
  )

  private given ConfigReader[Port] = ConfigReader[Int].emap(n =>
    Port.fromInt(n).toRight(CannotConvert(n.toString, "Port", "must be between 1 and 65535")),
  )

  final case class MqttSettings(
    host: Host,
    port: Port,
    clientId: String,
    cleanSession: Boolean,
    user: Option[String],
    password: Option[String],
    keepAliveSeconds: Int,
    topic: String)
      derives ConfigReader

  final case class OtelConfig(
    endpoint: Option[String],
    headers: Option[String],
    protocol: Option[String])
      derives ConfigReader

  final case class AppConfig(
    mqtt: MqttSettings,
    otel: OtelConfig)
      derives ConfigReader {

    def otelProps: Map[String, String] =
      Map("otel.service.name" -> "ha-automations") ++
        otel.endpoint.fold(Map("otel.traces.exporter" -> "none")) { ep =>
          Map("otel.traces.exporter" -> "otlp", "otel.exporter.otlp.endpoint" -> ep) ++
            otel.headers.map("otel.exporter.otlp.headers" -> _).toMap ++
            otel.protocol.map("otel.exporter.otlp.protocol" -> _).toMap
        }
  }

  def load[F[_]: Async]: F[AppConfig] =
    Async[F]
      .blocking(ConfigSource.file(configPath.toFile).loadOrThrow[AppConfig])
      .flatMap { config =>
        if config.mqtt.user.isDefined && config.mqtt.password.isEmpty then
          Async[F].raiseError(new IllegalStateException("mqtt.user is set but mqtt.password is missing"))
        else config.pure[F]
      }
}
