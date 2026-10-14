package com.fabapps.gomonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fabapps.gomonitor.core.ConnectionState
import kotlinx.coroutines.delay

/** Tarjeta con título. [accent] es el color de la magnitud que muestra. */
@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (accent != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Muted,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        content()
    }
}

/** Barra de progreso; [fraction] va de 0 a 1 y se recorta a ese rango. */
@Composable
fun UsageBar(fraction: Double, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(Palette.Track)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat())
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

@Composable
fun StateChip(state: ConnectionState, modifier: Modifier = Modifier) {
    val color = Palette.forState(state)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(state.label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Par etiqueta y valor en una línea. */
@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Palette.Text) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Minigráfico de los últimos valores en vivo. Sin ejes: solo muestra la tendencia. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier, min: Double? = null, max: Double? = null) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val low = min ?: values.min()
        val high = (max ?: values.max()).let { if (it <= low) low + 1 else it }
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height - ((value - low) / (high - low)).coerceIn(0.0, 1.0).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, color.copy(alpha = 0.12f))
    }
}

/** Pantalla o sección sin datos, con una explicación y una acción opcional. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun LoadingState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Palette.Cpu)
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, textAlign = TextAlign.Center)
    }
}

/** Hora actual que se actualiza cada segundo, para textos como "hace 3 s". */
@Composable
fun rememberNowMillis(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return now
}

enum class TabIcon { DASHBOARD, HISTORY, PROCESSES, CONNECTIONS }

/** Íconos de la barra inferior, dibujados a mano para no sumar una biblioteca de íconos. */
@Composable
fun TabGlyph(icon: TabIcon, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (icon) {
            TabIcon.DASHBOARD -> {
                // Cuatro tarjetas.
                val gap = w * 0.12f
                val cell = (w - gap * 3) / 2
                for (row in 0..1) for (col in 0..1) {
                    drawRoundRect(
                        tint,
                        topLeft = Offset(gap + col * (cell + gap), gap + row * (cell + gap)),
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
                        style = stroke,
                    )
                }
            }
            TabIcon.HISTORY -> {
                // Una línea de gráfico sobre su eje.
                val path = Path().apply {
                    moveTo(w * 0.12f, h * 0.70f)
                    lineTo(w * 0.36f, h * 0.42f)
                    lineTo(w * 0.56f, h * 0.60f)
                    lineTo(w * 0.88f, h * 0.22f)
                }
                drawPath(path, tint, style = stroke)
                drawLine(tint, Offset(w * 0.12f, h * 0.88f), Offset(w * 0.88f, h * 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            TabIcon.PROCESSES -> {
                // Una lista.
                for (i in 0..2) {
                    val y = h * (0.26f + i * 0.24f)
                    drawCircle(tint, radius = w * 0.05f, center = Offset(w * 0.16f, y))
                    drawLine(tint, Offset(w * 0.32f, y), Offset(w * 0.88f, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
            }
            TabIcon.CONNECTIONS -> {
                // Una pantalla con su base.
                drawRoundRect(
                    tint,
                    topLeft = Offset(w * 0.12f, h * 0.18f),
                    size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.48f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
                    style = stroke,
                )
                drawLine(tint, Offset(w * 0.34f, h * 0.84f), Offset(w * 0.66f, h * 0.84f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.5f, h * 0.66f), Offset(w * 0.5f, h * 0.84f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}
