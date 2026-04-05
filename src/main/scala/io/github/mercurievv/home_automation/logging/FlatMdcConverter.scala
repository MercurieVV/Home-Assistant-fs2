package io.github.mercurievv.home_automation.logging

import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/** Outputs all non-empty, non-JSON MDC entries as ",key=value,key=value" (leading comma when non-empty). */
class FlatMdcConverter extends ClassicConverter {

  override def convert(event: ILoggingEvent): String = {
    val entries = event.getMDCPropertyMap.asScala
      .filter { case (_, v) => v != null && v.nonEmpty && !v.startsWith("{") && !v.startsWith("[") && !v.contains(",") }
      .map { case (k, v) => s"$k=$v" }
      .mkString(",")
    if entries.isEmpty then "" else "," + entries
  }
}
