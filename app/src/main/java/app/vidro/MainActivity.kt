package app.vidro

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.vidro.player.WebFullscreen
import app.vidro.player.enterImmersive
import app.vidro.player.exitImmersive
import app.vidro.ui.BrowserApp
import app.vidro.ui.BrowserViewModel
import app.vidro.ui.theme.VidroTheme

class MainActivity : ComponentActivity() {
    private val vm: BrowserViewModel by viewModels()
    private var fullscreenView by mutableStateOf<View?>(null)
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var inPip by mutableStateOf(false)

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        vm.onFileChosen(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        vm.fileChooser = { pickFile.launch("*/*") }
        vm.showFullscreen = { view, callback ->
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = callback
            fullscreenView = view
            enterImmersive()
        }
        vm.hideFullscreen = { hideCustom() }
        vm.openFromIntent(intent)
        setContent {
            VidroTheme {
                val custom = fullscreenView
                if (custom != null) {
                    BackHandler { hideCustom() }
                    WebFullscreen(
                        custom = custom,
                        inPip = inPip,
                        onClose = { hideCustom() },
                        onPip = {
                            if (Build.VERSION.SDK_INT >= 26 && !inPip) enterPictureInPictureMode(pipParams())
                        },
                        eval = { js -> vm.webView()?.evaluateJavascript(js, null) },
                    )
                } else {
                    BrowserApp(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.openFromIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fullscreenView != null) enterImmersive()
    }

    override fun onResume() {
        super.onResume()
        vm.attach(this)
    }

    override fun onPause() {
        vm.detach(application)
        super.onPause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (fullscreenView != null && Build.VERSION.SDK_INT >= 26 && !inPip) {
            enterPictureInPictureMode(pipParams())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    private fun hideCustom() {
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        exitImmersive()
    }

    private fun pipParams(): android.app.PictureInPictureParams {
        val builder = android.app.PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(fullscreenView != null)
        val hint = android.graphics.Rect()
        window.decorView.getGlobalVisibleRect(hint)
        if (!hint.isEmpty) builder.setSourceRectHint(hint)
        return builder.build()
    }

    override fun onDestroy() {
        vm.fileChooser = null
        vm.showFullscreen = null
        vm.hideFullscreen = null
        super.onDestroy()
    }
}
