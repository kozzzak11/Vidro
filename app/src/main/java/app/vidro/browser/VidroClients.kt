package app.vidro.browser

import android.annotation.SuppressLint
import android.app.Application
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.vidro.data.UserSettings
import app.vidro.engine.AddressBar
import app.vidro.engine.FilterMatcher
import app.vidro.engine.Request
import app.vidro.engine.ResourceType
import app.vidro.engine.Scripts
import app.vidro.engine.UrlSniffer
import org.json.JSONArray
import java.io.ByteArrayInputStream

interface BrowserHost {
    val settings: UserSettings
    val engine: AdBlockEngine
    fun siteAllowsAdblock(url: String): Boolean
    fun onPageStarted(tab: TabSession, url: String)
    fun onPageFinished(tab: TabSession, url: String)
    fun onTitle(tab: TabSession, title: String)
    fun onProgress(tab: TabSession, progress: Int)
    fun onVideos(tab: TabSession, urls: List<String>)
    fun onPlaying(tab: TabSession, url: String)
    fun onNavigate(tab: TabSession, url: String)
    fun onBlocked(tab: TabSession, type: ResourceType)
    fun onDirectVideo(tab: TabSession, url: String)
    fun allowNewWindow(isDialog: Boolean, isUserGesture: Boolean): Boolean
    fun openPopupTab(): WebView?
    fun closePopup(tab: TabSession)
    fun onPageError(tab: TabSession, message: String?)
    fun onRenderProcessGone(tab: TabSession)
    fun chooseFile(callback: ValueCallback<Array<Uri>>)
    fun showWebFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
    fun hideWebFullscreen()
    fun prefersNativeFullscreen(tab: TabSession): Boolean
    fun onDownload()
}

private val mainHandler = Handler(Looper.getMainLooper())

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

fun isYouTubeHost(url: String?): Boolean {
    val host = AddressBar.hostOf(url) ?: return false
    return host == "youtu.be" || host.endsWith(".youtu.be") ||
        host == "youtube.com" || host.endsWith(".youtube.com") ||
        host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
}

@SuppressLint("SetJavaScriptEnabled")
fun applyWebSettings(web: WebView, settings: UserSettings, desktop: Boolean) {
    val webSettings = web.settings
    webSettings.javaScriptEnabled = true
    webSettings.domStorageEnabled = true
    webSettings.databaseEnabled = true
    webSettings.mediaPlaybackRequiresUserGesture = settings.blockAutoplay
    webSettings.loadsImagesAutomatically = !settings.blockImages
    webSettings.blockNetworkImage = settings.blockImages
    webSettings.javaScriptCanOpenWindowsAutomatically = !settings.blockPopups
    webSettings.setSupportMultipleWindows(true)
    webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    webSettings.cacheMode = WebSettings.LOAD_DEFAULT
    webSettings.useWideViewPort = true
    webSettings.loadWithOverviewMode = true
    webSettings.builtInZoomControls = true
    webSettings.displayZoomControls = false
    webSettings.setSupportZoom(true)
    webSettings.setGeolocationEnabled(false)
    webSettings.allowFileAccess = false
    webSettings.allowContentAccess = true
    webSettings.safeBrowsingEnabled = false
    if (Build.VERSION.SDK_INT >= 33) {
        webSettings.isAlgorithmicDarkeningAllowed = false
    } else if (Build.VERSION.SDK_INT >= 29) {
        @Suppress("DEPRECATION")
        webSettings.forceDark = WebSettings.FORCE_DARK_OFF
    }
    webSettings.userAgentString = if (desktop) DESKTOP_UA else WebSettings.getDefaultUserAgent(web.context)
}

@SuppressLint("SetJavaScriptEnabled")
fun createTabWebView(tab: TabSession, app: Application, host: BrowserHost): WebView {
    val wrapper = MutableContextWrapper(app)
    tab.wrapper = wrapper
    val web = WebView(wrapper)
    tab.web = web
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
    applyWebSettings(web, host.settings, tab.desktop)
    web.addJavascriptInterface(VidroJs(tab, host), "VidroJs")
    web.webViewClient = VidroWebViewClient(tab, host)
    web.webChromeClient = VidroChromeClient(tab, host)
    web.setDownloadListener { _, _, _, _, _ -> host.onDownload() }
    installDocumentScripts(web)
    return web
}

fun installDocumentScripts(web: WebView) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
    try {
        WebViewCompat.addDocumentStartJavaScript(web, Scripts.video, setOf("*"))
        WebViewCompat.addDocumentStartJavaScript(
            web,
            Scripts.youtube,
            setOf(
                "https://www.youtube.com",
                "https://m.youtube.com",
                "https://youtube.com",
                "https://music.youtube.com",
                "https://www.youtube-nocookie.com",
            ),
        )
    } catch (_: Throwable) {
    }
}

