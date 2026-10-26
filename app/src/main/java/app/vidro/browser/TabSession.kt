package app.vidro.browser

import android.content.MutableContextWrapper
import android.webkit.WebView
import app.vidro.engine.UrlSniffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class DetectedVideo(
    val url: String,
    val kind: UrlSniffer.Kind,
    val label: String = "",
    val height: Int = 0,
    val at: Long = System.currentTimeMillis(),
)

class TabSession(
    val id: String,
    var title: String,
    var url: String,
    var scrollY: Int = 0,
    var desktop: Boolean = false,
    var openedAsPopup: Boolean = false,
) {
    val pageUrl: AtomicReference<String> = AtomicReference(url)
    val videos: ConcurrentHashMap<String, DetectedVideo> = ConcurrentHashMap()
    val blocked: AtomicInteger = AtomicInteger()
    val savedBytes: AtomicLong = AtomicLong()
    var web: WebView? = null
    var wrapper: MutableContextWrapper? = null
    var error: String? = null
    var progress: Int = 0

    fun bestVideo(): DetectedVideo? =
        videos.values.maxWithOrNull(compareBy<DetectedVideo>({ UrlSniffer.rank(it.url, it.kind) }, { it.at }))

    fun remember(url: String, kind: UrlSniffer.Kind, label: String = "", height: Int = 0): Boolean {
        val existing = videos[url]
        val nextHeight = maxOf(existing?.height ?: 0, height)
        val nextLabel = when {
            label.isNotBlank() -> label
            nextHeight > 0 -> "${nextHeight}p"
            else -> existing?.label.orEmpty()
        }
        if (existing != null && existing.height == nextHeight && existing.label == nextLabel && existing.kind == kind) {
            return false
        }
        if (existing == null && videos.size >= 24) {
            videos.values.minByOrNull { it.at }?.url?.let { videos.remove(it) }
        }
        videos[url] = DetectedVideo(url, kind, nextLabel, nextHeight, existing?.at ?: System.currentTimeMillis())
        return true
    }
}

data class FoundSource(val url: String, val label: String = "", val height: Int = 0)
