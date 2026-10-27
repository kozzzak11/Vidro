package app.vidro.ui

import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vidro.BuildConfig
import app.vidro.ui.theme.VidroPalette

@Composable
fun BrowserApp(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val playerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result -> vm.onPlayerClosed(result.data) }

    LaunchedEffect(state.toast) {
        val message = state.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.consumeToast()
    }
    LaunchedEffect(state.pendingVideoUrl) {
        if (state.pendingVideoUrl != null) {
            val intent = vm.consumePlayerIntent(context) ?: return@LaunchedEffect
            playerLauncher.launch(intent)
        }
    }
    if (BuildConfig.DEBUG) {
        val screen = (context as? android.app.Activity)?.intent?.getStringExtra("screen")
        LaunchedEffect(state.restored, screen) {
            if (state.restored && !screen.isNullOrBlank()) vm.prepareScreens(screen)
        }
    }

    BackHandler(enabled = state.overlay != Overlay.None || state.canGoBack || !state.home) {
        when {
            state.overlay != Overlay.None -> vm.dismissOverlay()
            state.canGoBack -> vm.goBack()
            !state.home -> vm.load(app.vidro.engine.AddressBar.HOME)
            else -> (context as? android.app.Activity)?.moveTaskToBack(true)
        }
    }

    Box(Modifier.fillMaxSize().background(VidroPalette.Bg).imePadding()) {
        if (!state.restored) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Vidro", color = VidroPalette.Lime, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(state, vm)
                LoadBar(vm)
                SessionBadge(vm)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (state.home) {
                        HomePane(state, onSubmit = vm::commitAddress, onOpen = vm::load)
                    } else {
                        WebSlot(state.webGeneration, state.activeId, vm)
                        state.error?.let { message ->
                            ErrorCard(message, onRetry = vm::reload, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = state.overlay != Overlay.None, enter = fadeIn(), exit = fadeOut()) {
            OverlayHost(state, vm)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp))
    }
}

@Composable
private fun TopBar(state: BrowserUiState, vm: BrowserViewModel) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var text by remember(state.activeId) { mutableStateOf(state.url) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.url, focused) {
        if (!focused) text = state.url
    }
    Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(VidroPalette.Bar)
                .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { vm.show(Overlay.Tabs) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    state.tabs.size.coerceAtLeast(1).toString(),
                    color = VidroPalette.Lime,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(VidroPalette.Card)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (focused) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        textStyle = androidx.compose.ui.text.TextStyle(color = VidroPalette.Text, fontSize = 15.sp),
                        cursorBrush = SolidColor(VidroPalette.Lime),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            vm.commitAddress(text)
                            focused = false
                            focusManager.clearFocus()
                            keyboard?.hide()
                        }),
                    )
                    LaunchedEffect(Unit) { focusRequester.requestFocus(); keyboard?.show() }
                    IconButton(onClick = { text = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Очистить", tint = VidroPalette.Muted, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Text(
                        text = state.host.ifBlank { "Адрес или поиск" },
                        color = if (state.host.isBlank()) VidroPalette.Muted else VidroPalette.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable {
                            text = state.url
                            focused = true
                        },
                        fontSize = 15.sp,
                    )
                    if (state.pageBlocked > 0) {
                        Text(
                            "${state.pageBlocked} · ${formatBytes(state.pageBytes)}",
                            color = VidroPalette.Lime,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(VidroPalette.Lime.copy(alpha = 0.14f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    if (!state.home) {
                        IconButton(onClick = vm::toggleBookmark, modifier = Modifier.size(36.dp)) {
                            Icon(
                                if (state.bookmarked) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = "Закладка",
                                tint = if (state.bookmarked) VidroPalette.Lime else VidroPalette.Muted,
                            )
                        }
                    }
                }
            }
            if (!state.home && !focused) {
                IconButton(onClick = vm::reload) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = VidroPalette.Text)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Меню", tint = VidroPalette.Text)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Новая вкладка") }, onClick = { menu = false; vm.newTab() })
                    DropdownMenuItem(text = { Text("Домой") }, onClick = { menu = false; vm.load(app.vidro.engine.AddressBar.HOME) }, leadingIcon = { Icon(Icons.Filled.Home, null) })
                    DropdownMenuItem(text = { Text("Закладки") }, onClick = { menu = false; vm.show(Overlay.Bookmarks) })
                    DropdownMenuItem(text = { Text("История") }, onClick = { menu = false; vm.show(Overlay.History) })
                    DropdownMenuItem(text = { Text("Обновить") }, onClick = { menu = false; vm.reload() })
                    DropdownMenuItem(
                        text = { Text("Поделиться") },
                        onClick = {
                            menu = false
                            if (state.url.startsWith("http")) {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, state.url)
                                }
                                context.startActivity(Intent.createChooser(send, null))
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (state.desktop) "Мобильная версия" else "Настольная версия") },
                        onClick = { menu = false; vm.toggleDesktop() },
                    )
                    if (state.siteKey.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text(if (state.adblockForSite) "Блокировка включена" else "Блокировка выключена") },
                            trailingIcon = {
                                if (state.adblockForSite) Icon(Icons.Filled.Check, null, tint = VidroPalette.Lime) else null
                            },
                            onClick = { menu = false; vm.toggleSiteAdblock() },
                        )
                    }
                    DropdownMenuItem(text = { Text("Настройки") }, onClick = { menu = false; vm.show(Overlay.Settings) })
                }
            }
        }
    }
}

