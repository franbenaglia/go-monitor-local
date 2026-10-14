package com.fabapps.gomonitor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.fabapps.gomonitor.core.ConnectionState

/** Colores de la app. Cada magnitud tiene el suyo y lo conserva en todas las pantallas. */
object Palette {
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF171B22)
    val SurfaceHigh = Color(0xFF1F242D)
    val Track = Color(0xFF2A3140)
    val Text = Color(0xFFE6E9EF)
    val Muted = Color(0xFF8B93A1)

    val Cpu = Color(0xFF4FC3F7)
    val Memory = Color(0xFFBA68C8)
    val Disk = Color(0xFFFFB74D)
    val Upload = Color(0xFFF06292)
    val Download = Color(0xFF4DD0E1)
    val Sensor = Color(0xFFAED581)

    val Connected = Color(0xFF66BB6A)
    val Warning = Color(0xFFFFCA28)
    val Alert = Color(0xFFEF5350)

    /** Color de una serie según su tipo; lo que no se conoce usa el color de sensores. */
    fun forKind(kind: String): Color = when (kind) {
        "cpu" -> Cpu
        "memory" -> Memory
        "network_upload" -> Upload
        "network_download" -> Download
        "temperature" -> Disk
        "humidity" -> Download
        else -> Sensor
    }

    /** Un uso alto cambia al color de advertencia o de alerta, sea cual sea la magnitud. */
    fun forUsage(percent: Double, normal: Color): Color = when {
        percent >= 90 -> Alert
        percent >= 75 -> Warning
        else -> normal
    }

    fun forState(state: ConnectionState): Color = when (state) {
        ConnectionState.Connected -> Connected
        ConnectionState.Connecting, ConnectionState.Authenticating, is ConnectionState.Reconnecting -> Warning
        is ConnectionState.Failed -> Alert
        ConnectionState.Disconnected, ConnectionState.NotConfigured -> Muted
    }
}

private val colors = darkColorScheme(
    primary = Palette.Cpu,
    onPrimary = Palette.Background,
    secondary = Palette.Download,
    onSecondary = Palette.Background,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.Muted,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    outline = Palette.Track,
    outlineVariant = Palette.Track,
    error = Palette.Alert,
    onError = Palette.Background,
)

@Composable
fun GoMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
