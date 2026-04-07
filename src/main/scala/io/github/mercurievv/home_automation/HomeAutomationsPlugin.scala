package io.github.mercurievv.home_automation

import io.github.mercurievv.cats.arrow.kleisli.*
import io.github.mercurievv.cats.composeMonads
import io.github.mercurievv.home_automation.AppConfig
import io.github.mercurievv.home_automation.TypeSystem.StatesT
import io.github.mercurievv.home_automation.Wiring
import io.github.mercurievv.home_automation.impl.TypeSystemImpl
import io.github.mercurievv.home_automation.instances.JsonInstances.given
import io.github.mercurievv.home_automation.mqtt.MessageCoders.*
import io.github.mercurievv.home_automation.mqtt.Mqtt
import io.github.mercurievv.home_automation.rules.Bindings
import io.github.mercurievv.home_automation.rules.BindingsProcessor
import io.github.mercurievv.home_automation.rules.BindingsTooling
import io.github.mercurievv.home_automation.rules.EventTypes.{EntityId, In, Topic}
import io.github.mercurievv.home_automation.state.*

import java.util.concurrent.atomic.AtomicReference

import scala.compiletime.uninitialized
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.implicits.*
import cats.{Applicative, Id, Monad, MonoidK, Show, Traverse, ~>}

import cats.effect.implicits.*
import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Console
import cats.effect.unsafe.IORuntime
import cats.effect.{FiberIO, IO, LiftIO}

import io.circe.{Encoder, JsonObject}

import fs2.*

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import net.sigusr.mqtt.api.QualityOfService.AtMostOnce
import net.sigusr.mqtt.api.Session
import org.pf4j.Plugin
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.{Slf4jFactory, Slf4jLogger}
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer

class HomeAutomationsPlugin extends Plugin {

  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Null"))
  private var runtime: IORuntime = uninitialized

  private val fiberRef: AtomicReference[Option[FiberIO[Unit]]] =
    new AtomicReference[Option[FiberIO[Unit]]](None)

  @annotation.nowarn("msg=unused implicit parameter")
  def programmF[F[_]: {SelfAwareStructuredLogger, Async, Console, Applicative, LoggerFactory, LiftIO}]: F[Unit] =
    val ts = new TypeSystemImpl[F]
    import ts.given
    AppConfig.load[F].flatMap { config =>
      OtelJava
        .autoConfigured[F] { builder =>
          builder.addPropertiesSupplier(() => config.otelProps.asJava)
        }
        .product(PersistentDeviceState.create[F, ts.type](ts))
        .use {
          (
            otel,
            _,
          ) =>
            otel.tracerProvider.tracer("ha-automations").get.flatMap { tracer =>
              given Tracer[F] = tracer
              given SelfAwareStructuredLogger[F] = withTracing(summon[SelfAwareStructuredLogger[F]])

              given Id ~> F = new (Id ~> F) { def apply[A](fa: Id[A]) = fa.pure }

              val addLogContext: Topic => Map[String, String] = topic =>
                Map(
                  "entityId"  -> topic.entityId.value,
                  "service"   -> topic.service,
                  "eventType" -> topic.eventType.getOrElse(""),
                  "topic"     -> topic.value,
                )
              val pluginResources: Resource[F, ((AppConfig.MqttSettings, Session[F]), StatesT[F, Topic, JsonObject])] =
                Async[F]
                  .pure(config.mqtt)
                  .toResource
                  .mproduct(Mqtt.create[F])
                  .mproduct { case (_, session) =>
                    Wiring
                      .wireRessources[F, Topic, JsonObject]
                      .apply((Map.empty, ("./db/", DeviceState.source[F](session))))
                  }

              val retryPolicy: Stream[F, FiniteDuration] =
                Stream.iterate(10.seconds)(d => (d * 2).min(5.minutes))

              type FL[a] = F[List[a]]
              val o2l: Option ~> List = new (Option ~> List) { def apply[A](fa: Option[A]) = fa.toList }

              given Monad[FL] = composeMonads[F, List]

              given catsStdShowForList: [A: Show] => Show[List[A]] =
                _.iterator.map(Show[A].show).mkString("", ";", "")
              val bindingsProcessor = createBindingProcessor(addLogContext, o2l, FunctionK.id)
              val processBindingFunction = bindingsProcessor.processBindings.lmap[(ts.InputEvent, ts.States)](t =>
                (
                  t._1,
                  ((k: Topic) => t._2(k).get.flatMap(_.getOrElse(JsonObject.empty).pure[List].pure[F])).k,
                ),
              )
              Stream
                .resource(pluginResources)
                .flatMap { case ((settings, session), states) =>
                  Stream.eval(session.subscribe(Vector(settings.topic -> AtMostOnce))) >>
                    Wiring
                      .wire[F, List, Stream[F, *]]
                      .apply(ts)(
                        Kleisli((c: Session[F]) => c.messages.map(decodeMessage).flatMap(Stream.emits)),
                        Kleisli(producer =>
                          Kleisli[FL, ts.OutputEvent, Unit]((oe: ts.OutputEvent) =>
                            val msg = encodeMessage(oe)
                            producer.publish(msg.topic, msg.payload).map(_.pure[List]),
                          ).pure[Stream[F, *]],
                        ),
                        processBindingFunction,
                        addLogContext,
                        new (Id ~> Stream[F, *]) { def apply[A](fa: A): Stream[F, A] = Stream.emit(fa) },
                        preFilterK = Kleisli((ie: ts.InputEvent) =>
                          fs2.Stream
                            .emit(ie)
                            .filterNot { v =>
                              val entityIdStr = v.value._1.value
                              entityIdStr.contains("mikrotik") ||
                              entityIdStr.contains("archer") ||
                              (v.value._1.service == "ha" && v.value._2.toString.contains("measurement"))
                            }
                            .covary[F],
                        ),
                        if_Z2mBridgeGroups_then_splitK = Kleisli((ie: ts.InputEvent) =>
                          if (
                              ie.value._1 == Topic("zigbee2mqtt",
                                                   EntityId("bridge"),
                                                   Some("groups"),
                              ) || ie.value._1 == Topic("zigbee2mqtt", EntityId("bridge"), Some("devices"))
                            )
                          then
                            Stream.emits(
                              for {
                                name <- ie.value._2("friendly_name").flatMap(_.asString).toList
                              } yield In(ie.value._1.copy(entityId = EntityId(name), eventType = None), ie.value._2),
                            )
                          else Stream.emit(ie),
                        ),
                      )
                      .apply(((ts, states), session))
                      .evalMap { case (inputEvent, process) =>
                        Tracer[F].span("mqtt.message.process").surround {
                          process
                            .run(inputEvent)
                            .handleErrorWith(e =>
                              SelfAwareStructuredLogger[F].error(e)("Error during event processing").as(Nil),
                            )
                        }
                      }
                      .drain
                }
                .onFinalize(Logger[F].warn("MQTT stream completed unexpectedly — will retry"))
                .append(Stream.raiseError(new Exception("MQTT stream ended without error")))
                .attempts(retryPolicy)
                .evalMap {
                  case Left(e)  => Logger[F].error(e)(s"Plugin failed, retrying: ${e.getMessage}")
                  case Right(_) => Applicative[F].unit
                }
                .compile
                .drain
            }
        }
    }

