package com.fabapps.gomonitor.core

import java.util.Locale
import kotlin.math.abs

/** Formato de magnitudes para mostrar. Usa coma decimal, como el resto de la app en español. */
object Formatters {
    private val locale = Locale.forLanguageTag("es-AR")
    private val byteUnits = listOf("B", "KB", "MB", "GB", "TB")

    private fun scaled(value: Double, units: List<String>, suffix: String): String {
        var v = abs(value)
        var index = 0
        while (v >= 1024 && index < units.lastIndex) {
            v /= 1024
            index++
        }
        val sign = if (value < 0) "-" else ""
        // Los bytes sueltos no llevan decimales; el resto, uno o dos según el tamaño.
        val number = when {
            index == 0 -> String.format(locale, "%.0f", v)
            v >= 100 -> String.format(locale, "%.0f", v)
            v >= 10 -> String.format(locale, "%.1f", v)
            else -> String.format(locale, "%.2f", v)
        }
        return "$sign$number ${units[index]}$suffix"
    }

    /** 1536 -> "1,50 KB". Base 1024. */
    fun bytes(value: Long): String = scaled(value.toDouble(), byteUnits, "")

    fun bytes(value: Double): String = scaled(value, byteUnits, "")

    /** 1536 -> "1,50 KB/s". */
    fun rate(bytesPerSecond: Double): String = scaled(bytesPerSecond, byteUnits, "/s")

    fun percent(value: Double): String = String.format(locale, "%.1f %%", value)

    /** 93784 -> "1 d 2 h 3 min". Omite las partes que valen cero, salvo que todo sea cero. */
    fun uptime(seconds: Long): String {
        if (seconds < 60) return "${seconds.coerceAtLeast(0)} s"
        val days = seconds / 86_400
        val hours = seconds % 86_400 / 3_600
        val minutes = seconds % 3_600 / 60
        return buildList {
            if (days > 0) add("$days d")
            if (hours > 0) add("$hours h")
            if (minutes > 0) add("$minutes min")
        }.joinToString(" ")
    }

    /** "hace 3 s", "hace 2 min"; para la última actualización y la última conexión. */
    fun elapsed(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000
        return when {
            seconds < 2 -> "recién"
            seconds < 60 -> "hace $seconds s"
            seconds < 3_600 -> "hace ${seconds / 60} min"
            seconds < 86_400 -> "hace ${seconds / 3_600} h"
            else -> "hace ${seconds / 86_400} d"
        }
    }

    /**
     * Valor de una serie según su unidad. Una unidad que esta versión no conoce se muestra tal
     * cual después del número, como pide el protocolo.
     */
    fun value(value: Double, unit: String): String = when (unit) {
        "percent" -> percent(value)
        "bytes" -> bytes(value)
        "bytes_per_second" -> rate(value)
        "celsius" -> String.format(locale, "%.1f °C", value)
        "hectopascal" -> String.format(locale, "%.1f hPa", value)
        "volt" -> String.format(locale, "%.2f V", value)
        "count", "" -> number(value)
        else -> "${number(value)} $unit"
    }

    private fun number(value: Double): String =
        if (value == Math.floor(value) && abs(value) < 1e12) {
            String.format(locale, "%.0f", value)
        } else {
            String.format(locale, "%.2f", value)
        }
}
