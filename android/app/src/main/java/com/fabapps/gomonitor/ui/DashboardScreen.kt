package com.fabapps.gomonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabapps.gomonitor.core.ConnectionState
import com.fabapps.gomonitor.core.Cpu
import com.fabapps.gomonitor.core.Disk
import com.fabapps.gomonitor.core.Formatters
import com.fabapps.gomonitor.core.Memory
import com.fabapps.gomonitor.core.Network
import com.fabapps.gomonitor.core.ProcessInfo
import com.fabapps.gomonitor.core.ProcessSort
import com.fabapps.gomonitor.core.Processes
import com.fabapps.gomonitor.core.SavedConnection
import com.fabapps.gomonitor.core.Sensors
import com.fabapps.gomonitor.core.SeriesIds
import com.fabapps.gomonitor.core.Snapshot

@Composable
fun DashboardScreen(viewModel: MainViewModel, onOpenConnections: () -> Unit) {
    val target by viewModel.target.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val lastUpdate by viewModel.lastUpdateMillis.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()

    val connection = target
    if (connection == null) {
        EmptyState(
            title = "Sin configurar",
            message = "Agregá una computadora con su dirección IP, puerto y token para ver sus métricas.",
            actionLabel = "Ir a Conexiones",
            onAction = onOpenConnections,
        )
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeaderPanel(connection, state, snapshot, lastUpdate)
        StatusBanner(state, hasData = snapshot != null, onRetry = viewModel::retry)

        val data = snapshot
        if (data == null) {
            if (state.isActive) {
                LoadingState("Esperando la primera muestra…", Modifier.height(220.dp))
            }
            return@Column
        }

        data.cpu?.let { CpuPanel(it, recent[SeriesIds.CPU_TOTAL].orEmpty()) }
        data.memory?.let { MemoryPanel(it, recent[SeriesIds.MEMORY_PERCENT].orEmpty()) }
        data.disks?.let { DisksPanel(it) }
        data.network?.let {
            NetworkPanel(it, recent[SeriesIds.NET_SENT_RATE].orEmpty(), recent[SeriesIds.NET_RECEIVED_RATE].orEmpty())
        }
        // Sensores: cualquier serie que no sea del sistema se dibuja con una tarjeta genérica.
        Sensors.from(data, catalog).forEach { sensor ->
            SensorPanel(sensor, recent[sensor.descriptor.id].orEmpty())
        }
        data.processes?.let { TopProcessesPanel(it) }

        val nothingToShow = data.cpu == null && data.memory == null && data.disks == null &&
            data.network == null && data.processes == null && data.readings.isEmpty()
        if (nothingToShow) {
            Panel("Sin datos") {
                Text("El agente está conectado pero no tiene ningún recolector que envíe datos.", color = Palette.Muted)
            }
        }
    }
}

