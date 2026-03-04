package io.github.mercurievv.home_automation

import io.github.mercurievv.home_automation.instances.JsonInstances.given
import io.github.mercurievv.knn.has.Wiring
import io.github.mercurievv.knn.has.impl.TypeSystemImpl
import io.github.mercurievv.knn.has.mqtt.MessageCoders.*
import io.github.mercurievv.knn.has.mqtt.Mqtt
import net.sigusr.mqtt.api.QualityOfService.AtMostOnce

import java.util.concurrent.atomic.AtomicReference

import scala.compiletime.uninitialized
import scala.concurrent.duration.*

import cats.Applicative
import cats.data.Kleisli
import cats.implicits.*

import cats.effect.implicits.*
import cats.effect.kernel.{Async, Resource}
import cats.effect.std.{Console, MapRef}
import cats.effect.unsafe.IORuntime
import cats.effect.{FiberIO, IO}

import io.circe.*

import fs2.*

import net.sigusr.mqtt.api.Session
import org.pf4j.Plugin
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareLogger}

class HomeAutomationsPlugin extends Plugin {
  given SelfAwareLogger[IO] = Slf4jLogger.getLogger[IO]

  private var runtime: IORuntime = uninitialized

  private val fiberRef: AtomicReference[Option[FiberIO[Unit]]] =
    new AtomicReference[Option[FiberIO[Unit]]](None)

  def programmF[F[_]: {SelfAwareLogger, Async, Console, Applicative}]: F[Unit] = {
    val ts = new TypeSystemImpl[F]
    val pluginResources: Resource[F, (Mqtt.MqttSettings, Session[F])] = Mqtt
      .loadSettings[F]
      .toResource
      .mproduct(Mqtt.create[F])

    val retryPolicy: Stream[F, FiniteDuration] =
      Stream.iterate(10.seconds)(d => (d * 2).min(5.minutes))

    Logger[F].info("Starting app") <* MapRef.ofSingleImmutableMap[F, ts.EventId, ts.EventState]().flatMap { mapRef =>
      Stream
        .resource(pluginResources)
        // .evalTap { case (settings, _) => Logger[F].info(s"Started app. MQTT topic: ${settings.topic}") }
        .flatMap { case (settings, session) =>
          Stream.eval(session.subscribe(Vector(settings.topic -> AtMostOnce))) >>
            Wiring
              .wire[F]
              .apply(ts)(
                decodeMessage,
                encodeMessage,
                Kleisli { case (event, _) =>
                  Logger[F].info(s"Received: ${event._1} -> ${Json.fromJsonObject(event._2).noSpaces}").as(None)
                },
              )
              .apply(((ts, mapRef), session))
              .evalMap { case (inputEvent, process) => process.run(inputEvent) }
              .drain
//          Mqtt.logAllTopics(session) mergeHaltBoth mainStream
        }
        .attempts(retryPolicy)
        .evalMap {
          case Left(e)  => Logger[F].error(e)(s"Plugin failed, retrying: ${e.getMessage}")
          case Right(_) => Applicative[F].unit
        }
        .compile
        .drain
    }
  }

  override def start(): Unit = {
    runtime = IORuntime.builder().build()
    given IORuntime = runtime

    val newFiber = programmF[IO].start.unsafeRunSync()

    // If start() is called again, stop the previous fiber to avoid leaks
    val previous = fiberRef.getAndSet(Some(newFiber))
    previous.foreach { old =>
      (old.cancel *> old.join.void)
        .handleErrorWith(e => Logger[IO].error(e)(s"Previous run stop failed: $e"))
        .unsafeRunSync()
    }
  }

  override def stop(): Unit =
    fiberRef.getAndSet(None) match {
      case Some(fiber) =>
        given IORuntime = runtime
        (fiber.cancel *> fiber.join.void)
          .handleErrorWith(e => SelfAwareLogger[IO].error(e)(s"Stop failed: $e"))
          .unsafeRunSync()
        runtime.shutdown()
        runtime = null
        println("App stopped")

      case None =>
        System.err.println("WARN: stop called but plugin not running")
    }
}
