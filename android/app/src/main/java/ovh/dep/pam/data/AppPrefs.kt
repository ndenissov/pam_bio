package ovh.dep.pam.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose

class AppPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    var authCount: Int
        get() = prefs.getInt("auth_count", 0)
        set(value) = prefs.edit().putInt("auth_count", value).apply()

    var githubStarred: Boolean
        get() = prefs.getBoolean("github_starred", false)
        set(value) = prefs.edit().putBoolean("github_starred", value).apply()

    var lastPromptedAuthCount: Int
        get() = prefs.getInt("last_prompted_auth_count", 0)
        set(value) = prefs.edit().putInt("last_prompted_auth_count", value).apply()

    fun incrementAuthCount() {
        authCount++
    }

    // Reactive flows for Compose
    fun getAuthCountFlow(): kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "auth_count") {
                trySend(sharedPreferences.getInt("auth_count", 0))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.getInt("auth_count", 0))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    
    fun getGithubStarredFlow(): kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "github_starred") {
                trySend(sharedPreferences.getBoolean("github_starred", false))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.getBoolean("github_starred", false))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
