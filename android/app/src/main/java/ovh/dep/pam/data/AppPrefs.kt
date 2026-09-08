package ovh.dep.pam.data

import android.content.Context
import android.content.SharedPreferences

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
}
