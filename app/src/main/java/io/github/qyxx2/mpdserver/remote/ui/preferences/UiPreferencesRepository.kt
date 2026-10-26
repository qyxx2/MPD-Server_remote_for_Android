package io.github.qyxx2.mpdserver.remote.ui.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    private companion object {
        val THEME_PREFERENCE_KEY = stringPreferencesKey("theme_preference")
        val STATUS_RING_SIZE_SCALE_KEY = floatPreferencesKey("status_ring_size_scale")
        val STATUS_RING_LINE_WIDTH_KEY = floatPreferencesKey("status_ring_line_width_dp")
        val STATUS_RING_HORIZONTAL_OFFSET_KEY = floatPreferencesKey("status_ring_horizontal_offset_dp")
        val STATUS_RING_VERTICAL_OFFSET_KEY = floatPreferencesKey("status_ring_vertical_offset_dp")
    }
}
