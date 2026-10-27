package app.vidro.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.vidro.VidroApp
import app.vidro.browser.AdBlockEngine
import app.vidro.browser.BrowserHost
import app.vidro.browser.DetectedVideo
import app.vidro.browser.FoundSource
import app.vidro.browser.TabSession
import app.vidro.browser.applyWebSettings
import app.vidro.browser.createTabWebView
import app.vidro.browser.isYouTubeHost
import app.vidro.data.BlockStats
import app.vidro.data.Bookmark
import app.vidro.data.HistoryEntry
import app.vidro.data.TabRecord
import app.vidro.data.UserSettings
import app.vidro.engine.AddressBar
import app.vidro.engine.ExternalLinks
import app.vidro.engine.FilterMatcher
import app.vidro.engine.PlaybackHandoff
import app.vidro.engine.Request
import app.vidro.engine.ResourceType
import app.vidro.engine.Scripts
import app.vidro.engine.SearchEngine
import app.vidro.engine.UrlSniffer
import app.vidro.net.isMobileData
import app.vidro.player.PlayerActivity
import app.vidro.player.PlayerContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class BrowserViewModel(app: Application) : AndroidViewModel(app), BrowserHost {
    private val vidro = app as VidroApp
    private val db = vidro.db
    private val settingsStore = vidro.settings
    override val engine: AdBlockEngine = vidro.engine

    private var currentSettings = UserSettings()
    override val settings: UserSettings get() = currentSettings

    private val tabs = ArrayList<TabSession>()
    private var activeId: String = ""
    private var overlay = Overlay.None
    private var restored = false
    private var webGeneration = 0
    private var bookmarks = emptyList<Bookmark>()
    private var bookmarkUrls = emptySet<String>()
    private var history = emptyList<HistoryEntry>()
    private var stats = BlockStats()
    private var liveBlocked = 0L
    private var liveBytes = 0L
    private var toast: String? = null
    private var pendingVideoUrl: String? = null
    private var playerOpen = false
    private var handoffMs = 0L
    private var handoffPlaying = true
    private var handoffToken = 0
    private var pendingRestore: PlaybackHandoff? = null
    private var pageResumed = true
    private var lastAutoUrl: String? = null
    private var lastAutoAt = 0L
    private var saveJob: Job? = null
    private var publishScheduled = false
    private val trafficScheduled = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingOpen: String? = null
    private val sessionUsed = AtomicLong()
    private val sessionSaved = AtomicLong()

    var fileChooser: ((ValueCallback<Array<Uri>>) -> Unit)? = null
    var showFullscreen: ((View, WebChromeClient.CustomViewCallback) -> Unit)? = null
    var hideFullscreen: (() -> Unit)? = null

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()
    private val _loadProgress = MutableStateFlow(0)
    val loadProgress: StateFlow<Int> = _loadProgress.asStateFlow()
    private val _traffic = MutableStateFlow(SessionTraffic())
    val traffic: StateFlow<SessionTraffic> = _traffic.asStateFlow()

    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() {
            main.post { trimBackground() }
        }
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                main.post { trimBackground() }
            }
        }
    }

    init {
        getApplication<Application>().registerComponentCallbacks(trimCallbacks)
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { db.loadTabs() }
            currentSettings = settingsStore.snapshot()
            bookmarks = withContext(Dispatchers.IO) { db.bookmarks() }
            bookmarkUrls = bookmarks.map { it.url }.toSet()
            if (saved.isEmpty()) {
                val tab = newSession(AddressBar.HOME, "Vidro")
                tabs += tab
                activeId = tab.id
                ensureWeb(tab)
            } else {
                saved.forEach { record ->
                    val tab = newSession(record.url, record.title.ifBlank { "Vidro" }, record.id)
                    tab.scrollY = record.scrollY
                    tabs += tab
                }
                activeId = tabs.first().id
                val tab = tabs.first()
                ensureWeb(tab)
                restoreOrLoad(tab)
            }
            restored = true
            publish()
            consumeOpen()
        }
        viewModelScope.launch {
            settingsStore.settings.collect { value ->
                currentSettings = value
                tabs.forEach { tab -> tab.web?.let { applyWebSettings(it, value, tab.desktop) } }
                publish()
            }
        }
        viewModelScope.launch {
            settingsStore.stats.collect { value ->
                stats = value
                if (liveBlocked < value.totalBlocked) liveBlocked = value.totalBlocked
                if (liveBytes < value.totalBytes) liveBytes = value.totalBytes
                publish()
            }
        }
        viewModelScope.launch {
            engine.ready.collect { ready ->
                if (ready) reinjectActive()
                publish()
            }
        }
    }

    fun webView(): WebView? = active()?.web

    fun attach(activity: Activity) {
        pageResumed = true
        tabs.forEach { it.wrapper?.baseContext = activity }
        active()?.web?.onResume()
        applyPendingRestore()
    }

    fun detach(app: Application) {
        pageResumed = false
        active()?.let { tab ->
            tab.web?.let { tab.scrollY = it.scrollY }
            saveWebState(tab)
            tab.web?.onPause()
        }
        CookieManager.getInstance().flush()
        tabs.forEach { it.wrapper?.baseContext = app }
        flushStats()
        scheduleSave()
    }

    fun commitAddress(text: String) {
        load(AddressBar.resolve(text, currentSettings.searchEngine))
    }

    fun load(url: String) {
        val tab = active() ?: return
        overlay = Overlay.None
        tab.error = null
        if (!url.startsWith("http")) {
            tab.url = AddressBar.HOME
            tab.pageUrl.set(AddressBar.HOME)
            tab.title = "Vidro"
            tab.videos.clear()
            tab.blocked.set(0)
            tab.savedBytes.set(0)
            publish()
            scheduleSave()
            return
        }
        ensureWeb(tab)
        tab.web?.loadUrl(url)
        publish()
    }

    fun goBack() {
        active()?.web?.takeIf { it.canGoBack() }?.goBack()
    }

    fun goForward() {
        active()?.web?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload() {
        val tab = active() ?: return
        if (tab.pageUrl.get().startsWith("http")) tab.web?.reload() else publish()
    }

    fun newTab() {
        if (tabs.size >= MAX_TABS) {
            toast = "Слишком много вкладок"
            publish()
            return
        }
        val tab = newSession(AddressBar.HOME, "Vidro")
        tabs += tab
        focus(tab.id)
        overlay = Overlay.None
        scheduleSave()
        publish()
    }

    fun selectTab(id: String) {
        focus(id)
        overlay = Overlay.None
        publish()
    }

    fun closeTab(id: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        destroyWeb(tabs.removeAt(index))
        if (tabs.isEmpty()) {
            val fresh = newSession(AddressBar.HOME, "Vidro")
            tabs += fresh
            activeId = ""
            focus(fresh.id)
        } else if (activeId == id) {
            focus(tabs[index.coerceAtMost(tabs.lastIndex)].id)
        }
        scheduleSave()
        publish()
    }

    fun show(overlay: Overlay) {
        this.overlay = overlay
        if (overlay == Overlay.Bookmarks || overlay == Overlay.History || overlay == Overlay.None) {
            viewModelScope.launch {
                if (overlay == Overlay.Bookmarks || overlay == Overlay.None) {
                    bookmarks = withContext(Dispatchers.IO) { db.bookmarks() }
                    bookmarkUrls = bookmarks.map { it.url }.toSet()
                }
                if (overlay == Overlay.History) history = withContext(Dispatchers.IO) { db.history() }
                publish()
            }
        }
        publish()
    }

    fun dismissOverlay() {
        overlay = Overlay.None
        publish()
    }

    fun toggleBookmark() {
        val tab = active() ?: return
        val url = tab.pageUrl.get()
        if (!url.startsWith("http")) return
        viewModelScope.launch {
            val exists = withContext(Dispatchers.IO) { db.isBookmarked(url) }
            withContext(Dispatchers.IO) {
                if (exists) db.removeBookmark(url) else db.addBookmark(tab.title, url)
            }
            bookmarks = withContext(Dispatchers.IO) { db.bookmarks() }
            bookmarkUrls = bookmarks.map { it.url }.toSet()
            toast = if (exists) "Закладка удалена" else "В закладках"
            publish()
        }
    }

    fun deleteBookmark(url: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.removeBookmark(url) }
            bookmarks = withContext(Dispatchers.IO) { db.bookmarks() }
            bookmarkUrls = bookmarks.map { it.url }.toSet()
            publish()
        }
    }

    fun deleteHistory(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.removeHistory(id) }
            history = withContext(Dispatchers.IO) { db.history() }
            publish()
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.clearHistory() }
            history = emptyList()
            publish()
        }
    }

    fun clearStats() {
        liveBlocked = 0
        liveBytes = 0
        viewModelScope.launch {
            settingsStore.clearStats()
            publish()
        }
    }

    fun toggleDesktop() {
        val tab = active() ?: return
        tab.desktop = !tab.desktop
        tab.web?.let {
            applyWebSettings(it, currentSettings, tab.desktop)
            if (tab.pageUrl.get().startsWith("http")) it.reload()
        }
        publish()
    }

    fun toggleSiteAdblock() {
        val tab = active() ?: return
        val key = siteKey(tab.pageUrl.get())
        if (key.isEmpty()) return
        val disable = siteAllows(tab.pageUrl.get())
        viewModelScope.launch {
            settingsStore.setSiteDisabled(key, disable)
            currentSettings = currentSettings.copy(
                disabledSites = if (disable) currentSettings.disabledSites + key else currentSettings.disabledSites - key,
            )
            tab.web?.reload()
            publish()
        }
    }

    fun setSearchEngine(engine: SearchEngine) {
        viewModelScope.launch { settingsStore.setSearchEngine(engine) }
    }

    fun setAdBlock(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAdBlock(enabled)
            active()?.web?.reload()
        }
    }

    fun setBlockAutoplay(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setBlockAutoplay(enabled) }
    }

    fun setBlockImages(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setBlockImages(enabled)
            currentSettings = currentSettings.copy(blockImages = enabled)
            tabs.forEach { tab -> tab.web?.let { applyWebSettings(it, currentSettings, tab.desktop) } }
            active()?.web?.takeIf { tabUrl(active()).startsWith("http") }?.reload()
        }
    }

    fun setAutoOpen(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoOpenPlayer(enabled) }
    }

    fun setOpenOnFullscreen(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setOpenOnFullscreen(enabled) }
    }

    fun setBlockPopups(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setBlockPopups(enabled) }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setWifiOnly(enabled) }
    }

    fun setCapMobileQuality(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setCapMobileQuality(enabled) }
    }

    fun openFromIntent(intent: Intent?) {
        val source = intent ?: return
        val url = ExternalLinks.urlFrom(source) ?: return
        source.action = null
        source.data = null
        source.removeExtra(Intent.EXTRA_TEXT)
        pendingOpen = url
        if (restored) consumeOpen()
    }

    fun updateFilters() {
        viewModelScope.launch {
            publish()
            when (engine.updateFromNetwork(currentSettings.wifiOnlyFilterUpdates)) {
                AdBlockEngine.UpdateResult.OK -> {
                    settingsStore.setFiltersUpdated(System.currentTimeMillis())
                    toast = "Списки обновлены"
                    reinjectActive()
                }
                AdBlockEngine.UpdateResult.NEEDS_WIFI -> toast = "Для обновления нужен Wi‑Fi"
                AdBlockEngine.UpdateResult.FAILED -> toast = "Не удалось обновить списки"
            }
            publish()
        }
    }

    fun consumeToast() {
        toast = null
    }

    fun onPlayerClosed(result: Intent?) {
        playerOpen = false
        handoffToken = 0
        if (result != null && result.hasExtra(PlayerContract.EXTRA_POSITION_MS)) {
            pendingRestore = PlaybackHandoff(
                positionMs = result.getLongExtra(PlayerContract.EXTRA_POSITION_MS, 0L).coerceAtLeast(0L),
                playing = result.getBooleanExtra(PlayerContract.EXTRA_PLAYING, true),
            )
        }
        if (pageResumed) applyPendingRestore()
        publish()
    }

    fun consumePlayerIntent(context: android.content.Context): Intent? {
        val url = pendingVideoUrl ?: return null
        pendingVideoUrl = null
        val tab = active()
        val video = tab?.videos?.get(url) ?: url.let { UrlSniffer.sniff(it, null)?.let { hit -> DetectedVideo(hit.url, hit.kind) } }
        if (tab == null || video == null) {
            playerOpen = false
            handoffMs = 0L
            handoffPlaying = true
            publish()
            return null
        }
        playerOpen = true
        publish()
        return intentFor(context, tab, video)
    }

    fun play(url: String? = null) {
        if (playerOpen || pendingVideoUrl != null) return
        val tab = active() ?: return
        val video = if (url == null) {
            tab.bestVideo()
        } else {
            tab.videos[url] ?: UrlSniffer.sniff(url, null)?.let { DetectedVideo(url, it.kind) }
        }
        if (video == null) return
        playerOpen = true
        queueHandoff(video.url, null)
    }

    private var screensPrepared = false

    fun prepareScreens(screen: String?) {
        if (!restored || screen.isNullOrBlank()) return
        viewModelScope.launch {
            if (!screensPrepared) {
                screensPrepared = true
                if (bookmarks.isEmpty()) {
                    withContext(Dispatchers.IO) {
                        db.addBookmark("DuckDuckGo", "https://duckduckgo.com/")
                        db.addBookmark("Википедия", "https://ru.wikipedia.org/")
                    }
                    bookmarks = withContext(Dispatchers.IO) { db.bookmarks() }
                    bookmarkUrls = bookmarks.map { it.url }.toSet()
                }
                if (history.isEmpty()) {
                    withContext(Dispatchers.IO) {
                        db.addHistory("DuckDuckGo", "https://duckduckgo.com/")
                        db.addHistory("Википедия", "https://ru.wikipedia.org/")
                    }
                    history = withContext(Dispatchers.IO) { db.history() }
                }
                if (tabs.size < 2) {
                    val extra = newSession(AddressBar.HOME, "Новая вкладка")
                    tabs += extra
                }
            }
            when (screen) {
                "tabs" -> overlay = Overlay.Tabs
                "bookmarks" -> overlay = Overlay.Bookmarks
                "history" -> overlay = Overlay.History
                "settings" -> overlay = Overlay.Settings
                "home" -> {
                    overlay = Overlay.None
                    load(AddressBar.HOME)
                    return@launch
                }
            }
            publish()
        }
    }

    override fun siteAllowsAdblock(url: String): Boolean = siteAllows(url)

    override fun onPageStarted(tab: TabSession, url: String) {
        tab.pageUrl.set(url)
        tab.url = url
        tab.error = null
        tab.progress = 12
        if (tab.id == activeId) _loadProgress.value = 12
        tab.blocked.set(0)
        tab.savedBytes.set(0)
        tab.videos.clear()
        if (tab.openedAsPopup && siteAllows(url) && engine.ready.value) {
            val blocked = engine.matcher.shouldBlock(Request(url, null, ResourceType.POPUP, true))
            if (blocked) {
                liveBlocked += 1
                liveBytes += FilterMatcher.estimatedBytes(ResourceType.POPUP)
                closePopup(tab)
                toast = "Всплывающее окно заблокировано"
                return
            }
        }
        scheduleSave()
        injectCosmetic(tab, url, includeBootstrap = true)
        publish()
    }

    override fun onPageFinished(tab: TabSession, url: String) {
        tab.progress = 100
        if (tab.id == activeId) _loadProgress.value = 0
        tab.error = null
        tab.url = url
        tab.pageUrl.set(url)
        if (url.startsWith("http")) {
            viewModelScope.launch(Dispatchers.IO) {
                db.addHistory(tab.title, url)
                if (overlay == Overlay.History) {
                    val items = db.history()
                    withContext(Dispatchers.Main) {
                        history = items
                        publish()
                    }
                }
            }
        }
        flushStats()
        scheduleSave()
        publish()
    }

    override fun onTitle(tab: TabSession, title: String) {
        if (title.startsWith("http") || title == "about:blank") return
        tab.title = title
        scheduleSave()
        publish()
    }

    override fun onProgress(tab: TabSession, progress: Int) {
        tab.progress = progress
        if (tab.id == activeId) _loadProgress.value = progress.coerceIn(0, 100)
    }

    override fun onVideos(tab: TabSession, sources: List<FoundSource>) {
        var changed = false
        sources.forEach { source ->
            val hit = UrlSniffer.sniff(source.url, null) ?: return@forEach
            val height = source.height.takeIf { it > 0 } ?: UrlSniffer.guessHeight(source.label, source.url)
            if (tab.remember(hit.url, hit.kind, source.label, height)) changed = true
        }
        if (changed && tab.id == activeId) requestPublish()
    }

    override fun onLongPressVideo(tab: TabSession, url: String) {
        if (tab.id != activeId || playerOpen) return
        val hit = UrlSniffer.sniff(url, null)
        if (hit == null) {
            toast = "Этот поток остаётся на странице"
            publish()
            return
        }
        val height = UrlSniffer.guessHeight(null, hit.url)
        tab.remember(hit.url, hit.kind, if (height > 0) "${height}p" else "", height)
        playerOpen = true
        queueHandoff(hit.url, null)
    }

    override fun onPlaying(tab: TabSession, url: String) {
        val hit = UrlSniffer.sniff(url, null) ?: return
        tab.remember(hit.url, hit.kind)
        if (tab.id != activeId) return
        publish()
        if (!currentSettings.autoOpenPlayer || playerOpen || pendingVideoUrl != null) return
        val now = System.currentTimeMillis()
        if (url == lastAutoUrl && now - lastAutoAt < 45_000) return
        lastAutoUrl = url
        lastAutoAt = now
        playerOpen = true
        queueHandoff(hit.url, null)
    }

    override fun onNavigate(tab: TabSession, url: String) {
        if (!url.startsWith("http")) return
        val previous = AddressBar.hostOf(tab.pageUrl.get())
        val next = AddressBar.hostOf(url)
        tab.pageUrl.set(url)
        tab.url = url
        if (previous != next) {
            tab.videos.clear()
            tab.blocked.set(0)
            tab.savedBytes.set(0)
        }
        injectCosmetic(tab, url, includeBootstrap = false)
        publish()
    }

    override fun onBlocked(tab: TabSession, type: ResourceType) {
        val bytes = FilterMatcher.estimatedBytes(type)
        tab.blocked.incrementAndGet()
        tab.savedBytes.addAndGet(bytes)
        liveBlocked += 1
        liveBytes += bytes
        noteTraffic(used = 0L, saved = bytes)
    }

    override fun onFetched(tab: TabSession, type: ResourceType) {
        if (tab.id != activeId) return
        noteTraffic(used = FilterMatcher.estimatedBytes(type), saved = 0L)
    }

    override fun onDirectVideo(tab: TabSession, url: String) {
        val hit = UrlSniffer.sniff(url, null) ?: return
        val before = tab.videos.size
        tab.remember(hit.url, hit.kind)
        if (tab.videos.size != before && tab.id == activeId) requestPublish()
    }

    override fun allowNewWindow(isDialog: Boolean, isUserGesture: Boolean): Boolean {
        if (!currentSettings.blockPopups) return true
        return isUserGesture && !isDialog
    }

    override fun openPopupTab(): WebView? {
        if (tabs.size >= MAX_TABS) {
            toast = "Слишком много вкладок"
            publish()
            return null
        }
        val tab = newSession("about:blank", "…")
        tab.openedAsPopup = true
        tabs += tab
        val web = ensureWeb(tab)
        focus(tab.id)
        overlay = Overlay.None
        publish()
        return web
    }

    override fun closePopup(tab: TabSession) {
        main.post { closeTab(tab.id) }
    }

    override fun onPageError(tab: TabSession, message: String?) {
        tab.error = message
        tab.progress = 0
        if (tab.id == activeId) {
            _loadProgress.value = 0
            publish()
        }
    }

    override fun onRenderProcessGone(tab: TabSession) {
        main.post {
            val url = tab.pageUrl.get()
            destroyWeb(tab, deleteState = true)
            webGeneration++
            if (tab.id != activeId) {
                publish()
                return@post
            }
            ensureWeb(tab)
            if (url.startsWith("http")) tab.web?.loadUrl(url)
            publish()
        }
    }

    override fun chooseFile(callback: ValueCallback<Array<Uri>>) {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        val launcher = fileChooser
        if (launcher == null) {
            fileCallback = null
            callback.onReceiveValue(null)
        } else {
            launcher.invoke(callback)
        }
    }

    fun onFileChosen(uri: Uri?) {
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    override fun showWebFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        showFullscreen?.invoke(view, callback) ?: callback.onCustomViewHidden()
    }

    override fun hideWebFullscreen() {
        hideFullscreen?.invoke()
    }

    override fun takeNativeFullscreen(tab: TabSession, hidePageFullscreen: () -> Unit): Boolean {
        if (!currentSettings.openOnFullscreen || playerOpen || pendingVideoUrl != null) return false
        if (tab.id != activeId) return false
        val video = tab.bestVideo() ?: return false
        playerOpen = true
        queueHandoff(video.url, hidePageFullscreen)
        return true
    }

    override fun onDownload() {
        toast = "Загрузки отключены"
        publish()
    }

    private fun intentFor(context: android.content.Context, tab: TabSession, video: DetectedVideo): Intent {
        tab.web?.evaluateJavascript(Scripts.PAUSE_MEDIA, null)
        tab.web?.evaluateJavascript("(function(){return window.scrollY||0})()") { raw ->
            tab.scrollY = raw?.trim()?.removeSurrounding("\"")?.toIntOrNull() ?: tab.scrollY
        }
        val referer = tab.pageUrl.get()
        val cookie = runCatching { CookieManager.getInstance().getCookie(video.url) }.getOrNull()
        val ua = tab.web?.settings?.userAgentString ?: WebSettings.getDefaultUserAgent(context)
        return Intent(context, PlayerActivity::class.java).apply {
            putExtra(PlayerContract.EXTRA_URL, video.url)
            putExtra(PlayerContract.EXTRA_KIND, video.kind.name)
            putExtra(PlayerContract.EXTRA_TITLE, tab.title)
            putExtra(PlayerContract.EXTRA_REFERER, referer)
            putExtra(PlayerContract.EXTRA_ORIGIN, AddressBar.originOf(referer))
            putExtra(PlayerContract.EXTRA_COOKIE, cookie)
            putExtra(PlayerContract.EXTRA_UA, ua)
            putExtra(PlayerContract.EXTRA_VARIANTS, variantsJson(tab))
            val cap = if (currentSettings.capMobileQuality && context.isMobileData()) 480 else 0
            putExtra(PlayerContract.EXTRA_MAX_HEIGHT, cap)
            putExtra(PlayerContract.EXTRA_POSITION_MS, handoffMs.coerceAtLeast(0L))
            putExtra(PlayerContract.EXTRA_PLAYING, handoffPlaying)
            handoffMs = 0L
            handoffPlaying = true
        }
    }

    /**
     * Reads the live page position before the player opens. [afterCapture] runs once,
     * after the read, so a site fullscreen exit cannot zero currentTime first.
     * Nothing here is written to disk.
     */
    private fun queueHandoff(url: String, afterCapture: (() -> Unit)?) {
        val token = ++handoffToken
        var delivered = false
        fun finish(raw: String?) {
            if (delivered || token != handoffToken) return
            delivered = true
            handoffToken = 0
            val handoff = PlaybackHandoff.parse(raw)
            handoffMs = handoff.positionMs
            handoffPlaying = handoff.playing
            pendingVideoUrl = url
            runCatching { afterCapture?.invoke() }
            publish()
        }
        val web = active()?.web
        if (web == null) {
            finish(null)
            return
        }
        web.evaluateJavascript(Scripts.capturePlayback()) { raw ->
            main.post { finish(raw) }
        }
        main.postDelayed({
            finish(null)
        }, 700)
    }

    private fun applyPendingRestore() {
        val restore = pendingRestore ?: return
        if (!pageResumed) return
        val web = active()?.web ?: return
        web.onResume()
        web.evaluateJavascript(Scripts.applyPlayback(restore.positionMs, restore.playing), null)
        pendingRestore = null
    }

    private fun variantsJson(tab: TabSession): String {
        val array = JSONArray()
        tab.videos.values
            .sortedByDescending { UrlSniffer.rank(it.url, it.kind) }
            .take(24)
            .forEach { video ->
                val height = video.height.takeIf { it > 0 } ?: UrlSniffer.guessHeight(video.label, video.url)
                array.put(
                    JSONObject()
                        .put("u", video.url)
                        .put("l", labelFor(video))
                        .put("h", height)
                        .put("k", video.kind.name),
                )
            }
        return array.toString()
    }

    private fun reinjectActive() {
        val tab = active() ?: return
        val url = tab.pageUrl.get()
        if (!url.startsWith("http") || tab.web == null) return
        injectCosmetic(tab, url, includeBootstrap = true)
    }

    private fun injectCosmetic(tab: TabSession, url: String, includeBootstrap: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            val allow = siteAllows(url)
            val chunks = if (allow) engine.matcher.cosmeticChunks(url) else emptyList()
            val youtube = isYouTubeHost(url) && allow
            withContext(Dispatchers.Main) {
                val web = tab.web ?: return@withContext
                if (!url.startsWith("http")) return@withContext
                if (includeBootstrap) {
                    web.evaluateJavascript(Scripts.video, null)
                    if (youtube) web.evaluateJavascript(Scripts.youtube, null)
                } else {
                    web.evaluateJavascript("window.__vidroCollect&&window.__vidroCollect();", null)
                }
                if (chunks.isNotEmpty()) web.evaluateJavascript(Scripts.cosmeticCall(chunks), null)
            }
        }
    }

    private fun siteAllows(url: String?): Boolean {
        if (!currentSettings.adBlockEnabled) return false
        val key = siteKey(url ?: return true)
        if (key.isEmpty()) return true
        return currentSettings.disabledSites.none { key == it || key.endsWith(".$it") }
    }

    private fun siteKey(url: String?): String = AddressBar.hostOf(url)?.removePrefix("www.").orEmpty()

    private fun newSession(url: String, title: String, id: String = UUID.randomUUID().toString()) = TabSession(
        id = id,
        title = title,
        url = url,
    )

    private fun ensureWeb(tab: TabSession): WebView {
        tab.web?.let { return it }
        webGeneration++
        return createTabWebView(tab, getApplication(), this)
    }

    private fun focus(id: String) {
        val previous = tabs.firstOrNull { it.id == activeId }
        if (previous != null && previous.id != id) park(previous)
        activeId = id
        val tab = tabs.firstOrNull { it.id == id } ?: return
        val fresh = tab.web == null
        val web = ensureWeb(tab)
        web.onResume()
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        if (fresh) restoreOrLoad(tab)
    }

    private fun park(tab: TabSession) {
        val web = tab.web ?: return
        tab.scrollY = web.scrollY
        saveWebState(tab)
        web.onPause()
        destroyWeb(tab, deleteState = false)
    }

    private fun restoreOrLoad(tab: TabSession) {
        val web = tab.web ?: return
        val bundle = readWebState(tab.id)
        val restoredState = bundle != null && runCatching { web.restoreState(bundle) }.getOrNull() != null
        if (!restoredState) {
            val url = tab.pageUrl.get()
            if (url.startsWith("http")) web.loadUrl(url)
        }
    }

    private fun stateFile(id: String): File {
        val dir = File(getApplication<Application>().filesDir, "webstate")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, id)
    }

    private fun saveWebState(tab: TabSession) {
        val web = tab.web ?: return
        val bundle = Bundle()
        val history = web.saveState(bundle)
        if (history == null || history.size == 0) return
        val parcel = Parcel.obtain()
        try {
            bundle.writeToParcel(parcel, 0)
            stateFile(tab.id).writeBytes(parcel.marshall())
        } catch (_: Throwable) {
        } finally {
            parcel.recycle()
        }
    }

    private fun readWebState(id: String): Bundle? {
        val file = stateFile(id)
        if (!file.exists() || file.length() <= 0L || file.length() > 8_000_000L) return null
        val parcel = Parcel.obtain()
        return try {
            val bytes = file.readBytes()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel).also { it.classLoader = javaClass.classLoader }
        } catch (_: Throwable) {
            null
        } finally {
            parcel.recycle()
        }
    }

    private fun consumeOpen() {
        val url = pendingOpen ?: return
        pendingOpen = null
        load(url)
    }

    private fun trimBackground() {
        tabs.filter { it.id != activeId && it.web != null }.forEach(::park)
        runCatching { CookieManager.getInstance().flush() }
    }

    private fun noteTraffic(used: Long, saved: Long) {
        if (used != 0L) sessionUsed.addAndGet(used)
        if (saved != 0L) sessionSaved.addAndGet(saved)
        if (!trafficScheduled.compareAndSet(false, true)) return
        main.postDelayed({
            trafficScheduled.set(false)
            _traffic.value = SessionTraffic(sessionUsed.get(), sessionSaved.get())
        }, 400)
    }

    private fun active(): TabSession? = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()

    private fun tabUrl(tab: TabSession?): String = tab?.pageUrl?.get().orEmpty()

    private fun destroyWeb(tab: TabSession, deleteState: Boolean = true) {
        val web = tab.web ?: run {
            if (deleteState) stateFile(tab.id).delete()
            return
        }
        tab.web = null
        runCatching {
            web.stopLoading()
            web.webChromeClient = null
            web.webViewClient = WebViewClient()
            (web.parent as? ViewGroup)?.removeView(web)
            web.removeAllViews()
            web.destroy()
        }
        if (deleteState) stateFile(tab.id).delete()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(350)
            val records = tabs.mapIndexed { index, tab ->
                TabRecord(tab.id, tab.title, tab.pageUrl.get(), index, tab.scrollY)
            }
            withContext(Dispatchers.IO) { db.saveTabs(records) }
        }
    }

    private fun flushStats() {
        val blocked = liveBlocked
        val bytes = liveBytes
        viewModelScope.launch {
            val stored = stats
            val addCount = (blocked - stored.totalBlocked).coerceAtLeast(0)
            val addBytes = (bytes - stored.totalBytes).coerceAtLeast(0)
            if (addCount > 0 || addBytes > 0) settingsStore.addBlocked(addCount, addBytes)
        }
    }

    private fun requestPublish() {
        if (publishScheduled) return
        publishScheduled = true
        main.postDelayed({
            publishScheduled = false
            publish()
        }, 160)
    }

    private fun publish() {
        val tab = active()
        val url = tab?.pageUrl?.get() ?: AddressBar.HOME
        val home = !url.startsWith("http")
        _state.value = BrowserUiState(
            restored = restored,
            tabs = tabs.map {
                TabSnapshot(
                    id = it.id,
                    title = it.title.ifBlank { AddressBar.displayHost(it.pageUrl.get()).ifBlank { "Vidro" } },
                    url = it.pageUrl.get(),
                    host = AddressBar.displayHost(it.pageUrl.get()),
                )
            },
            activeId = tab?.id.orEmpty(),
            webGeneration = webGeneration,
            overlay = overlay,
            home = home,
            url = if (home) "" else url,
            host = AddressBar.displayHost(url),
            progress = if (home) 0 else tab?.progress ?: 0,
            canGoBack = tab?.web?.canGoBack() == true,
            canGoForward = tab?.web?.canGoForward() == true,
            pageBlocked = tab?.blocked?.get() ?: 0,
            pageBytes = tab?.savedBytes?.get() ?: 0L,
            totalBlocked = liveBlocked,
            totalBytes = liveBytes,
            videos = tab?.videos?.values
                ?.sortedByDescending { UrlSniffer.rank(it.url, it.kind) }
                ?.map { VideoChoice(it.url, it.kind.name, labelFor(it)) }
                .orEmpty(),
            bookmarked = url in bookmarkUrls,
            settings = currentSettings,
            stats = stats,
            adblockForSite = siteAllows(url),
            siteKey = siteKey(url),
            filtersReady = engine.ready.value,
            filterRules = engine.matcher.stats.blockedDomains + engine.matcher.stats.networkRules,
            updatingFilters = engine.updating.value,
            bookmarks = bookmarks,
            history = history,
            error = if (home) null else tab?.error,
            toast = toast,
            desktop = tab?.desktop == true,
            pendingVideoUrl = pendingVideoUrl,
        )
    }

    private fun labelFor(video: DetectedVideo): String {
        if (video.label.isNotBlank() && !video.label.startsWith("http")) return video.label
        if (video.height > 0) {
            val kind = kindLabel(video.kind)
            return "$kind · ${video.height}p"
        }
        val host = AddressBar.displayHost(video.url)
        val kind = kindLabel(video.kind)
        return if (host.isBlank()) kind else "$kind · $host"
    }

    private fun kindLabel(kind: UrlSniffer.Kind): String = when (kind) {
        UrlSniffer.Kind.HLS -> "HLS"
        UrlSniffer.Kind.DASH -> "DASH"
        UrlSniffer.Kind.PROGRESSIVE -> "MP4"
    }

    override fun onCleared() {
        getApplication<Application>().unregisterComponentCallbacks(trimCallbacks)
        tabs.toList().forEach { destroyWeb(it, deleteState = false) }
        super.onCleared()
    }

    companion object {
        private const val MAX_TABS = 12
    }
}
