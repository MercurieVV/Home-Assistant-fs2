package io.github.mercurievv.home_automation.logging

import java.util.concurrent.TimeUnit

import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.influxdb.dto.Point
import org.influxdb.{InfluxDB, InfluxDBFactory}

class InfluxDbAppender extends AppenderBase[ILoggingEvent]:
  private var influxDB: InfluxDB = uninitialized

  private var url: String = ""
  private var user: String = ""
  private var password: String = ""
  private var database: String = ""
  private var measurement: String = "logs"

  def setUrl(v: String): Unit = url                 = v
  def setUser(v: String): Unit = user               = v
  def setPassword(v: String): Unit = password       = v
  def setDatabase(v: String): Unit = database       = v
  def setMeasurement(v: String): Unit = measurement = v

  override def start(): Unit =
    influxDB = InfluxDBFactory.connect(url, user, password)
    influxDB.setDatabase(database)
    influxDB.enableBatch(100, 500, TimeUnit.MILLISECONDS)
    super.start()

  override def stop(): Unit =
    if influxDB != null then influxDB.close()
    super.stop()

  override def append(event: ILoggingEvent): Unit =
    val point = Point
      .measurement(measurement)
      .time(event.getTimeStamp, TimeUnit.MILLISECONDS)
      .tag("level", event.getLevel.toString)
      .tag("logger", event.getLoggerName)
      .tag(event.getMDCPropertyMap)
      .tag(
        "markers",
        Option(event.getMarkerList).map(_.asScala.map(_.getName).mkString(",")).getOrElse(""),
      )
      .addField("message", event.getFormattedMessage)
      .build()
    influxDB.write(point)
