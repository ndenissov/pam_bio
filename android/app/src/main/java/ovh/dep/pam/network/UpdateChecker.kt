package ovh.dep.pam.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import ovh.dep.pam.BuildConfig

@Serializable
data class GithubRelease(
    val tag_name: String,
    val html_url: String,
    val body: String = ""
)

object UpdateChecker {
    private const val REPO_LATEST_RELEASE_URL = "https://api.github.com/repos/ndenissov/pam_bio/releases/latest"
    private const val TAG = "UpdateChecker"

    // Ignore unknown keys to prevent crashes if GitHub adds fields
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getLatestRelease(): GithubRelease? = withContext(Dispatchers.IO) {
        try {
            val url = URL(REPO_LATEST_RELEASE_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                return@withContext json.decodeFromString<GithubRelease>(response)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
        }
        null
    }

    fun isNewVersionAvailable(latestVersionTag: String, currentVersion: String = BuildConfig.VERSION_NAME): Boolean {
        // Remove 'v' prefix if present
        val remoteVersion = latestVersionTag.removePrefix("v")
        val localVersion = currentVersion.removePrefix("v")

        // Split by dots and compare numbers
        val remoteParts = remoteVersion.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = localVersion.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
