package com.fabapps.gomonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabapps.gomonitor.core.ConnectionState
import com.fabapps.gomonitor.core.Formatters
import com.fabapps.gomonitor.core.ProcessInfo
import com.fabapps.gomonitor.core.ProcessSort
import com.fabapps.gomonitor.core.Processes

@Composable
fun ProcessHeader(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        Text("PID", Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
        Text("Nombre", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
        Text("CPU", Modifier.width(72.dp), style = MaterialTheme.typography.labelSmall, color = Palette.Cpu, textAlign = TextAlign.End)
        Text("Memoria", Modifier.width(72.dp), style = MaterialTheme.typography.labelSmall, color = Palette.Memory, textAlign = TextAlign.End)
    }
}

@Composable
fun ProcessRow(process: ProcessInfo, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${process.pid}", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1)
        Text(process.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(Formatters.percent(process.cpuPercent), Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, maxLines = 1)
        Text(Formatters.percent(process.memoryPercent), Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, maxLines = 1)
    }
}

@Composable
fun ProcessesScreen(viewModel: MainViewModel, onOpenConnections: () -> Unit) {
    val target by viewModel.target.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(ProcessSort.CPU) }

    if (target == null) {
        EmptyState("Sin configurar", "Conectate a una computadora para ver sus procesos.", actionLabel = "Ir a Conexiones", onAction = onOpenConnections)
        return
    }
    val data = snapshot
    if (data == null) {
        if (state.isActive) {
            LoadingState("Esperando la primera muestra…")
        } else {
            EmptyState(state.label, state.detail ?: "No hay conexión con la computadora.", actionLabel = "Conectar", onAction = viewModel::retry)
        }
        return
    }
    val processes = data.processes
    if (processes == null) {
        EmptyState("Sin procesos", "Este agente solo envía lecturas de sensores; no informa procesos.")
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Ordenar por", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            FilterChip(selected = sort == ProcessSort.CPU, onClick = { sort = ProcessSort.CPU }, label = { Text("CPU") })
            FilterChip(selected = sort == ProcessSort.MEMORY, onClick = { sort = ProcessSort.MEMORY }, label = { Text("Memoria") })
        }
        if (state != ConnectionState.Connected) {
            Text(
                "${state.label}: la lista es la última recibida.",
                color = Palette.Warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        ProcessHeader(Modifier.padding(vertical = 6.dp))
        if (processes.isEmpty()) {
            EmptyState("Sin procesos", "El agente no informó ningún proceso en la última muestra.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(Processes.sorted(processes, sort), key = { it.pid }) { ProcessRow(it) }
            }
        }
    }
}
