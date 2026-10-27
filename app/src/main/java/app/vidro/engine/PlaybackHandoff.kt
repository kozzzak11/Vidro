package app.vidro.engine

/**
 * Live playback position for one fullscreen handoff. Not stored anywhere.
 * The page video reports milliseconds and whether it was playing.
 */
data class PlaybackHandoff(
    val positionMs: Long = 0L,
    val playing: Boolean = true,
) {
    companion object {
        fun parse(raw: String?): PlaybackHandoff {
            val text = raw
                ?.trim()
                ?.removePrefix("\"")
                ?.removeSuffix("\"")
                ?.trim()
                .orEmpty()
            if (text.isEmpty() || text == "null") return PlaybackHandoff()
            val parts = text.split('|')
            val ms = parts.getOrNull(0)?.toLongOrNull()?.coerceIn(0L, 24 * 60 * 60 * 1000L) ?: 0L
            val playing = parts.getOrNull(1)?.trim() != "0"
            return PlaybackHandoff(ms, playing)
        }
    }
}
