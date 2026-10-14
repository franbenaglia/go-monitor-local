package com.fabapps.gomonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabapps.gomonitor.core.ConnectionForm
import com.fabapps.gomonitor.core.ConnectionState
import com.fabapps.gomonitor.core.DiscoveredAgent
import com.fabapps.gomonitor.core.Discovery
import com.fabapps.gomonitor.core.Formatters
import com.fabapps.gomonitor.core.FormErrors
import com.fabapps.gomonitor.core.SavedConnection
import com.fabapps.gomonitor.core.TestResult
import com.fabapps.gomonitor.core.Validation

/** Qué formulario está abierto: ninguno, uno nuevo o la edición de una conexión. */
private sealed interface Editing {
    /** [prefill] trae los datos de un agente encontrado en la red, o va vacío. */
    data class New(val prefill: ConnectionForm = ConnectionForm()) : Editing
    data class Existing(val connection: SavedConnection) : Editing
}

@Composable
fun ConnectionsScreen(viewModel: MainViewModel, onConnected: () -> Unit) {
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val target by viewModel.target.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tests by viewModel.tests.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Editing?>(null) }
    var deleting by remember { mutableStateOf<SavedConnection?>(null) }

    // Recolectar este estado es lo que mantiene activa la búsqueda en la red: empieza al
    // entrar a esta pantalla y se detiene al salir.
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()

    val list = connections
    Box(Modifier.fillMaxSize()) {
        if (list == null) {
            LoadingState("Cargando conexiones…")
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (list.isEmpty()) {
                    Panel("Todavía no hay computadoras") {
                        Text(
                            "Iniciá GoMonitor Agent en la computadora. Si aparece abajo, tocá Agregar; si no, cargá a mano la dirección que el agente muestra al arrancar.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                list.forEach { connection ->
                    val isTarget = target?.id == connection.id
                    ConnectionCard(
                        connection = connection,
                        // Solo la conexión en uso tiene estado; las demás están sin conectar.
                        state = if (isTarget) state else ConnectionState.Disconnected,
                        test = tests[connection.id],
                        onConnect = {
                            viewModel.connect(connection)
                            onConnected()
                        },
                        onDisconnect = viewModel::disconnect,
                        onTest = { viewModel.test(connection) },
                        onEdit = { editing = Editing.Existing(connection) },
                        onDelete = { deleting = connection },
                    )
                }
                DiscoveryPanel(discovered, onAdd = { editing = Editing.New(Discovery.toForm(it)) })
                // Espacio para que el botón flotante no tape la última tarjeta.
                Spacer(Modifier.height(72.dp))
            }
            ExtendedFloatingActionButton(
                onClick = { editing = Editing.New() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = Palette.Cpu,
                contentColor = Palette.Background,
            ) { Text("Agregar a mano") }
        }
    }

    editing?.let { current ->
        val existing = (current as? Editing.Existing)?.connection
        ConnectionFormDialog(
            existing = existing,
            prefill = (current as? Editing.New)?.prefill,
            onDismiss = { editing = null },
            onSave = { form -> if (viewModel.save(form, existing)) editing = null },
        )
    }

    deleting?.let { connection ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Eliminar conexión") },
            text = { Text("Se va a eliminar \"${connection.name}\" (${connection.address}) de este teléfono. El historial guardado en la computadora no se toca.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(connection)
                    deleting = null
                }) { Text("Eliminar", color = Palette.Alert) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

/** Agentes que se anuncian en la red y todavía no están guardados. */
@Composable
private fun DiscoveryPanel(agents: List<DiscoveredAgent>, onAdd: (DiscoveredAgent) -> Unit) {
    Panel("Encontradas en la red", accent = Palette.Download) {
        if (agents.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Download)
                Text("  Buscando…", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            }
            Text(
                "Si la computadora no aparece, agregala a mano: algunos routers y el hotspot del celular no permiten la búsqueda automática.",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
            )
            return@Panel
        }
        agents.forEach { agent ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(agent.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(agent.address, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1)
                }
                OutlinedButton(onClick = { onAdd(agent) }) { Text("Agregar") }
            }
        }
        Text("Al agregar vas a tener que cargar el token del agente.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
    }
}

@Composable
private fun ConnectionCard(
    connection: SavedConnection,
    state: ConnectionState,
    test: TestUiState?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val now = rememberNowMillis()
    Panel(connection.address, trailing = { StateChip(state) }) {
        Text(connection.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LabeledValue(
            "Última conexión exitosa",
            connection.lastSuccessMillis?.let { Formatters.elapsed(now - it) } ?: "nunca",
        )
        LabeledValue("Conexión automática", if (connection.autoConnect) "sí" else "no")
        state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.forState(state)) }

        when (test) {
            TestUiState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Cpu)
                Text("  Probando…", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
            is TestUiState.Done -> when (val result = test.result) {
                is TestResult.Success -> Text(
                    "Prueba correcta: respondió el agente \"${result.agentId}\" y aceptó el token.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Connected,
                )
                is TestResult.Failure -> Text(
                    "La prueba falló: ${result.reason.message}",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Alert,
                )
            }
            null -> Unit
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.isActive) {
                OutlinedButton(onClick = onDisconnect) { Text("Desconectar") }
            } else {
                Button(onClick = onConnect) { Text("Conectar") }
            }
            OutlinedButton(onClick = onTest, enabled = test != TestUiState.Running) { Text("Probar conexión") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit) { Text("Editar") }
            TextButton(onClick = onDelete) { Text("Eliminar", color = Palette.Alert) }
        }
    }
}

@Composable
private fun ConnectionFormDialog(
    existing: SavedConnection?,
    prefill: ConnectionForm?,
    onDismiss: () -> Unit,
    onSave: (ConnectionForm) -> Unit,
) {
    var form by remember { mutableStateOf(existing?.let(ConnectionForm::from) ?: prefill ?: ConnectionForm()) }
    // Los errores se muestran recién después del primer intento de guardar.
    var submitted by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    val errors = if (submitted) Validation.validate(form) else FormErrors()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.Background)
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (existing == null) "Agregar computadora" else "Editar conexión", style = MaterialTheme.typography.headlineSmall)
            Text(
                "La dirección y el puerto son los que muestra GoMonitor Agent al iniciar. El token es el valor de api_token en su configuración.",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
            )

            Field("Nombre", form.name, errors.name, placeholder = "Computadora principal") { form = form.copy(name = it) }
            Field("Dirección IP", form.host, errors.host, placeholder = "192.168.1.50", keyboard = KeyboardType.Uri) { form = form.copy(host = it) }
            Field("Puerto", form.port, errors.port, placeholder = "8080", keyboard = KeyboardType.Number) { form = form.copy(port = it) }
            Field(
                "Token", form.token, errors.token,
                keyboard = KeyboardType.Password,
                transformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Ocultar" else "Ver") } },
            ) { form = form.copy(token = it) }
            Field(
                "Intervalo de reconexión (segundos)", form.reconnectSeconds, errors.reconnectSeconds,
                keyboard = KeyboardType.Number, imeAction = ImeAction.Done,
                supporting = "Espera antes del primer reintento; se duplica en cada intento hasta 60 s.",
            ) { form = form.copy(reconnectSeconds = it) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Conexión automática", style = MaterialTheme.typography.bodyLarge)
                    Text("Conectar con esta computadora al abrir la app.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
                Switch(checked = form.autoConnect, onCheckedChange = { form = form.copy(autoConnect = it) })
            }

            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
                Button(onClick = {
                    submitted = true
                    if (Validation.validate(form).isValid) onSave(form)
                }) { Text("Guardar") }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    error: String?,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    transformation: VisualTransformation = VisualTransformation.None,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = Palette.Muted) } },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = transformation,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = imeAction, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}