fun injectPageHelpers(web: WebView, url: String?, chunks: List<String>, youtube: Boolean) {
    web.evaluateJavascript(Scripts.video, null)
    if (youtube) web.evaluateJavascript(Scripts.youtube, null)
    if (chunks.isNotEmpty() || true) {
        web.evaluateJavascript(Scripts.cosmeticCall(chunks), null)
    }
}

private class VidroJs(private val tab: TabSession, private val host: BrowserHost) {
    @android.webkit.JavascriptInterface
    fun onVideos(json: String) {
        mainHandler.post {
            val urls = ArrayList<String>()
            try {
                val array = JSONArray(json)
                for (i in 0 until array.length()) urls.add(array.getString(i))
            } catch (_: Exception) {
            }
            host.onVideos(tab, urls)
        }
    }

    @android.webkit.JavascriptInterface
    fun onPlaying(url: String) {
        mainHandler.post { host.onPlaying(tab, url) }
    }

    @android.webkit.JavascriptInterface
    fun onNavigate(url: String) {
        mainHandler.post { host.onNavigate(tab, url) }
    }
}

private class VidroWebViewClient(
    private val tab: TabSession,
    private val host: BrowserHost,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return true
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") return false
        if (scheme == "intent" || scheme == "mailto" || scheme == "tel" || scheme == "market") {
            try {
                val intent = android.content.Intent.parseUri(uri.toString(), android.content.Intent.URI_INTENT_SCHEME)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                view.context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        if (request.isForMainFrame) {
            tab.pageUrl.set(url)
            return null
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val document = tab.pageUrl.get()
        val type = FilterMatcher.inferType(url, false)
        if (host.siteAllowsAdblock(document) && host.engine.ready.value) {
            val blocked = host.engine.matcher.shouldBlock(
                Request(url = url, documentHost = document, type = type),
            )
            if (blocked) {
                host.onBlocked(tab, type)
                return blockedResponse(type)
            }
        }
        val hit = UrlSniffer.sniff(url, null)
        if (hit != null) host.onDirectVideo(tab, hit.url)
        return null
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (url.isNullOrBlank()) return
        host.onPageStarted(tab, url)
        val chunks = if (host.siteAllowsAdblock(url)) host.engine.matcher.cosmeticChunks(url) else emptyList()
        injectPageHelpers(view, url, chunks, isYouTubeHost(url) && host.siteAllowsAdblock(url))
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (url.isNullOrBlank()) return
        host.onPageFinished(tab, url)
        val chunks = if (host.siteAllowsAdblock(url)) host.engine.matcher.cosmeticChunks(url) else emptyList()
        view.evaluateJavascript(Scripts.cosmeticCall(chunks), null)
        if (tab.scrollY > 0) {
            view.evaluateJavascript("window.scrollTo(0, ${tab.scrollY.coerceAtLeast(0)})", null)
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        val message = when (error.errorCode) {
            ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_IO -> "Нет соединения"
            else -> "Страница не открылась"
        }
        host.onPageError(tab, message)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        host.onRenderProcessGone(tab)
        return true
    }
}

private class VidroChromeClient(
    private val tab: TabSession,
    private val host: BrowserHost,
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        host.onProgress(tab, newProgress)
        if (newProgress == 100 && view != null) {
            val url = tab.pageUrl.get()
            if (host.siteAllowsAdblock(url)) {
                view.evaluateJavascript(Scripts.cosmeticCall(host.engine.matcher.cosmeticChunks(url)), null)
            }
        }
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        if (!title.isNullOrBlank()) host.onTitle(tab, title)
    }

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
        if (!host.allowNewWindow(isDialog, isUserGesture)) return false
        val popup = host.openPopupTab() ?: return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null || callback == null) return
        if (host.prefersNativeFullscreen(tab)) {
            callback.onCustomViewHidden()
            return
        }
        host.showWebFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        host.hideWebFullscreen()
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        if (filePathCallback == null) return false
        host.chooseFile(filePathCallback)
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val allow = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
        if (allow.isNotEmpty()) request.grant(allow.toTypedArray()) else request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        callback?.invoke(origin, false, false)
    }
}

private fun blockedResponse(type: ResourceType): WebResourceResponse {
    val mime = when (type) {
        ResourceType.SCRIPT -> "application/javascript"
        ResourceType.STYLESHEET -> "text/css"
        ResourceType.IMAGE -> "image/gif"
        else -> "text/plain"
    }
    return WebResourceResponse(
        mime,
        "UTF-8",
        200,
        "OK",
        mapOf("Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(ByteArray(0)),
    )
}
