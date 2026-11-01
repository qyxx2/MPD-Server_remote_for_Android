package io.github.qyxx2.mpdserver.remote.ui.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
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

    @Test
    fun missingStatusRingPreferencesUseSpecificationDefaults() = runBlocking {
        val file = newPreferencesFile()
        val (store, scope) = newStore(file)
        try {
            assertEquals(StatusRingPreferences.DEFAULT, UiPreferencesRepository(store).statusRingPreferences.first())
        } finally {
            scope.cancel()
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun allStatusRingPreferencesSurviveRepositoryAndStoreRecreation() = runBlocking {
        val file = newPreferencesFile()
        val (initialStore, initialScope) = newStore(file)
        val selected = StatusRingPreferences(
            sizeScale = 1.25f,
            lineWidthDp = 4.5f,
            horizontalOffsetDp = -10f,
            verticalOffsetDp = 8f,
        )
        try {
            UiPreferencesRepository(initialStore).setStatusRingPreferences(selected)
        } finally {
            initialScope.cancel()
        }

        val (restoredStore, restoredScope) = newStore(file)
        try {
            assertEquals(selected, UiPreferencesRepository(restoredStore).statusRingPreferences.first())
        } finally {
            restoredScope.cancel()
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun legacyVerticalOffsetPreferenceIsClampedWhileSiblingPreferencesRemainIntact() = runBlocking {
        val file = newPreferencesFile()
        val (store, scope) = newStore(file)
        try {
            val repository = UiPreferencesRepository(store)
            repository.setServerOrigin(ServerOrigin.parseAddress("preserved.example.com"))
            repository.setThemePreference(ThemePreference.DARK)
            store.edit { preferences ->
                preferences[androidx.datastore.preferences.core.floatPreferencesKey("status_ring_size_scale")] = 1.25f
                preferences[androidx.datastore.preferences.core.floatPreferencesKey("status_ring_line_width_dp")] = 4.5f
                preferences[androidx.datastore.preferences.core.floatPreferencesKey("status_ring_horizontal_offset_dp")] = 18f
                preferences[androidx.datastore.preferences.core.floatPreferencesKey("status_ring_vertical_offset_dp")] = 24f
            }
            val restored = repository.statusRingPreferences.first()
            assertEquals(1.25f, restored.sizeScale, 0.0001f)
            assertEquals(4.5f, restored.lineWidthDp, 0.0001f)
            assertEquals(18f, restored.horizontalOffsetDp, 0.0001f)
            assertEquals(12f, restored.verticalOffsetDp, 0.0001f)
            assertEquals("http://preserved.example.com:18000", repository.serverOrigin.first())
            assertEquals(ThemePreference.DARK, repository.themePreference.first())
        } finally {
            scope.cancel()
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun resettingStatusRingPreferencesPersistsEveryDefault() = runBlocking {
        val file = newPreferencesFile()
        val (store, scope) = newStore(file)
        try {
            val repository = UiPreferencesRepository(store)
            repository.setStatusRingPreferences(
                StatusRingPreferences(1.35f, 5f, 24f, -24f),
            )
            repository.resetStatusRingPreferences()
            assertEquals(StatusRingPreferences.DEFAULT, repository.statusRingPreferences.first())
        } finally {
            scope.cancel()
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun missingServerOriginUsesLocalPlaceholder() = runBlocking {
        val file = newPreferencesFile()
        val (store, scope) = newStore(file)
        try {
            assertEquals(ServerOrigin.DEFAULT_ADDRESS, UiPreferencesRepository(store).serverOrigin.first())
        } finally {
            scope.cancel()
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun serverOriginSurvivesRepositoryAndStoreRecreation() = runBlocking {
        val file = newPreferencesFile()
        val (initialStore, initialScope) = newStore(file)
        try {
            UiPreferencesRepository(initialStore).setServerOrigin(ServerOrigin.parseAddress("ms.example.com"))
        } finally {
            initialScope.cancel()
        }

        val (restoredStore, restoredScope) = newStore(file)
        try {
            assertEquals(
                "http://ms.example.com:18000",
                UiPreferencesRepository(restoredStore).serverOrigin.first(),
            )
        } finally {
            restoredScope.cancel()
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun searchHistoryPersistsLatestFirstWithTenEntryLimitAndDeduplication() = runBlocking {
        val file = newPreferencesFile()
        val (initialStore, initialScope) = newStore(file)
        try {
            val repository = UiPreferencesRepository(initialStore)
            (1..12).forEach { repository.recordSearchQuery("query-$it") }
            repository.recordSearchQuery("query-8")
            assertEquals(
                listOf("query-8", "query-12", "query-11", "query-10", "query-9", "query-7", "query-6", "query-5", "query-4", "query-3"),
                repository.searchHistory.first(),
            )
        } finally {
            initialScope.cancel()
        }

        val (restoredStore, restoredScope) = newStore(file)
        try {
            assertEquals(
                listOf("query-8", "query-12", "query-11", "query-10", "query-9", "query-7", "query-6", "query-5", "query-4", "query-3"),
                UiPreferencesRepository(restoredStore).searchHistory.first(),
            )
        } finally {
            restoredScope.cancel()
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun clearingSearchHistoryPersistsAnEmptyHistory() = runBlocking {
        val file = newPreferencesFile()
        val (store, scope) = newStore(file)
        try {
            val repository = UiPreferencesRepository(store)
            repository.recordSearchQuery("jazz")
            repository.recordSearchQuery("classical")
            repository.clearSearchHistory()
            assertEquals(emptyList<String>(), repository.searchHistory.first())
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
