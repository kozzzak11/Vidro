package app.vidro.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vidro.BuildConfig
import app.vidro.data.HistoryEntry
import app.vidro.engine.SearchEngine
import app.vidro.ui.theme.VidroPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun OverlayHost(state: BrowserUiState, vm: BrowserViewModel) {
    Column(Modifier.fillMaxSize().background(VidroPalette.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::dismissOverlay) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = VidroPalette.Text)
            }
            Text(
                when (state.overlay) {
                    Overlay.Tabs -> "Вкладки"
                    Overlay.Bookmarks -> "Закладки"
                    Overlay.History -> "История"
                    Overlay.Settings -> "Настройки"
                    Overlay.None -> ""
                },
                color = VidroPalette.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        when (state.overlay) {
            Overlay.Tabs -> TabsPane(state, vm)
            Overlay.Bookmarks -> MarksPane(state, vm)
            Overlay.History -> HistoryPane(state, vm)
            Overlay.Settings -> SettingsPane(state, vm)
            Overlay.None -> Unit
        }
    }
}

@Composable
private fun TabsPane(state: BrowserUiState, vm: BrowserViewModel) {
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(state.tabs, key = { it.id }) { tab ->
                val active = tab.id == state.activeId
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(132.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (active) VidroPalette.CardHigh else VidroPalette.Card)
                        .clickable { vm.selectTab(tab.id) }
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tab.title.ifBlank { "Vidro" },
                            color = VidroPalette.Text,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Закрыть вкладку",
                            tint = VidroPalette.Muted,
                            modifier = Modifier.size(18.dp).clickable { vm.closeTab(tab.id) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(tab.host.ifBlank { "Новая вкладка" }, color = VidroPalette.Muted, fontSize = 12.sp, maxLines = 1)
                    if (active) {
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(VidroPalette.Lime))
                    }
                }
            }
        }
        Box(Modifier.navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.clip(RoundedCornerShape(18.dp)).background(VidroPalette.Lime).clickable { vm.newTab() }.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = VidroPalette.OnLime)
                Spacer(Modifier.width(6.dp))
                Text("Новая вкладка", color = VidroPalette.OnLime, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MarksPane(state: BrowserUiState, vm: BrowserViewModel) {
    if (state.bookmarks.isEmpty()) {
        Empty("Закладок пока нет")
        return
    }
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.bookmarks, key = { it.id }) { bookmark ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(VidroPalette.Card).clickable { vm.load(bookmark.url) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(bookmark.title, color = VidroPalette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(bookmark.url, color = VidroPalette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { vm.deleteBookmark(bookmark.url) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = VidroPalette.Muted)
                }
            }
        }
    }
}

@Composable
private fun HistoryPane(state: BrowserUiState, vm: BrowserViewModel) {
    if (state.history.isEmpty()) {
        Empty("История пуста")
        return
    }
    val groups = groupHistory(state.history)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TextButton(onClick = vm::clearHistory, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Очистить историю", color = VidroPalette.Danger)
            }
        }
        groups.forEach { (label, entries) ->
            item { Text(label, color = VidroPalette.Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 13.sp) }
            items(entries, key = { it.id }) { entry ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.load(entry.url) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, color = VidroPalette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.url, color = VidroPalette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { vm.deleteHistory(entry.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Удалить", tint = VidroPalette.Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPane(state: BrowserUiState, vm: BrowserViewModel) {
    LazyColumn(
        Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionTitle("Поиск") }
        item {
            Card {
                Text("Поисковик", color = VidroPalette.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchEngine.entries.forEach { engine ->
                        val selected = state.settings.searchEngine == engine
                        Text(
                            engine.label,
                            color = if (selected) VidroPalette.OnLime else VidroPalette.Text,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) VidroPalette.Lime else VidroPalette.CardHigh)
                                .clickable { vm.setSearchEngine(engine) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        item { SectionTitle("Экономия трафика") }
        item {
            Card {
                ToggleRow("Не запускать видео само", "Экономит трафик на страницах", state.settings.blockAutoplay, vm::setBlockAutoplay)
                ToggleRow("Не грузить картинки", "Страницы без изображений", state.settings.blockImages, vm::setBlockImages)
                ToggleRow("Блокировать всплывающие окна", "Новые окна только по нажатию", state.settings.blockPopups, vm::setBlockPopups)
            }
        }
        item { SectionTitle("Реклама") }
        item {
            Card {
                ToggleRow("Блокировка рекламы", "EasyList, EasyPrivacy, uBlock", state.settings.adBlockEnabled, vm::setAdBlock)
                ToggleRow("Обновлять списки только по Wi‑Fi", "Чтобы не тратить спутниковый канал", state.settings.wifiOnlyFilterUpdates, vm::setWifiOnly)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.filtersReady) "Правил: ${state.filterRules}" else "Списки загружаются…",
                    color = VidroPalette.Muted,
                    fontSize = 13.sp,
                )
                Text(
                    "Всего ${state.totalBlocked} · ≈ ${formatBytes(state.totalBytes)}",
                    color = VidroPalette.Text,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "На этой странице ${state.pageBlocked} · ≈ ${formatBytes(state.pageBytes)}",
                    color = VidroPalette.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (state.updatingFilters) "Обновление…" else "Обновить списки",
                    color = VidroPalette.OnLime,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(VidroPalette.Lime)
                        .clickable(enabled = !state.updatingFilters) { vm.updateFilters() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
                TextButton(onClick = vm::clearStats) { Text("Сбросить статистику", color = VidroPalette.Muted) }
            }
        }
        item { SectionTitle("О приложении") }
        item {
            Card {
                Text("Vidro ${BuildConfig.VERSION_NAME}", color = VidroPalette.Text, fontWeight = FontWeight.Medium)
                Text(
                    "Счётчик экономии — оценка. DRM и blob-плееры остаются в WebView.",
                    color = VidroPalette.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = VidroPalette.Lime, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(VidroPalette.Card).padding(14.dp)) {
        content()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = VidroPalette.Text)
            Text(subtitle, color = VidroPalette.Muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = VidroPalette.Lime,
                checkedThumbColor = VidroPalette.OnLime,
                uncheckedTrackColor = VidroPalette.CardHigh,
                uncheckedThumbColor = VidroPalette.Muted,
            ),
        )
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = VidroPalette.Muted)
    }
}

private fun groupHistory(entries: List<HistoryEntry>): List<Pair<String, List<HistoryEntry>>> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val formatter = DateTimeFormatter.ofPattern("d MMMM", Locale("ru"))
    return entries.groupBy { entry ->
        val day = Instant.ofEpochMilli(entry.visited).atZone(zone).toLocalDate()
        when (day) {
            today -> "Сегодня"
            today.minusDays(1) -> "Вчера"
            else -> day.format(formatter)
        }
    }.map { it.key to it.value }
}
