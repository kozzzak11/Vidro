package app.vidro.engine

/**
 * Decides whether a URL (and optional response Content-Type) is a direct
 * progressive, HLS, or DASH stream that ExoPlayer can open.
 * Segment files (.ts, .m4s) and blob/data URLs are ignored.
 */
object UrlSniffer {
    enum class Kind { PROGRESSIVE, HLS, DASH }

    data class Hit(val url: String, val kind: Kind)

    fun sniff(url: String?, contentType: String? = null): Hit? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        val lower = trimmed.lowercase()
        if (lower.startsWith("blob:") || lower.startsWith("data:") || lower.startsWith("javascript:")) {
            return null
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        if (isSegment(lower)) return null

        val ct = contentType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        when (ct) {
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "audio/mpegurl",
            "audio/x-mpegurl",
            -> return Hit(trimmed, Kind.HLS)
            "application/dash+xml" -> return Hit(trimmed, Kind.DASH)
            "video/mp4", "video/webm", "video/x-m4v", "video/quicktime" ->
                return Hit(trimmed, Kind.PROGRESSIVE)
        }

        val ext = extension(lower)
        when (ext) {
            "m3u8" -> return Hit(trimmed, Kind.HLS)
            "mpd" -> return Hit(trimmed, Kind.DASH)
            "mp4", "m4v", "webm", "mkv", "mov" -> return Hit(trimmed, Kind.PROGRESSIVE)
        }

        val query = lower.substringAfter('?', "")
        if ("format=m3u8" in query || "format=m3u" in query) return Hit(trimmed, Kind.HLS)
        if ("format=mpd" in query) return Hit(trimmed, Kind.DASH)
        if ("mime=video%2f" in query || "mime=video/" in query) return Hit(trimmed, Kind.PROGRESSIVE)
        if ("videoplayback" in lower && ("mime=video" in query || "itag=" in query)) {
            return Hit(trimmed, Kind.PROGRESSIVE)
        }
        return null
    }

    /** Higher is a better candidate when several URLs were seen on one page. */
    fun rank(url: String, kind: Kind): Int {
        val lower = url.lowercase()
        var score = when (kind) {
            Kind.HLS -> 300
            Kind.DASH -> 260
            Kind.PROGRESSIVE -> 120
        }
        if ("master" in lower || "playlist" in lower || "index.m3u8" in lower) score += 40
        if ("range=" in lower || "rn=" in lower || "sq=" in lower) score -= 70
        if ("initplayback" in lower) score -= 30
        if ("googlevideo.com" in lower && kind == Kind.PROGRESSIVE) score -= 20
        return score
    }

    private fun isSegment(lowerUrl: String): Boolean {
        val ext = extension(lowerUrl)
        return ext == "ts" || ext == "m4s" || ext == "aac" || ext == "vtt" || ext == "srt" || ext == "cmfv" || ext == "cmfa"
    }

    private fun extension(lowerUrl: String): String {
        val path = lowerUrl.substringBefore('?').substringBefore('#')
        val file = path.substringAfterLast('/')
        val dot = file.lastIndexOf('.')
        if (dot < 0 || dot == file.lastIndex) return ""
        return file.substring(dot + 1)
    }
}
