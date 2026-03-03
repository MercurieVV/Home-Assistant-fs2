package io.github.mercurievv.home_automation

import io.github.mercurievv.home_automation.instances.JsonInstances.given
import io.github.mercurievv.knn.has.Wiring
import io.github.mercurievv.knn.has.impl.TypeSystemImpl
import io.github.mercurievv.knn.has.mqtt.MessageCoders.*
import io.github.mercurievv.knn.has.mqtt.Mqtt

import java.util.concurrent.atomic.AtomicReference

import cats.Applicative
import cats.data.Kleisli
import cats.implicits.*

import cats.effect.implicits.*
import cats.effect.kernel.{Async, Resource}
import cats.effect.std.{Console, MapRef}
import cats.effect.unsafe.IORuntime
import cats.effect.{FiberIO, IO}

import net.sigusr.mqtt.api.Session
import org.pf4j.Plugin
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareLogger}

class HomeAutomationsPlugin extends Plugin {
  given SelfAwareLogger[IO] = Slf4jLogger.getLogger[IO]

  given IORuntime = IORuntime.global

  private val fiberRef: AtomicReference[Option[FiberIO[Unit]]] =
    new AtomicReference[Option[FiberIO[Unit]]](None)

  def programmF[F[_]: {SelfAwareLogger, Async, Console, Applicative}]: F[Unit] = {
    val ts = new TypeSystemImpl[F]
    val pluginResources: Resource[F, (Mqtt.MqttSettings, Session[F])] = Mqtt
      .loadSettings[F]
      .toResource
      .mproduct(Mqtt.create[F])

    (pluginResources, MapRef.ofSingleImmutableMap[F, ts.EventId, ts.EventState]().toResource).tupled
      .use { case ((settings, session), mapRef) =>
        Wiring
          .wire[F]
          .apply(ts)(decodeMessage, encodeMessage, Kleisli.pure(None))
          .apply(((ts, mapRef), session))
          .handleErrorWith(e =>
            fs2.Stream.exec(Logger[F].error(e)(s"Stream element failed: ${e.getMessage}"))
          )
          .repeat
          .compile
          .drain
      }
      .handleErrorWith(e => Logger[F].error(e)(s"Plugin program failed: ${e.getMessage}"))
  }

  override def start(): Unit = {
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
        (fiber.cancel *> fiber.join.void)
          .handleErrorWith(e => SelfAwareLogger[IO].error(e)(s"Stop failed: $e"))
          .unsafeRunCancelable()

      case None =>
        System.err.println("WARN: stop called but plugin not running")
    }
}
