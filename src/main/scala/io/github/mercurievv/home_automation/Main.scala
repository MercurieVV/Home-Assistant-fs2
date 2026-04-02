package io.github.mercurievv.home_automation

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, IOApp}

import org.typelevel.log4cats.*
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.{Slf4jFactory, Slf4jLogger}

object Main extends IOApp.Simple:

  override protected def runtime: IORuntime = {
    System.setProperty("cats.effect.trackFiberContext", "true")
    super.runtime
  }

  given SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  given LoggerFactory[IO] = Slf4jFactory.create[IO]

  val plugin = new HomeAutomationsPlugin

  def run: IO[Unit] =
    plugin.programmF[IO]
