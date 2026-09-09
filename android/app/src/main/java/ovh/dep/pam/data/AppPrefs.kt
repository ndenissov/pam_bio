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

    var serviceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(value) = prefs.edit().putBoolean("service_enabled", value).apply()

    var githubClicked: Boolean
        get() = prefs.getBoolean("github_clicked", false)
        set(value) = prefs.edit().putBoolean("github_clicked", value).apply()

    var lastPromptedAuthCount: Int
        get() = prefs.getInt("last_prompted_auth_count", 0)
        set(value) = prefs.edit().putInt("last_prompted_auth_count", value).apply()

    var skippedUpdateVersion: String
        get() = prefs.getString("skipped_update_version", "") ?: ""
        set(value) = prefs.edit().putString("skipped_update_version", value).apply()

    var requireBiometricOnStart: Boolean
        get() = prefs.getBoolean("require_biometric_on_start", false)
        set(value) = prefs.edit().putBoolean("require_biometric_on_start", value).apply()

    var hasPromptedBiometricOnStart: Boolean
        get() = prefs.getBoolean("has_prompted_biometric_on_start", false)
        set(value) = prefs.edit().putBoolean("has_prompted_biometric_on_start", value).apply()

    var disableScreenshots: Boolean
        get() = prefs.getBoolean("disable_screenshots", false)
        set(value) = prefs.edit().putBoolean("disable_screenshots", value).apply()

    var enableServiceTimer: Boolean
        get() = prefs.getBoolean("enable_service_timer", false)
        set(value) = prefs.edit().putBoolean("enable_service_timer", value).apply()

    var timerStartHour: Int
        get() = prefs.getInt("timer_start_hour", 7)
        set(value) = prefs.edit().putInt("timer_start_hour", value).apply()

    var timerStartMinute: Int
        get() = prefs.getInt("timer_start_minute", 0)
        set(value) = prefs.edit().putInt("timer_start_minute", value).apply()

    var timerStopHour: Int
        get() = prefs.getInt("timer_stop_hour", 23)
        set(value) = prefs.edit().putInt("timer_stop_hour", value).apply()

    var timerStopMinute: Int
        get() = prefs.getInt("timer_stop_minute", 0)
        set(value) = prefs.edit().putInt("timer_stop_minute", value).apply()

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

    fun getGithubClickedFlow(): kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "github_clicked") {
                trySend(sharedPreferences.getBoolean("github_clicked", false))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.getBoolean("github_clicked", false))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun getRequireBiometricOnStartFlow(): kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "require_biometric_on_start") {
                trySend(sharedPreferences.getBoolean("require_biometric_on_start", false))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.getBoolean("require_biometric_on_start", false))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun getDisableScreenshotsFlow(): kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "disable_screenshots") {
                trySend(sharedPreferences.getBoolean("disable_screenshots", false))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.getBoolean("disable_screenshots", false))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
