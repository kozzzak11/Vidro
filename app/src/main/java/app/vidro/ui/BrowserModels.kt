package app.vidro.ui

import app.vidro.data.Bookmark
import app.vidro.data.BlockStats
import app.vidro.data.HistoryEntry
import app.vidro.data.UserSettings

enum class Overlay { None, Tabs, Bookmarks, History, Settings }

data class SessionTraffic(val used: Long = 0, val saved: Long = 0)

data class TabSnapshot(
    val id: String,
    val title: String,
    val url: String,
    val host: String,
)

data class VideoChoice(
    val url: String,
    val kind: String,
    val label: String,
)

data class BrowserUiState(
    val restored: Boolean = false,
    val tabs: List<TabSnapshot> = emptyList(),
    val activeId: String = "",
    val webGeneration: Int = 0,
    val overlay: Overlay = Overlay.None,
    val home: Boolean = true,
    val url: String = "",
    val host: String = "",
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val pageBlocked: Int = 0,
    val pageBytes: Long = 0,
    val totalBlocked: Long = 0,
    val totalBytes: Long = 0,
    val videos: List<VideoChoice> = emptyList(),
    val bookmarked: Boolean = false,
    val settings: UserSettings = UserSettings(),
    val stats: BlockStats = BlockStats(),
    val adblockForSite: Boolean = true,
    val siteKey: String = "",
    val filtersReady: Boolean = false,
    val filterRules: Int = 0,
    val updatingFilters: Boolean = false,
    val bookmarks: List<Bookmark> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val error: String? = null,
    val toast: String? = null,
    val desktop: Boolean = false,
    val pendingVideoUrl: String? = null,
)