@Composable
private fun LoadBar(vm: BrowserViewModel) {
    val progress by vm.loadProgress.collectAsStateWithLifecycle()
    if (progress in 1..99) {
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(2.dp),
            color = VidroPalette.Lime,
            trackColor = Color.Transparent,
        )
    }
}

@Composable
private fun SessionBadge(vm: BrowserViewModel) {
    val traffic by vm.traffic.collectAsStateWithLifecycle()
    if (traffic.used + traffic.saved <= 0L) return
    Text(
        "${formatBytes(traffic.used)} · −${formatBytes(traffic.saved)}",
        color = VidroPalette.Muted,
        fontSize = 11.sp,
        modifier = Modifier
            .padding(start = 22.dp, bottom = 2.dp)
            .semantics {
                contentDescription = "Трафик сессии ${formatBytes(traffic.used)}, сэкономлено ${formatBytes(traffic.saved)}"
            },
    )
}

@Composable
private fun WebSlot(generation: Int, activeId: String, vm: BrowserViewModel) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { FrameLayout(it) },
        update = { frame ->
            val web: WebView? = vm.webView()
            if (web == null) {
                frame.removeAllViews()
                return@AndroidView
            }
            if (web.parent === frame) return@AndroidView
            (web.parent as? ViewGroup)?.removeView(web)
            frame.removeAllViews()
            frame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        },
    )
    // generation and activeId are read so a new webview is attached.
    if (generation < 0 || activeId.length < 0) Spacer(Modifier.size(0.dp))
}

@Composable
private fun HomePane(state: BrowserUiState, onSubmit: (String) -> Unit, onOpen: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Vidro", color = VidroPalette.Text, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.2).sp)
        Spacer(Modifier.height(6.dp))
        Text("Смотреть видео без лишнего трафика", color = VidroPalette.Muted, fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(18.dp)).background(VidroPalette.Card).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = VidroPalette.Lime)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = VidroPalette.Text, fontSize = 16.sp),
                cursorBrush = SolidColor(VidroPalette.Lime),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit(query); query = "" }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Адрес или поиск", color = VidroPalette.Muted)
                        inner()
                    }
                },
            )
        }
        Spacer(Modifier.height(28.dp))
        if (state.bookmarks.isEmpty()) {
            Text("Закладки появятся здесь", color = VidroPalette.Muted, fontSize = 13.sp)
        } else {
            Text("Закладки", color = VidroPalette.Muted, modifier = Modifier.fillMaxWidth(), fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(state.bookmarks, key = { it.id }) { bookmark ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(VidroPalette.Card)
                            .clickable { onOpen(bookmark.url) }
                            .padding(14.dp),
                    ) {
                        Text(bookmark.title, color = VidroPalette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(bookmark.url, color = VidroPalette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val status = when {
            !state.filtersReady -> "Списки загружаются…"
            state.settings.adBlockEnabled -> "Блокировка включена · ${state.filterRules} правил"
            else -> "Блокировка выключена"
        }
        Text(status, color = VidroPalette.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 16.dp))
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(VidroPalette.Card).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = VidroPalette.Text)
        Spacer(Modifier.height(8.dp))
        Text(
            "Повторить",
            color = VidroPalette.OnLime,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(VidroPalette.Lime).clickable(onClick = onRetry).padding(horizontal = 16.dp, vertical = 8.dp),
            fontWeight = FontWeight.SemiBold,
        )
    }
}
