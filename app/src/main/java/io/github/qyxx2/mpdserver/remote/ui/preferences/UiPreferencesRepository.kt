package io.github.qyxx2.mpdserver.remote.ui.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

    suspend fun setThemePreference(preference: ThemePreference) {
        dataStore.edit { preferences ->
            preferences[THEME_PREFERENCE_KEY] = preference.name
        }
    }

    private companion object {
        val THEME_PREFERENCE_KEY = stringPreferencesKey("theme_preference")
    }
}
