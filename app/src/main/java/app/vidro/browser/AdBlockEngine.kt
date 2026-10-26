package app.vidro.browser

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.vidro.engine.FilterMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AdBlockEngine(private val context: Context) {
    @Volatile var matcher: FilterMatcher = FilterMatcher.empty()
        private set

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating

    suspend fun load() = withContext(Dispatchers.Default) {
        val lines = readAll()
        matcher = FilterMatcher.compile(lines)
        _ready.value = true
    }

    suspend fun updateFromNetwork(wifiOnly: Boolean): UpdateResult = withContext(Dispatchers.IO) {
        if (wifiOnly && !onWifi()) return@withContext UpdateResult.NEEDS_WIFI
        _updating.value = true
        try {
            val dir = File(context.filesDir, "filters")
            dir.mkdirs()
            for (source in SOURCES) {
                val body = download(source.url) ?: return@withContext UpdateResult.FAILED
                if (!body.contains("||") && !body.contains("##")) return@withContext UpdateResult.FAILED
                val tmp = File(dir, source.file + ".tmp")
                tmp.writeText(body)
                val dest = File(dir, source.file)
                if (!tmp.renameTo(dest)) {
                    dest.writeText(body)
                    tmp.delete()
                }
            }
            withContext(Dispatchers.Default) {
                matcher = FilterMatcher.compile(readAll())
                _ready.value = true
            }
            UpdateResult.OK
        } catch (_: Exception) {
            UpdateResult.FAILED
        } finally {
            _updating.value = false
        }
    }

    private fun readAll(): List<String> {
        val lines = ArrayList<String>(140_000)
        val dir = File(context.filesDir, "filters")
        for (source in SOURCES) {
            val file = File(dir, source.file)
            val stream = if (file.exists() && file.length() > 0) file.inputStream()
            else context.assets.open("filters/${source.file}")
            stream.bufferedReader().useLines { seq ->
                seq.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed[0] == '!' || trimmed[0] == '[') return@forEach
                    lines.add(trimmed)
                }
            }
        }
        return lines
    }

    private fun download(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("User-Agent", "Vidro/0.1.0")
        }
        return try {
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun onWifi(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    enum class UpdateResult { OK, NEEDS_WIFI, FAILED }

    data class Source(val file: String, val url: String)

    companion object {
        val SOURCES = listOf(
            Source("easylist.txt", "https://easylist.to/easylist/easylist.txt"),
            Source("easyprivacy.txt", "https://easylist.to/easylist/easyprivacy.txt"),
            Source("ublock-filters.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt"),
            Source("ublock-privacy.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt"),
            Source("ublock-quick-fixes.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/quick-fixes.txt"),
            Source("ublock-unbreak.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt"),
        )
    }
}
