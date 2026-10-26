package app.vidro

import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
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
        }
        vm.hideFullscreen = { hideCustom() }
        setContent {
            VidroTheme {
                val custom = fullscreenView
                if (custom != null) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { FrameLayout(it) },
                            update = { frame ->
                                if (custom.parent !== frame) {
                                    (custom.parent as? ViewGroup)?.removeView(custom)
                                    frame.removeAllViews()
                                    frame.addView(
                                        custom,
                                        FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                        ),
                                    )
                                }
                            },
                        )
                        if (!inPip) {
                            IconButton(onClick = { hideCustom() }, modifier = Modifier.statusBarsPadding()) {
                                Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
                            }
                        }
                    }
                } else {
                    BrowserApp(vm)
                }
            }
        }
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
