package com.fabapps.gomonitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fabapps.gomonitor.core.ChartSeries
import com.fabapps.gomonitor.core.ConnectionForm
import com.fabapps.gomonitor.core.ConnectionList
import com.fabapps.gomonitor.core.ConnectionManager
import com.fabapps.gomonitor.core.ConnectionState
import com.fabapps.gomonitor.core.ConnectionTester
import com.fabapps.gomonitor.core.DiscoveredAgent
import com.fabapps.gomonitor.core.Discovery
import com.fabapps.gomonitor.core.ErrorClassifier
import com.fabapps.gomonitor.core.HistoryResponse
import com.fabapps.gomonitor.core.HistoryTransform
import com.fabapps.gomonitor.core.ProtocolJson
import com.fabapps.gomonitor.core.RecentReadings
import com.fabapps.gomonitor.core.SavedConnection
import com.fabapps.gomonitor.core.SeriesDescriptor
import com.fabapps.gomonitor.core.SeriesIds
import com.fabapps.gomonitor.core.SeriesList
import com.fabapps.gomonitor.core.Snapshot
import com.fabapps.gomonitor.core.TestResult
import com.fabapps.gomonitor.core.Validation
import com.fabapps.gomonitor.data.ConnectionStore
import com.fabapps.gomonitor.data.NetworkMonitor
import com.fabapps.gomonitor.data.NsdDiscovery
import com.fabapps.gomonitor.data.OkHttpTransport
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val period: String = "15m",
    val loading: Boolean = false,
    val error: String? = null,
    val charts: List<ChartSeries> = emptyList(),
    /** Null hasta que llega la primera respuesta. */
    val loadedAtMillis: Long? = null,
)

sealed interface TestUiState {
    data object Running : TestUiState
    data class Done(val result: TestResult) : TestUiState
}

