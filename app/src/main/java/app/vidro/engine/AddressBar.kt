package app.vidro.engine

import java.net.URLEncoder

enum class SearchEngine(val id: String, val label: String, val template: String) {
    DUCKDUCKGO("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("google", "Google", "https://www.google.com/search?q=%s"),
    YANDEX("yandex", "Яндекс", "https://yandex.ru/search/?text=%s"),
    BRAVE("brave", "Brave", "https://search.brave.com/search?q=%s"),
    ;

    companion object {
        fun fromId(id: String?): SearchEngine = entries.firstOrNull { it.id == id } ?: DUCKDUCKGO
    }
}

object AddressBar {
    const val HOME = "vidro://home"

    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?$""")

    fun resolve(raw: String, engine: SearchEngine): String {
        val text = raw.trim()
        if (text.isEmpty()) return HOME
        val lower = text.lowercase()
        if (lower == HOME || lower == "about:blank") return HOME
        if (lower.startsWith("http://") || lower.startsWith("https://")) return text
        val beforeSlash = text.substringBefore('/').substringBefore('?').substringBefore('#')
        if (
            lower.startsWith("localhost") ||
            lower.startsWith("127.0.0.1") ||
            ipv4.matches(beforeSlash)
        ) {
            return "http://$text"
        }
        if (!text.any { it.isWhitespace() }) {
            val host = beforeSlash.substringBefore(':')
            if (
                host.contains('.') &&
                host.first().isLetterOrDigit() &&
                host.last().isLetter() &&
                host.none { it.isWhitespace() }
            ) {
                return "https://$text"
            }
        }
        val query = URLEncoder.encode(text, Charsets.UTF_8.name())
        return engine.template.replace("%s", query)
    }

    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val match = Regex(
            """^[a-z][a-z0-9+.-]*://(?:[^/?#@]+@)?(\[[^\]]+\]|[^/?#:@]+)""",
            RegexOption.IGNORE_CASE,
        ).find(url.trim()) ?: return null
        val host = match.groupValues[1].trim('.').lowercase()
        if (host.isEmpty()) return null
        return host
    }

    fun displayHost(url: String?): String {
        if (url.isNullOrBlank() || url == HOME || url.startsWith("vidro:")) return ""
        return hostOf(url)?.removePrefix("www.").orEmpty()
    }

    fun originOf(url: String?): String? {
        val host = hostOf(url) ?: return null
        val lower = url?.lowercase().orEmpty()
        val scheme = when {
            lower.startsWith("https://") -> "https"
            lower.startsWith("http://") -> "http"
            else -> return null
        }
        return "$scheme://$host"
    }
}
