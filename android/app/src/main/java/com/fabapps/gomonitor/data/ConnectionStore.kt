package com.fabapps.gomonitor.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fabapps.gomonitor.core.ConnectionList
import com.fabapps.gomonitor.core.SavedConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "connections")

/**
 * Conexiones guardadas, en el almacenamiento privado de la app. Nada de esto sale del teléfono.
 * El token queda en texto plano dentro de ese almacenamiento, que otras apps no pueden leer.
 */
class ConnectionStore(private val context: Context) {
    private val key = stringPreferencesKey("connections_json")

    val connections: Flow<List<SavedConnection>> =
        context.dataStore.data.map { ConnectionList.decode(it[key]) }

    /** Aplica un cambio a la lista de forma atómica. */
    suspend fun update(change: (List<SavedConnection>) -> List<SavedConnection>) {
        context.dataStore.edit { preferences ->
            preferences[key] = ConnectionList.encode(change(ConnectionList.decode(preferences[key])))
        }
    }
}
