package com.fabapps.gomonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.fabapps.gomonitor.core.ChartSeries
import com.fabapps.gomonitor.core.Formatters
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeOnly = DateTimeFormatter.ofPattern("HH:mm")
private val dayAndTime = DateTimeFormatter.ofPattern("dd/MM HH:mm")

/** Hora local de un instante; con fecha si el gráfico abarca un día o más. */
private fun axisTime(epochSeconds: Long, spanSeconds: Long): String {
    val time = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
    return (if (spanSeconds >= 86_400) dayAndTime else timeOnly).format(time)
}

/**
 * Gráfico de línea de una serie del historial. La línea es el promedio de cada intervalo y la
 * franja tenue va del mínimo al máximo. Los tramos sin datos quedan en blanco.
 */
@Composable
fun HistoryChart(series: ChartSeries, color: Color, modifier: Modifier = Modifier) {
    val span = (series.toEpochSeconds - series.fromEpochSeconds).coerceAtLeast(1)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(140.dp)) {
            // Eje vertical: máximo arriba, mínimo abajo.
            Column(
                Modifier.fillMaxHeight().width(64.dp).padding(end = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text(Formatters.value(series.yMax, series.unit), style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1)
                Text(Formatters.value((series.yMax + series.yMin) / 2, series.unit), style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1)
                Text(Formatters.value(series.yMin, series.unit), style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp)) {
                val range = (series.yMax - series.yMin).takeIf { it > 0 } ?: 1.0
                fun x(epoch: Long) = ((epoch - series.fromEpochSeconds).toFloat() / span).coerceIn(0f, 1f) * size.width
                fun y(value: Double) = size.height - ((value - series.yMin) / range).coerceIn(0.0, 1.0).toFloat() * size.height

                // Líneas guía en máximo, medio y mínimo.
                val dashes = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                for (fraction in listOf(0f, 0.5f, 1f)) {
                    drawLine(Palette.Track, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction), strokeWidth = 1.dp.toPx(), pathEffect = dashes)
                }

                for (segment in series.segments) {
                    if (segment.isEmpty()) continue
                    if (segment.size == 1) {
                        // Un punto aislado no forma línea; se dibuja como punto.
                        drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(x(segment[0].epochSeconds), y(segment[0].avg)))
                        continue
                    }
                    val band = Path()
                    segment.forEachIndexed { i, p -> if (i == 0) band.moveTo(x(p.epochSeconds), y(p.max)) else band.lineTo(x(p.epochSeconds), y(p.max)) }
                    segment.asReversed().forEach { p -> band.lineTo(x(p.epochSeconds), y(p.min)) }
                    band.close()
                    drawPath(band, color.copy(alpha = 0.18f))

                    val line = Path()
                    segment.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(p.epochSeconds), y(p.avg)) else line.lineTo(x(p.epochSeconds), y(p.avg)) }
                    drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().padding(start = 64.dp)) {
            Text(axisTime(series.fromEpochSeconds, span), style = MaterialTheme.typography.labelSmall, color = Palette.Muted, modifier = Modifier.weight(1f))
            Text(axisTime(series.toEpochSeconds, span), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
        }
    }
}
