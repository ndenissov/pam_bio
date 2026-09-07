package ovh.dep.pam.data.history

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AuthHistoryItem(
    val timestamp: Long,
    val user: String,
    val service: String,
    val status: String,
    val method: String
)

private val Context.historyDataStore by preferencesDataStore(name = "auth_history")

class AuthHistoryRepository(private val context: Context) {
    private val HISTORY_KEY = stringPreferencesKey("history_list")

    fun getHistory(): Flow<List<AuthHistoryItem>> {
        return context.historyDataStore.data.map { prefs ->
            val jsonStr = prefs[HISTORY_KEY] ?: "[]"
            try {
                Json.decodeFromString<List<AuthHistoryItem>>(jsonStr)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun addHistoryItem(item: AuthHistoryItem) {
        context.historyDataStore.edit { prefs ->
            val jsonStr = prefs[HISTORY_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<AuthHistoryItem>>(jsonStr)
            } catch (e: Exception) {
                emptyList()
            }
            val newList = (listOf(item) + currentList).take(100) // Keep last 100 items
            prefs[HISTORY_KEY] = Json.encodeToString(newList)
        }
    }
}
