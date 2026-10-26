package app.vidro

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebView
import app.vidro.browser.AdBlockEngine
import app.vidro.data.SettingsStore
import app.vidro.data.VidroDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VidroApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var engine: AdBlockEngine
        private set
    lateinit var db: VidroDb
        private set
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        db = VidroDb(this)
        settings = SettingsStore(this)
        engine = AdBlockEngine(this)
        CookieManager.getInstance().setAcceptCookie(true)
        if (BuildConfig.DEBUG) {
            runCatching { WebView.setWebContentsDebuggingEnabled(true) }
        }
        appScope.launch {
            runCatching { engine.load() }
        }
    }
}