/**
 * Estado de toda la app. La conexión vive acá, no en una pantalla: cambiar de pestaña o girar el
 * teléfono no la corta, y al cerrarse la app el alcance del ViewModel cancela socket y timers.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ConnectionStore(application)
    private val transport = OkHttpTransport()
    private val networkMonitor = NetworkMonitor(application)
    private val recentReadings = RecentReadings()

    private val manager = ConnectionManager(
        transport = transport,
        scope = viewModelScope,
        network = networkMonitor::status,
        onAuthenticated = ::onAuthenticated,
    )

    val state: StateFlow<ConnectionState> = manager.state
    val snapshot: StateFlow<Snapshot?> = manager.snapshot
    val lastUpdateMillis: StateFlow<Long?> = manager.lastUpdateMillis
    val target: StateFlow<SavedConnection?> = manager.target

    /** Null mientras se lee el almacenamiento. */
    private val _connections = MutableStateFlow<List<SavedConnection>?>(null)
    val connections: StateFlow<List<SavedConnection>?> = _connections.asStateFlow()

    private val _catalog = MutableStateFlow<Map<String, SeriesDescriptor>>(emptyMap())
    val catalog: StateFlow<Map<String, SeriesDescriptor>> = _catalog.asStateFlow()

    private val _recent = MutableStateFlow<Map<String, List<Double>>>(emptyMap())
    val recent: StateFlow<Map<String, List<Double>>> = _recent.asStateFlow()

    private val _history = MutableStateFlow(HistoryUiState())
    val history: StateFlow<HistoryUiState> = _history.asStateFlow()

    private val _tests = MutableStateFlow<Map<String, TestUiState>>(emptyMap())
    val tests: StateFlow<Map<String, TestUiState>> = _tests.asStateFlow()

    /**
     * Agentes anunciados en la red que todavía no están guardados. La búsqueda solo corre
     * mientras la pantalla de conexiones los está mirando, y se detiene poco después de salir.
     */
    val discovered: StateFlow<List<DiscoveredAgent>> =
        combine(NsdDiscovery(application).agents(), _connections) { found, saved ->
            Discovery.unsaved(found, saved.orEmpty())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 2_000), emptyList())

    private var historyJob: Job? = null

    init {
        viewModelScope.launch {
            var first = true
            store.connections.collect { list ->
                _connections.value = list
                if (first) {
                    first = false
                    ConnectionList.autoConnectTarget(list)?.let(::connect)
                }
            }
        }
        viewModelScope.launch {
            manager.snapshot.collect { snapshot ->
                if (snapshot != null) _recent.value = recentReadings.add(snapshot)
            }
        }
    }

    fun connect(connection: SavedConnection) {
        if (manager.target.value?.id != connection.id) resetAgentData()
        manager.connect(connection)
    }

    fun disconnect() = manager.disconnect()

    fun retry() = manager.retryNow()

    private fun resetAgentData() {
        recentReadings.clear()
        _recent.value = emptyMap()
        _catalog.value = emptyMap()
        historyJob?.cancel()
        _history.value = HistoryUiState(period = _history.value.period)
    }

    private fun onAuthenticated(connection: SavedConnection) {
        viewModelScope.launch {
            store.update { ConnectionList.markSuccess(it, connection.id, System.currentTimeMillis()) }
        }
        viewModelScope.launch {
            // El catálogo da nombre y unidad a los sensores. Si falla, el dashboard los muestra
            // igual con su id, así que no hace falta avisar.
            runCatching {
                ProtocolJson.decodeFromString(SeriesList.serializer(), transport.get(connection, "/series"))
            }.onSuccess { list ->
                if (manager.target.value?.id == connection.id) _catalog.value = list.series.associateBy { it.id }
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /** Guarda una conexión nueva o editada. Devuelve false si el formulario no es válido. */
    fun save(form: ConnectionForm, existing: SavedConnection?): Boolean {
        val connection = Validation.toConnection(form, newId = UUID.randomUUID().toString(), existing = existing) ?: return false
        viewModelScope.launch { store.update { ConnectionList.upsert(it, connection) } }
        _tests.update { it - connection.id }
        if (manager.target.value?.id == connection.id) {
            // Era la conexión en uso: seguir con los datos nuevos, o soltarla si estaba detenida.
            if (manager.state.value.isActive) manager.connect(connection) else forget()
        }
        return true
    }

    fun delete(connection: SavedConnection) {
        viewModelScope.launch { store.update { ConnectionList.remove(it, connection.id) } }
        _tests.update { it - connection.id }
        if (manager.target.value?.id == connection.id) forget()
    }

    private fun forget() {
        manager.clear()
        resetAgentData()
    }

    fun test(connection: SavedConnection) {
        _tests.update { it + (connection.id to TestUiState.Running) }
        viewModelScope.launch {
            val result = ConnectionTester.test(transport, connection, networkMonitor.status())
            _tests.update { it + (connection.id to TestUiState.Done(result)) }
        }
    }

    /** Carga el historial del período indicado, o recarga el actual. */
    fun loadHistory(period: String = _history.value.period) {
        val connection = manager.target.value ?: return
        historyJob?.cancel()
        val periodChanged = period != _history.value.period
        _history.update {
            // Al cambiar de período los gráficos viejos ya no corresponden; al refrescar se
            // dejan a la vista hasta que llegan los nuevos.
            it.copy(period = period, loading = true, error = null, charts = if (periodChanged) emptyList() else it.charts)
        }
        historyJob = viewModelScope.launch {
            try {
                val catalog = _catalog.value
                val body = transport.get(connection, "/history?period=$period" + seriesParameter(catalog))
                val response = ProtocolJson.decodeFromString(HistoryResponse.serializer(), body)
                _history.update {
                    it.copy(loading = false, error = null, charts = HistoryTransform.toChart(response, catalog), loadedAtMillis = System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = ErrorClassifier.classify(e, connection, networkMonitor.status())
                _history.update { it.copy(loading = false, error = reason.message) }
            }
        }
    }

    /**
     * Series a pedir: las cuatro del sistema que el agente tenga y todos los sensores, hasta el
     * máximo del protocolo. Sin catálogo se omite el parámetro y el agente usa sus valores por
     * defecto.
     */
    private fun seriesParameter(catalog: Map<String, SeriesDescriptor>): String {
        if (catalog.isEmpty()) return ""
        val system = SeriesIds.DEFAULT_HISTORY.filter(catalog::containsKey)
        val sensors = catalog.values.filter { it.collector != SeriesIds.SYSTEM_COLLECTOR }.map { it.id }.sorted()
        val ids = (system + sensors).take(MAX_HISTORY_SERIES)
        return if (ids.isEmpty()) "" else "&series=" + ids.joinToString(",")
    }

    private companion object {
        const val MAX_HISTORY_SERIES = 16
    }
}