  def createBindingProcessor[
    F[_]: {SelfAwareStructuredLogger, Async, Applicative},
    S[_]: {Applicative, MonoidK, Monad, Traverse},
  ](
    addLogContext: Topic => Map[String, String],
    o2s: Option ~> S,
    l2s: List ~> S,
  )(using Monad[[a] =>> F[S[a]]],
    Show[S[String]],
  ): BindingsProcessor[F, S, Topic, JsonObject] = {
    val bindingsTooling = new BindingsTooling[[a] =>> F[S[a]]]()
    val bindings = Bindings.create[F, S](bindingsTooling, o2s, l2s)

    given Show[Topic] = Show.show(_.value)
    given Show[JsonObject] = Show.show(_.toJson.noSpaces)
    new BindingsProcessor[F, S, Topic, JsonObject](o2s, bindings, addLogContext)
  }

  /** Loads config values needed by logback.xml as temporary system properties for the duration of doConfigure(), then
    * clears them. System properties are resolved by logback's ${...} substitution but are never merged into MDC, so
    * credentials don't leak into log tags.
    */
  private def withHoconAsSystemProps[A](action: => A): A =
    import com.typesafe.config.ConfigFactory
    val keys = Seq(
      "loki.url",
      "loki.user",
      "loki.password",
      "influxdb.url",
      "influxdb.user",
      "influxdb.password",
      "influxdb.database",
      "influxdb.measurement",
    )
    val loaded =
      try
        val config = ConfigFactory.parseFile(AppConfig.configPath.toFile)
        keys.flatMap(k => if config.hasPath(k) then Some(k -> config.getString(k)) else None).toMap
      catch
        case e: Exception =>
          System.err.println(s"[Plugin-logback] Failed to load HOCON for logback substitution: $e")
          Map.empty
    loaded.foreach { case (k, v) => System.setProperty(k, v) }
    try action
    finally loaded.keys.foreach(System.clearProperty)

