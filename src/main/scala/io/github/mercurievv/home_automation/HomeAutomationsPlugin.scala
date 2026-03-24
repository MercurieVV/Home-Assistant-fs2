package io.github.mercurievv.home_automation

import io.github.mercurievv.cats.arrow.kleisli.*
import io.github.mercurievv.cats.composeMonads
import io.github.mercurievv.home_automation.TypeSystem.StatesT
import io.github.mercurievv.home_automation.Wiring
import io.github.mercurievv.home_automation.impl.TypeSystemImpl
import io.github.mercurievv.home_automation.instances.JsonInstances.given
import io.github.mercurievv.home_automation.mqtt.MessageCoders.*
import io.github.mercurievv.home_automation.mqtt.Mqtt
import io.github.mercurievv.home_automation.rules.Bindings
import io.github.mercurievv.home_automation.rules.BindingsProcessor
import io.github.mercurievv.home_automation.rules.BindingsTooling
import io.github.mercurievv.home_automation.rules.EventTypes.EntityId
import io.github.mercurievv.home_automation.state.*

import java.util.concurrent.atomic.AtomicReference

import scala.compiletime.uninitialized
import scala.concurrent.duration.*

import cats.data.Kleisli
import cats.implicits.*
import cats.kernel.Semigroup
import cats.mtl.Stateful
import cats.{Applicative, Monad, ~>}

import cats.effect.implicits.*
import cats.effect.kernel.{Async, Resource}
import cats.effect.std.{Console, MapRef}
import cats.effect.unsafe.IORuntime
import cats.effect.{FiberIO, IO}

import io.circe.JsonObject

import fs2.*

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import fetch.{DataSource, Fetch}
import net.sigusr.mqtt.api.QualityOfService.AtMostOnce
import net.sigusr.mqtt.api.Session
import org.pf4j.Plugin
import org.slf4j.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareLogger, SelfAwareStructuredLogger}

class HomeAutomationsPlugin extends Plugin {
  reconfigureLogback()
  given SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  private var runtime: IORuntime = uninitialized

  private val fiberRef: AtomicReference[Option[FiberIO[Unit]]] =
    new AtomicReference[Option[FiberIO[Unit]]](None)

  def programmF[F[_]: {SelfAwareStructuredLogger, Async, Console, Applicative}]: F[Unit] = {
    val ts = new TypeSystemImpl[F]
    val pluginResources: Resource[F, (Mqtt.MqttSettings, Session[F])] = Mqtt
      .loadSettings[F]
      .toResource
      .mproduct(Mqtt.create[F])

    val retryPolicy: Stream[F, FiniteDuration] =
      Stream.iterate(10.seconds)(d => (d * 2).min(5.minutes))

    type FL[a] = F[List[a]]
    val o2l: Option ~> List = new (Option ~> List) { def apply[A](fa: Option[A]) = fa.toList }

    given Monad[FL] = composeMonads[F, List]
    val bindingsTooling = new BindingsTooling[FL]()
    val bindings = Bindings.create[F, List](bindingsTooling, o2l)

    val bindingsProcessor: BindingsProcessor[F, List] = new BindingsProcessor[F, List](o2l, bindings)

    Stream
      .resource(pluginResources)
      .mproduct(v => Stream.eval(createStates[F, ts.EventId, ts.EventState](DeviceState.source[F](v._2))))
      .flatMap { case ((settings, session), states) =>
        Stream.eval(session.subscribe(Vector(settings.topic -> AtMostOnce))) >>
          Wiring
            .wire[F, List]
            .apply(ts)(
              decodeMessage,
              encodeMessage,
              bindingsProcessor.processBindings.lmap[(ts.InputEvent, ts.States)](t =>
                (
                  t._1,
                  ((k: EntityId) => t._2(k).get.flatMap(_.getOrElse(JsonObject.empty).pure[List].pure[F])).k,
                ),
              ),
            )
            .apply(((ts, states), session))
            .evalMap { case (inputEvent, process) =>
              process
                .run(inputEvent)
                .handleErrorWith(e => SelfAwareStructuredLogger[F].error(e)("Error during event processing").as(Nil))
            }
            .drain
      }
      .attempts(retryPolicy)
      .evalMap {
        case Left(e)  => Logger[F].error(e)(s"Plugin failed, retrying: ${e.getMessage}")
        case Right(_) => Applicative[F].unit
      }
      .compile
      .drain

  }

  def createStates[F[_]: Async, K, V: Semigroup](deviceStateSource: DataSource[F, K, V]): F[StatesT[F, K, V]] =
    MapRef
      .inSingleImmutableMap[F, F, K, V]()
      .map(mapRef =>
        val cache = MapRefCache[F, K, V](mapRef)
        (k: K) =>
          new Stateful[F, Option[V]] {
            override def monad: Monad[F] = summon

            override def get: F[Option[V]] = Fetch.run(Fetch.optional[F, K, V](k, deviceStateSource), cache)

            override def set(s: Option[V]): F[Unit] = mapRef(k).update(_ |+| s)
          },
      )

  private def reconfigureLogback(): Unit =
    val factory = LoggerFactory.getILoggerFactory
    System.err.println(s"[Plugin-logback] factory=${factory.getClass.getName} cl=${factory.getClass.getClassLoader}")
    factory match
      case context: LoggerContext =>
        val url = getClass.getResource("/logback.xml")
        System.err.println(s"[Plugin-logback] logback.xml url=$url")
        if url != null then
          val configurator = new JoranConfigurator()
          configurator.setContext(context)
          context.reset()
          try
            configurator.doConfigure(url)
            System.err.println(
              s"[Plugin-logback] configured OK, appenders=${context.getLogger("root").iteratorForAppenders().hasNext}",
            )
          catch case e: Exception => System.err.println(s"[Plugin-logback] doConfigure failed: $e")
        else System.err.println(s"[Plugin-logback] logback.xml NOT found in plugin classpath!")
      case other =>
        System.err.println(s"[Plugin-logback] factory is NOT LoggerContext: ${other.getClass.getName}")
        System.err.println(s"[Plugin-logback] plugin LoggerContext cl: ${classOf[LoggerContext].getClassLoader}")

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
