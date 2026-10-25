package io.github.qyxx2.mpdserver.remote.ui.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UiPreferencesRepositoryTest {
    @Test
    fun missingThemePreferenceDefaultsToFollowSystem() = runBlocking {
        val file = newPreferencesFile()
        val (store, scope) = newStore(file)
        try {
            assertEquals(ThemePreference.FOLLOW_SYSTEM, UiPreferencesRepository(store).themePreference.first())
        } finally {
            scope.cancel()
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun selectedThemePreferenceSurvivesRepositoryAndStoreRecreation() = runBlocking {
        val file = newPreferencesFile()
        val (initialStore, initialScope) = newStore(file)
        try {
            UiPreferencesRepository(initialStore).setThemePreference(ThemePreference.DARK)
        } finally {
            initialScope.cancel()
        }

        val (restoredStore, restoredScope) = newStore(file)
        try {
            assertEquals(ThemePreference.DARK, UiPreferencesRepository(restoredStore).themePreference.first())
        } finally {
            restoredScope.cancel()
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun unknownStoredThemePreferenceDefaultsToFollowSystem() = runBlocking {
        val file = newPreferencesFile()
        val (store, scope) = newStore(file)
        try {
            store.edit { preferences ->
                preferences[stringPreferencesKey("theme_preference")] = "future-value"
            }
            assertEquals(ThemePreference.FOLLOW_SYSTEM, UiPreferencesRepository(store).themePreference.first())
        } finally {
            scope.cancel()
            file.parentFile?.deleteRecursively()
        }
    }

    private fun newPreferencesFile(): File =
        Files.createTempDirectory("mpd-remote-ui-preferences").resolve("ui.preferences_pb").toFile()

    private fun newStore(file: File): Pair<DataStore<Preferences>, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        ) to scope
    }
}