@Composable
private fun HeaderPanel(connection: SavedConnection, state: ConnectionState, snapshot: Snapshot?, lastUpdate: Long?) {
    val now = rememberNowMillis()
    Panel(connection.name, trailing = { StateChip(state) }) {
        Text(
            snapshot?.hostname?.ifBlank { null } ?: connection.address,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (snapshot != null) {
            LabeledValue("Sistema", "${snapshot.operatingSystem} · ${snapshot.architecture}")
            LabeledValue("Encendida hace", Formatters.uptime(snapshot.uptimeSeconds))
        }
        LabeledValue("Dirección", connection.address)
        LabeledValue(
            "Última actualización",
            lastUpdate?.let { Formatters.elapsed(now - it) } ?: "todavía no hay datos",
            // Datos viejos a la vista sin conexión: que se note que no son actuales.
            valueColor = if (state == ConnectionState.Connected || lastUpdate == null) Palette.Text else Palette.Warning,
        )
    }
}

/** Explica el problema cuando no está conectado, y ofrece reintentar. */
@Composable
private fun StatusBanner(state: ConnectionState, hasData: Boolean, onRetry: () -> Unit) {
    if (state == ConnectionState.Connected || state == ConnectionState.Connecting || state == ConnectionState.Authenticating) return
    val color = Palette.forState(state)
    Panel(state.label, accent = color) {
        val detail = when (state) {
            is ConnectionState.Reconnecting ->
                "${state.reason.message} Reintento ${state.attempt}, próxima espera de ${state.delayMillis / 1000} s."
            ConnectionState.Disconnected -> "La conexión se cerró manualmente."
            else -> state.detail
        }
        if (detail != null) Text(detail, color = Palette.Text, style = MaterialTheme.typography.bodyMedium)
        if (hasData) {
            Text("Los valores de abajo son los últimos recibidos.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onRetry) {
            Text(if (state is ConnectionState.Reconnecting) "Reintentar ahora" else "Conectar")
        }
    }
}

/** Número grande con su minigráfico al lado. */
@Composable
private fun BigValue(text: String, color: Color, history: List<Double>, min: Double? = null, max: Double? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        Spacer(Modifier.width(16.dp))
        Sparkline(history, color, Modifier.weight(1f).height(44.dp), min = min, max = max)
    }
}

@Composable
private fun CpuPanel(cpu: Cpu, history: List<Double>) {
    val color = Palette.forUsage(cpu.totalPercent, Palette.Cpu)
    Panel("CPU", accent = Palette.Cpu) {
        BigValue(Formatters.percent(cpu.totalPercent), color, history, min = 0.0, max = 100.0)
        UsageBar(cpu.totalPercent / 100, color)
        if (cpu.corePercentages.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text("Por núcleo", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            // Dos columnas para que entren muchos núcleos sin ocupar toda la pantalla.
            cpu.corePercentages.withIndex().chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { (index, value) ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", Modifier.width(22.dp), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                            UsageBar(value / 100, Palette.forUsage(value, Palette.Cpu), Modifier.weight(1f), height = 6.dp)
                            Text(
                                "${value.toInt()} %", Modifier.width(44.dp).padding(start = 6.dp),
                                style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1,
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MemoryPanel(memory: Memory, history: List<Double>) {
    val color = Palette.forUsage(memory.usedPercent, Palette.Memory)
    Panel("Memoria", accent = Palette.Memory) {
        BigValue(Formatters.percent(memory.usedPercent), color, history, min = 0.0, max = 100.0)
        UsageBar(memory.usedPercent / 100, color)
        LabeledValue("Usada", "${Formatters.bytes(memory.usedBytes)} de ${Formatters.bytes(memory.totalBytes)}")
        LabeledValue("Disponible", Formatters.bytes(memory.availableBytes))
    }
}

@Composable
private fun DisksPanel(disks: List<Disk>) {
    Panel("Discos", accent = Palette.Disk) {
        if (disks.isEmpty()) {
            Text("El agente no informó ningún disco.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        disks.forEach { disk ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(disk.mountPoint, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Formatters.percent(disk.usedPercent), style = MaterialTheme.typography.bodyMedium, color = Palette.forUsage(disk.usedPercent, Palette.Disk))
                }
                UsageBar(disk.usedPercent / 100, Palette.forUsage(disk.usedPercent, Palette.Disk))
                Text(
                    "${Formatters.bytes(disk.usedBytes)} de ${Formatters.bytes(disk.totalBytes)} · libre ${Formatters.bytes(disk.availableBytes)} · ${disk.filesystem}",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NetworkPanel(network: Network, sentHistory: List<Double>, receivedHistory: List<Double>) {
    Panel("Red") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NetworkColumn("Subida", network.bytesSentPerSecond, network.bytesSentTotal, Palette.Upload, sentHistory, Modifier.weight(1f))
            NetworkColumn("Bajada", network.bytesReceivedPerSecond, network.bytesReceivedTotal, Palette.Download, receivedHistory, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NetworkColumn(label: String, rate: Double, total: Long, color: Color, history: List<Double>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        Text(Formatters.rate(rate), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Sparkline(history, color, Modifier.fillMaxWidth().height(36.dp), min = 0.0)
        Text("Total ${Formatters.bytes(total)}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1)
    }
}

@Composable
private fun SensorPanel(sensor: Sensors.SensorReading, history: List<Double>) {
    val descriptor = sensor.descriptor
    val color = Palette.forKind(descriptor.kind)
    Panel(descriptor.displayName, accent = color) {
        BigValue(Formatters.value(sensor.value, descriptor.unit), color, history)
        // Con rango declarado por el sensor se puede ubicar el valor dentro de él.
        val min = descriptor.min
        val max = descriptor.max
        if (min != null && max != null && max > min) {
            UsageBar((sensor.value - min) / (max - min), color)
            Row {
                Text(Formatters.value(min, descriptor.unit), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                Text(Formatters.value(max, descriptor.unit), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
        }
    }
}

@Composable
private fun TopProcessesPanel(processes: List<ProcessInfo>) {
    Panel("Procesos principales") {
        if (processes.isEmpty()) {
            Text("El agente no informó procesos.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            return@Panel
        }
        ProcessHeader()
        Processes.sorted(processes, ProcessSort.CPU).take(5).forEach { ProcessRow(it) }
        if (processes.size > 5) {
            Text("La lista completa está en la pestaña Procesos.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
    }
}
