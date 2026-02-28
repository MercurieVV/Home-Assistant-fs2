package io.github.mercurievv.knn.has.mqtt

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.concurrent.duration.{FiniteDuration, SECONDS}
import scala.jdk.CollectionConverters.*

import cats.effect.kernel.Async
import cats.effect.std.Console
import cats.effect.{Resource, Temporal}
import cats.syntax.all.*

import fs2.Stream

import com.comcast.ip4s.{Host, Port}
import net.sigusr.mqtt.api.QualityOfService.*
import net.sigusr.mqtt.api.RetryConfig.Custom
import net.sigusr.mqtt.api.{Session, SessionConfig, TransportConfig}
import net.sigusr.mqtt.examples.localSubscriber
import retry.RetryPolicies

object Mqtt {

  final case class MqttSettings(
    host: Host,
    port: Port,
    clientId: String,
    cleanSession: Boolean,
    user: Option[String],
    password: Option[String],
    keepAliveSeconds: Int,
    topic: String)

  private def parseBoolean(value: String): Boolean =
    val v = value.trim
    v.equalsIgnoreCase("true") || v == "1" || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("y")

  private def stripQuotes(value: String): String =
    val v = value.trim
    if v.length >= 2 &&
      ((v.startsWith("\"")
        && v.endsWith("\""))
        || (v.startsWith("'") && v.endsWith("'")))
    then v.substring(1, v.length - 1)
    else v

  /** Minimal .env parser.
    *   - Supports `KEY=VALUE` lines
    *   - Ignores empty lines and comments starting with `#`
    *   - Trims whitespace around KEY and VALUE
    *   - Supports wrapping VALUE in single or double quotes
    *   - Does not implement variable interpolation or export statements
    */
  private def parseDotEnv(content: String): Map[String, String] =
    content.linesIterator
      .map(_.trim)
      .filter(line => line.nonEmpty && !line.startsWith("#"))
      .flatMap { line =>
        val idx = line.indexOf('=')
        if idx <= 0 then None
        else
          val key = line.substring(0, idx).trim
          val raw = line.substring(idx + 1).trim
          if key.isEmpty then None
          else Some(key -> stripQuotes(raw))
      }
      .toMap

  private def loadDotEnvFile[F[_]: Async](path: Path): F[Map[String, String]] = {
    println(s"Loading .env file from: $path")
    Async[F].blocking {
      println("blocked for file read")
      if Files.exists(path) && Files.isRegularFile(path) then
        println(s".env file found at: $path")
        val content = Files.readString(path, StandardCharsets.UTF_8)
        println(s".env file content:\n$content")
        parseDotEnv(content)
      else Map.empty[String, String]
    }
  }

  private def envOrPropOrDotEnv(
    dotEnv: Map[String, String],
    name: String,
  ): Option[String] =
    sys.env.get(name).orElse(sys.props.get(name)).orElse(dotEnv.get(name))

  private def required(
    dotEnv: Map[String, String],
    name: String,
  ): Either[Throwable, String] =
    envOrPropOrDotEnv(dotEnv, name)
      .map(_.trim)
      .filter(_.nonEmpty)
      .toRight(
        new IllegalStateException(
          s"Missing required MQTT setting '$name'. Provide it via environment variable, JVM property (-D$name=...), or .env file.",
        ),
      )

  private def optional(
    dotEnv: Map[String, String],
    name: String,
  ): Option[String] =
    envOrPropOrDotEnv(dotEnv, name).map(_.trim).filter(_.nonEmpty)

  private def optionalInt(
    dotEnv: Map[String, String],
    name: String,
  ): Option[Int] =
    optional(dotEnv, name).flatMap(_.toIntOption)

  private def parseHost(value: String): Either[Throwable, Host] =
    Host
      .fromString(value)
      .toRight(new IllegalArgumentException(s"Invalid MQTT_HOST: '$value'"))

  private def parsePort(value: String): Either[Throwable, Port] =
    Port
      .fromString(value)
      .toRight(new IllegalArgumentException(s"Invalid MQTT_PORT: '$value'"))

  /** Configuration sources (highest priority first): 1) Environment variables 2) JVM system properties
    * (-DMQTT_HOST=...) 3) .env file (default: `./.env`, overridable via `MQTT_DOTENV_PATH`)
    *
    * Supported keys: MQTT_HOST (default: core-mosquitto) MQTT_PORT (default: 1883) MQTT_CLIENT_ID (default: addon)
    * MQTT_CLEAN_SESSION (default: false) MQTT_USER (optional) MQTT_PASSWORD (optional; if MQTT_USER is set, password
    * must be set too) MQTT_KEEP_ALIVE_SECONDS (default: 5) MQTT_TOPIC (required) MQTT_DOTENV_PATH (optional; path to
    * .env file)
    */
  def loadSettings[F[_]: Async]: F[MqttSettings] =
    val dotEnvPathStr = "/data/plugins/ha-java.env".some
    // fixme    val dotEnvPathStr = sys.env.get("DOTENV_PATH").orElse(sys.props.get("DOTENV_PATH")).map(_.trim).filter(_.nonEmpty)
    val dotEnvPath =
      dotEnvPathStr.map(Paths.get(_)).getOrElse(Paths.get(".env"))

    for
      dotEnv <- loadDotEnvFile[F](dotEnvPath)
      hostStr = optional(dotEnv, "MQTT_HOST").getOrElse("core-mosquitto")
      portStr = optional(dotEnv, "MQTT_PORT").getOrElse("1883")
      clientId = optional(dotEnv, "MQTT_CLIENT_ID").getOrElse("addon")
      cleanSession = optional(dotEnv, "MQTT_CLEAN_SESSION").exists(parseBoolean)
      user = optional(dotEnv, "MQTT_USER")
      password = optional(dotEnv, "MQTT_PASSWORD")
      topic = optional(dotEnv, "MQTT_TOPIC")
      keepAlive = optionalInt(dotEnv, "MQTT_KEEP_ALIVE_SECONDS").getOrElse(5)

      validated: Either[Throwable, MqttSettings] =
        for
          host  <- parseHost(hostStr)
          port  <- parsePort(portStr)
          topic <- topic.toRight(
            new IllegalStateException(
              "Missing required MQTT setting 'MQTT_TOPIC'. Provide it via environment variable, JVM property (-DMQTT_TOPIC=...), or .env file.",
            ),
          )
          _ <-
            if user.isDefined && password.isEmpty then
              Left(
                new IllegalStateException(
                  "MQTT_USER is set but MQTT_PASSWORD is missing",
                ),
              )
            else Right(())
        yield MqttSettings(
          host             = host,
          port             = port,
          clientId         = clientId,
          cleanSession     = cleanSession,
          user             = user,
          password         = password,
          keepAliveSeconds = keepAlive,
          topic            = topic,
        )

      settings <- Async[F].fromEither(validated)
    yield settings

  def create[F[_]: {Async, Console}](s: MqttSettings): Resource[F, Session[F]] =
    val retryConfig: Custom[F] = Custom[F](
      RetryPolicies
        .limitRetries[F](5)
        .join(RetryPolicies.fullJitter[F](FiniteDuration(2, SECONDS))),
    )
    {
      val transportConfig =
        TransportConfig[F](
          s.host,
          s.port,
          // TLS support looks like
          // 8883,
          // tlsConfig = Some(TLSConfig(TLSContextKind.System)),
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
}
