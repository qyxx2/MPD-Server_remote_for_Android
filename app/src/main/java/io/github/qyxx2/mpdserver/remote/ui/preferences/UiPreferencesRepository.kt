package io.github.qyxx2.mpdserver.remote.ui.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import androidx.datastore.preferences.preferencesDataStore
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal val Context.uiPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ui_preferences",
)

/** Persists non-business presentation preferences on this device only. */
class UiPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val themePreference: Flow<ThemePreference> = dataStore.data.map { preferences ->
        ThemePreference.entries.firstOrNull { it.name == preferences[THEME_PREFERENCE_KEY] }
            ?: ThemePreference.FOLLOW_SYSTEM
    }

    /** Local configured origin only; this is not evidence that the server is reachable. */
    val serverOrigin: Flow<String> = dataStore.data.map { preferences ->
        preferences[SERVER_ORIGIN_KEY] ?: ServerOrigin.DEFAULT_ADDRESS
    }

    /** Latest-first persisted search history, capped at ten distinct non-blank queries. */
    val searchHistory: Flow<List<String>> = dataStore.data.map { preferences ->
        decodeSearchHistory(preferences[SEARCH_HISTORY_KEY].orEmpty())
    }

    suspend fun recordSearchQuery(query: String) {
        dataStore.edit { preferences ->
            val current = decodeSearchHistory(preferences[SEARCH_HISTORY_KEY].orEmpty())
            val updated = SearchHistoryPolicy.record(current, query)
            if (updated.isEmpty()) preferences.remove(SEARCH_HISTORY_KEY)
            else preferences[SEARCH_HISTORY_KEY] = encodeSearchHistory(updated)
        }
    }

    suspend fun clearSearchHistory() {
        dataStore.edit { preferences -> preferences.remove(SEARCH_HISTORY_KEY) }
    }

    suspend fun setServerOrigin(origin: ServerOrigin) {
        dataStore.edit { preferences ->
            preferences[SERVER_ORIGIN_KEY] = origin.address
        }
    }

    val statusRingPreferences: Flow<StatusRingPreferences> = dataStore.data.map { preferences ->
        StatusRingPreferences(
            sizeScale = preferences[STATUS_RING_SIZE_SCALE_KEY] ?: StatusRingPreferences.SIZE_SCALE_DEFAULT,
            lineWidthDp = preferences[STATUS_RING_LINE_WIDTH_KEY] ?: StatusRingPreferences.LINE_WIDTH_DEFAULT_DP,
            horizontalOffsetDp = preferences[STATUS_RING_HORIZONTAL_OFFSET_KEY]
                ?: StatusRingPreferences.POSITION_OFFSET_DEFAULT_DP,
            verticalOffsetDp = preferences[STATUS_RING_VERTICAL_OFFSET_KEY]
                ?: StatusRingPreferences.POSITION_OFFSET_DEFAULT_DP,
        ).normalized()
    }

    suspend fun setThemePreference(preference: ThemePreference) {
        dataStore.edit { preferences ->
            preferences[THEME_PREFERENCE_KEY] = preference.name
        }
    }

    suspend fun setStatusRingPreferences(preference: StatusRingPreferences) {
        val normalized = preference.normalized()
        dataStore.edit { preferences ->
            preferences[STATUS_RING_SIZE_SCALE_KEY] = normalized.sizeScale
            preferences[STATUS_RING_LINE_WIDTH_KEY] = normalized.lineWidthDp
            preferences[STATUS_RING_HORIZONTAL_OFFSET_KEY] = normalized.horizontalOffsetDp
            preferences[STATUS_RING_VERTICAL_OFFSET_KEY] = normalized.verticalOffsetDp
        }
    }

    /** Writes all four defaults explicitly so reset is a persisted preference update. */
    suspend fun resetStatusRingPreferences() = setStatusRingPreferences(StatusRingPreferences.DEFAULT)

    private fun decodeSearchHistory(raw: String): List<String> = runCatching {
        Json.decodeFromString(ListSerializer(String.serializer()), raw)
    }.getOrDefault(emptyList()).let(SearchHistoryPolicy::normalize)

    private fun encodeSearchHistory(history: List<String>): String =
        Json.encodeToString(ListSerializer(String.serializer()), history)

    private companion object {
        val SEARCH_HISTORY_KEY = stringPreferencesKey("search_history")
        val SERVER_ORIGIN_KEY = stringPreferencesKey("server_origin")
        val THEME_PREFERENCE_KEY = stringPreferencesKey("theme_preference")
        val STATUS_RING_SIZE_SCALE_KEY = floatPreferencesKey("status_ring_size_scale")
        val STATUS_RING_LINE_WIDTH_KEY = floatPreferencesKey("status_ring_line_width_dp")
        val STATUS_RING_HORIZONTAL_OFFSET_KEY = floatPreferencesKey("status_ring_horizontal_offset_dp")
        val STATUS_RING_VERTICAL_OFFSET_KEY = floatPreferencesKey("status_ring_vertical_offset_dp")
    }
}