  private def reconfigureLogback(): Unit =
    // Set system props BEFORE getILoggerFactory() so DefaultJoranConfigurator
    // can resolve ${loki.url} etc. on the very first SLF4J initialization.
    withHoconAsSystemProps {
      val factory = org.slf4j.LoggerFactory.getILoggerFactory
      System.err.println(s"[Plugin-logback] factory=${factory.getClass.getName} cl=${factory.getClass.getClassLoader}")
      factory match {
        case context: LoggerContext =>
          val url = getClass.getResource("/logback.xml")
          System.err.println(s"[Plugin-logback] logback.xml url=$url")
          if url != null then
            val configurator = new JoranConfigurator()
            configurator.setContext(context)
            context.reset()
            try configurator.doConfigure(url)
            catch case e: Exception => System.err.println(s"[Plugin-logback] doConfigure failed: $e")
            System.err.println(
              s"[Plugin-logback] configured OK, appenders=${context.getLogger("root").iteratorForAppenders().hasNext}",
            )
          else System.err.println(s"[Plugin-logback] logback.xml NOT found in plugin classpath!")
        case other =>
          System.err.println(s"[Plugin-logback] factory is NOT LoggerContext: ${other.getClass.getName}")
          System.err.println(s"[Plugin-logback] plugin LoggerContext cl: ${classOf[LoggerContext].getClassLoader}")
      }
    }

  private def withTracing[F[_]: {Monad, Tracer}](
    underlying: SelfAwareStructuredLogger[F],
  ): SelfAwareStructuredLogger[F] = {
    def tc: F[Map[String, String]] =
      Tracer[F].currentSpanContext.map(
        _.map(c => Map("trace_id" -> c.traceId.toHex, "span_id" -> c.spanId.toHex)).getOrElse(Map.empty),
      )
    def run(f: SelfAwareStructuredLogger[F] => F[Unit]): F[Unit] =
      tc.flatMap(ctx => f(underlying.addContext(ctx)))

    new SelfAwareStructuredLogger[F] {
      def isTraceEnabled: F[Boolean] = underlying.isTraceEnabled
      def isDebugEnabled: F[Boolean] = underlying.isDebugEnabled
      def isInfoEnabled: F[Boolean] = underlying.isInfoEnabled
      def isWarnEnabled: F[Boolean] = underlying.isWarnEnabled
      def isErrorEnabled: F[Boolean] = underlying.isErrorEnabled

      def trace(msg: => String): F[Unit] = run(_.trace(msg))
      def trace(ctx: Map[String, String])(msg: => String): F[Unit] = run(_.trace(ctx)(msg))
      def trace(t: Throwable)(msg: => String): F[Unit] = run(_.trace(t)(msg))
      def trace(
        ctx: Map[String, String],
        t: Throwable,
      )(
        msg: => String,
      ): F[Unit] = run(_.trace(ctx, t)(msg))

      def debug(msg: => String): F[Unit] = run(_.debug(msg))
      def debug(ctx: Map[String, String])(msg: => String): F[Unit] = run(_.debug(ctx)(msg))
      def debug(t: Throwable)(msg: => String): F[Unit] = run(_.debug(t)(msg))
      def debug(
        ctx: Map[String, String],
        t: Throwable,
      )(
        msg: => String,
      ): F[Unit] = run(_.debug(ctx, t)(msg))

      def info(msg: => String): F[Unit] = run(_.info(msg))
      def info(ctx: Map[String, String])(msg: => String): F[Unit] = run(_.info(ctx)(msg))
      def info(t: Throwable)(msg: => String): F[Unit] = run(_.info(t)(msg))
      def info(
        ctx: Map[String, String],
        t: Throwable,
      )(
        msg: => String,
      ): F[Unit] = run(_.info(ctx, t)(msg))

      def warn(msg: => String): F[Unit] = run(_.warn(msg))
      def warn(ctx: Map[String, String])(msg: => String): F[Unit] = run(_.warn(ctx)(msg))
      def warn(t: Throwable)(msg: => String): F[Unit] = run(_.warn(t)(msg))
      def warn(
        ctx: Map[String, String],
        t: Throwable,
      )(
        msg: => String,
      ): F[Unit] = run(_.warn(ctx, t)(msg))

      def error(msg: => String): F[Unit] = run(_.error(msg))
      def error(ctx: Map[String, String])(msg: => String): F[Unit] = run(_.error(ctx)(msg))
      def error(t: Throwable)(msg: => String): F[Unit] = run(_.error(t)(msg))
      def error(
        ctx: Map[String, String],
        t: Throwable,
      )(
        msg: => String,
      ): F[Unit] = run(_.error(ctx, t)(msg))
    }
  }

  override def start(): Unit = {
    reconfigureLogback()
    given SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
    given LoggerFactory[IO] = Slf4jFactory.create[IO]
    System.setProperty("cats.effect.trackFiberContext", "true")
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

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  override def stop(): Unit =
    fiberRef.getAndSet(None) match {
      case Some(fiber) =>
        given IORuntime = runtime
        (fiber.cancel *> fiber.join.void)
          .timeout(10.seconds)
          .handleErrorWith(e => IO(System.err.println(s"Stop failed/timed out: $e")))
          .unsafeRunSync()
        runtime.shutdown()
        runtime = null
        println("App stopped")

      case None =>
        System.err.println("WARN: stop called but plugin not running")
    }
}
