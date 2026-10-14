package com.fabapps.gomonitor.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabapps.gomonitor.core.ChartSeries
import com.fabapps.gomonitor.core.ConnectionState
import com.fabapps.gomonitor.core.Formatters
import com.fabapps.gomonitor.core.HISTORY_PERIODS
import com.fabapps.gomonitor.core.SeriesDescriptor
import com.fabapps.gomonitor.core.SeriesIds
import kotlinx.coroutines.delay

private const val REFRESH_MILLIS = 30_000L

/** Nombre y tipo de las series del sistema, por si el catálogo todavía no llegó. */
private val knownSeries = mapOf(
    SeriesIds.CPU_TOTAL to ("CPU" to "cpu"),
    SeriesIds.MEMORY_PERCENT to ("Memoria" to "memory"),
    SeriesIds.NET_SENT_RATE to ("Subida" to "network_upload"),
    SeriesIds.NET_RECEIVED_RATE to ("Bajada" to "network_download"),
)

@Composable
fun HistoryScreen(viewModel: MainViewModel, onOpenConnections: () -> Unit) {
    val target by viewModel.target.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()

    val connection = target
    if (connection == null) {
        EmptyState("Sin configurar", "Conectate a una computadora para ver su historial.", actionLabel = "Ir a Conexiones", onAction = onOpenConnections)
        return
    }

    // Carga al entrar, al conectar y al llegar el catálogo (que agrega los sensores), y refresca
    // mientras la pantalla está visible. Al salir de la pantalla el efecto se cancela solo.
    val connected = state == ConnectionState.Connected
    LaunchedEffect(connection.id, connected, catalog.keys) {
        if (!connected) return@LaunchedEffect
        while (true) {
            viewModel.loadHistory()
            delay(REFRESH_MILLIS)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HISTORY_PERIODS.forEach { (id, label) ->
                FilterChip(selected = history.period == id, onClick = { viewModel.loadHistory(id) }, label = { Text(label) })
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
            val now = rememberNowMillis()
            val status = when {
                history.loading && history.charts.isEmpty() -> "Cargando…"
                history.loadedAtMillis != null -> "Actualizado ${Formatters.elapsed(now - history.loadedAtMillis!!)}"
                else -> ""
            }
            Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            if (history.loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Cpu)
            } else {
                TextButton(onClick = { viewModel.loadHistory() }, enabled = connected) { Text("Actualizar") }
            }
        }

        if (!connected) {
            Panel(state.label, accent = Palette.forState(state)) {
                Text(
                    state.detail ?: "El historial se consulta a la computadora; hace falta estar conectado.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (history.charts.isNotEmpty()) {
                    Text("Los gráficos de abajo son los últimos cargados.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
                if (!state.isActive) OutlinedButton(onClick = viewModel::retry) { Text("Conectar") }
            }
        }

        history.error?.let { message ->
            Panel("No se pudo cargar el historial", accent = Palette.Alert) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { viewModel.loadHistory() }, enabled = connected) { Text("Reintentar") }
            }
        }

        if (history.charts.isEmpty() && !history.loading && history.error == null && history.loadedAtMillis != null) {
            Panel("Sin series") {
                Text("El agente no tiene ninguna serie con historial.", color = Palette.Muted)
            }
        }

        history.charts.forEach { series -> SeriesPanel(series, catalog[series.id]) }
    }
}

@Composable
private fun SeriesPanel(series: ChartSeries, descriptor: SeriesDescriptor?) {
    val known = knownSeries[series.id]
    val title = known?.first ?: descriptor?.displayName ?: series.id
    val color = Palette.forKind(known?.second ?: descriptor?.kind ?: "generic")
    Panel(
        title,
        accent = color,
        trailing = {
            series.latest?.let {
                Text(Formatters.value(it.avg, series.unit), style = MaterialTheme.typography.labelLarge, color = color)
            }
        },
    ) {
        if (series.isEmpty) {
            Text("Sin datos en este período.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 24.dp))
        } else {
            HistoryChart(series, color)
            val points = series.points
            Text(
                "Mín ${Formatters.value(points.minOf { it.min }, series.unit)} · " +
                    "Prom ${Formatters.value(points.map { it.avg }.average(), series.unit)} · " +
                    "Máx ${Formatters.value(points.maxOf { it.max }, series.unit)}",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
            )
        }
    }
}
