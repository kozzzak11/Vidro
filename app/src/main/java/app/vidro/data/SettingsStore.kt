package app.vidro.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.vidro.engine.SearchEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("vidro_settings")

class SettingsStore(context: Context) {
    private val data = context.applicationContext.settingsDataStore

    val settings: Flow<UserSettings> = data.data.map { prefs -> prefs.toSettings() }.distinctUntilChanged()
    val stats: Flow<BlockStats> = data.data.map { prefs -> prefs.toStats() }.distinctUntilChanged()

    suspend fun snapshot(): UserSettings = settings.first()

    suspend fun setSearchEngine(engine: SearchEngine) = edit { it[Keys.engine] = engine.id }
    suspend fun setAdBlock(enabled: Boolean) = edit { it[Keys.adBlock] = enabled }
    suspend fun setBlockAutoplay(enabled: Boolean) = edit { it[Keys.autoplay] = enabled }
    suspend fun setBlockImages(enabled: Boolean) = edit { it[Keys.images] = enabled }
    suspend fun setAutoOpenPlayer(enabled: Boolean) = edit { it[Keys.autoOpen] = enabled }
    suspend fun setOpenOnFullscreen(enabled: Boolean) = edit { it[Keys.fullscreen] = enabled }
    suspend fun setBlockPopups(enabled: Boolean) = edit { it[Keys.popups] = enabled }
    suspend fun setWifiOnly(enabled: Boolean) = edit { it[Keys.wifiOnly] = enabled }
    suspend fun setCapMobileQuality(enabled: Boolean) = edit { it[Keys.capMobile] = enabled }

    suspend fun setSiteDisabled(site: String, disabled: Boolean) = edit { prefs ->
        val current = prefs[Keys.sites]?.toMutableSet() ?: mutableSetOf()
        if (disabled) current.add(site) else current.remove(site)
        prefs[Keys.sites] = current
    }

    suspend fun addBlocked(count: Long, bytes: Long) {
        if (count == 0L && bytes == 0L) return
        edit { prefs ->
            prefs[Keys.blocked] = (prefs[Keys.blocked] ?: 0L) + count
            prefs[Keys.bytes] = (prefs[Keys.bytes] ?: 0L) + bytes
        }
    }

    suspend fun clearStats() = edit {
        it[Keys.blocked] = 0L
        it[Keys.bytes] = 0L
    }

    suspend fun setFiltersUpdated(at: Long) = edit { it[Keys.updated] = at }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        data.edit(block)
    }

    private fun androidx.datastore.preferences.core.Preferences.toSettings() = UserSettings(
        searchEngine = SearchEngine.fromId(this[Keys.engine]),
        adBlockEnabled = this[Keys.adBlock] ?: true,
        blockAutoplay = this[Keys.autoplay] ?: true,
        blockImages = this[Keys.images] ?: false,
        autoOpenPlayer = this[Keys.autoOpen] ?: false,
        openOnFullscreen = this[Keys.fullscreen] ?: true,
        blockPopups = this[Keys.popups] ?: true,
        wifiOnlyFilterUpdates = this[Keys.wifiOnly] ?: true,
        capMobileQuality = this[Keys.capMobile] ?: true,
        disabledSites = this[Keys.sites] ?: emptySet(),
    )

    private fun androidx.datastore.preferences.core.Preferences.toStats() = BlockStats(
        totalBlocked = this[Keys.blocked] ?: 0L,
        totalBytes = this[Keys.bytes] ?: 0L,
        filtersUpdatedAt = this[Keys.updated] ?: 0L,
    )

    private object Keys {
        val engine = stringPreferencesKey("search_engine")
        val adBlock = booleanPreferencesKey("adblock")
        val autoplay = booleanPreferencesKey("block_autoplay")
        val images = booleanPreferencesKey("block_images")
        val autoOpen = booleanPreferencesKey("auto_open_player")
        val fullscreen = booleanPreferencesKey("open_on_fullscreen")
        val popups = booleanPreferencesKey("block_popups")
        val wifiOnly = booleanPreferencesKey("wifi_only_filters")
        val capMobile = booleanPreferencesKey("cap_mobile_quality")
        val sites = stringSetPreferencesKey("disabled_sites")
        val blocked = longPreferencesKey("total_blocked")
        val bytes = longPreferencesKey("total_bytes")
        val updated = longPreferencesKey("filters_updated")
    }
}
