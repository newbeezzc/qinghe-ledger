package com.qinghe.ledger.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

data class AutoSettings(
    val notificationAutoEnabled: Boolean = true,
    val accessibilityAutoEnabled: Boolean = true,
    val showAutoRecordNotification: Boolean = true,
    val githubRepo: String = DEFAULT_GITHUB_REPO,
    val autoCheckUpdates: Boolean = true,
    val lastUpdateCheck: Long = 0L
)

/** Built-in update source so a fresh install can check updates without manual setup. */
const val DEFAULT_GITHUB_REPO = "newbeezzc/qinghe-ledger"

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val notificationAuto = booleanPreferencesKey("auto_notification_enabled")
        val accessibilityAuto = booleanPreferencesKey("auto_accessibility_enabled")
        val showAutoNotification = booleanPreferencesKey("auto_record_notification")
        val githubRepo = stringPreferencesKey("github_repo")
        val autoCheckUpdates = booleanPreferencesKey("auto_check_updates")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
    }

    val settings: Flow<AutoSettings> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            AutoSettings(
                notificationAutoEnabled = preferences[Keys.notificationAuto] ?: true,
                accessibilityAutoEnabled = preferences[Keys.accessibilityAuto] ?: true,
                showAutoRecordNotification = preferences[Keys.showAutoNotification] ?: true,
                githubRepo = preferences[Keys.githubRepo]?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_GITHUB_REPO,
                autoCheckUpdates = preferences[Keys.autoCheckUpdates] ?: true,
                lastUpdateCheck = preferences[Keys.lastUpdateCheck] ?: 0L
            )
        }

    suspend fun setNotificationAutoEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.notificationAuto] = enabled }
    }

    suspend fun setAccessibilityAutoEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.accessibilityAuto] = enabled }
    }

    suspend fun setShowAutoRecordNotification(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.showAutoNotification] = enabled }
    }

    suspend fun setGithubRepo(repo: String) {
        context.settingsDataStore.edit { it[Keys.githubRepo] = repo.trim() }
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.autoCheckUpdates] = enabled }
    }

    suspend fun setLastUpdateCheck(timestamp: Long) {
        context.settingsDataStore.edit { it[Keys.lastUpdateCheck] = timestamp }
    }
}
