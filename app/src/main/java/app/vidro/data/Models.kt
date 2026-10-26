package app.vidro.data

import app.vidro.engine.SearchEngine

data class UserSettings(
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val adBlockEnabled: Boolean = true,
    val blockAutoplay: Boolean = true,
    val blockImages: Boolean = false,
    val autoOpenPlayer: Boolean = false,
    val openOnFullscreen: Boolean = true,
    val blockPopups: Boolean = true,
    val wifiOnlyFilterUpdates: Boolean = true,
    val disabledSites: Set<String> = emptySet(),
)

data class BlockStats(
    val totalBlocked: Long = 0,
    val totalBytes: Long = 0,
    val filtersUpdatedAt: Long = 0,
)

data class Bookmark(
    val id: Long,
    val title: String,
    val url: String,
    val created: Long,
)

data class HistoryEntry(
    val id: Long,
    val title: String,
    val url: String,
    val visited: Long,
)

data class TabRecord(
    val id: String,
    val title: String,
    val url: String,
    val position: Int,
    val scrollY: Int,
)
