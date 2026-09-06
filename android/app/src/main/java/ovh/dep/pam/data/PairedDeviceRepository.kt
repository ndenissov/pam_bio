package ovh.dep.pam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("paired_devices")

@Serializable
data class PairedDevice(
    val serviceName: String,
    val pcPubKey: String,
    val deviceName: String = "",
    val pairedAt: Long = System.currentTimeMillis()
)

/**
 * Persists paired PC information using Jetpack DataStore.
 */
class PairedDeviceRepository(private val context: Context) {

    companion object {
        private val KEY_DEVICES = stringPreferencesKey("devices_json")
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** Flow of all paired devices, emitted on every change. */
    val devicesFlow: Flow<List<PairedDevice>> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_DEVICES] ?: return@map emptyList()
        try {
            json.decodeFromString<List<PairedDevice>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Adds a new paired device. */
    suspend fun addDevice(device: PairedDevice) {
        context.dataStore.edit { prefs ->
            val current = getDeviceList(prefs).toMutableList()
            // Replace if same service_name already exists
            current.removeAll { it.serviceName == device.serviceName }
            current.add(device)
            prefs[KEY_DEVICES] = json.encodeToString(current)
        }
    }

    /** Removes a device by service name. */
    suspend fun removeDevice(serviceName: String) {
        context.dataStore.edit { prefs ->
            val current = getDeviceList(prefs).toMutableList()
            current.removeAll { it.serviceName == serviceName }
            prefs[KEY_DEVICES] = json.encodeToString(current)
        }
    }

    /** Returns a snapshot of all devices (suspend variant). */
    suspend fun getAll(): List<PairedDevice> {
        var result = emptyList<PairedDevice>()
        context.dataStore.edit { prefs ->
            result = getDeviceList(prefs)
        }
        return result
    }

    private fun getDeviceList(prefs: Preferences): List<PairedDevice> {
        val raw = prefs[KEY_DEVICES] ?: return emptyList()
        return try {
            json.decodeFromString<List<PairedDevice>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
