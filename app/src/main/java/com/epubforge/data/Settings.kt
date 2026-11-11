package com.epubforge.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    val theme: Int = 0, // 0 system, 1 light, 2 dark
    val dynamicColor: Boolean = true,
    val pureBlack: Boolean = false,
    val defaultDelayMs: Int = 800,
    val royalRoadDelayMs: Int = 2500,
    val maxRetries: Int = 5,
    val librarySort: Int = 0, // 0 last updated, 1 title, 2 date added
    val libraryList: Boolean = false,
    val autoSync: Boolean = true,
    val syncWifiOnly: Boolean = false,
    // Sync: 0 off, 1 WebDAV, 2 Supabase. Passwords and tokens live in [Secrets], never here.
    val syncProvider: Int = 0,
    val webdavUrl: String = "",
    val webdavUser: String = "",
    val supabaseUrl: String = "",
    val supabaseKey: String = "",
    val supabaseEmail: String = "",
    val lastSync: Long = 0,
    val lastSyncError: String = "",
    val lastSyncedVersion: Long = 0,
    // Download defaults (each can be changed per download in the Add sheet).
    val includeImages: Boolean = true,
    val includeAuthorNotes: Boolean = true,
    val stopAfterFailures: Int = 5,
    // Library updates
    val updateIntervalHours: Int = 12, // 0 = off
    val autoDownloadNew: Boolean = true,
    val updateWifiOnly: Boolean = false,
    val notifyNewChapters: Boolean = true,
    // Local backups
    val autoBackupDays: Int = 0, // 0 off, 1 daily, 7 weekly
    val autoBackupFolder: String = "", // SAF tree URI
    val autoBackupKeep: Int = 5,
    val backupIncludeChapters: Boolean = false,
    val lastBackup: Long = 0,
    val lastBackupError: String = "",
    // In-app updates (official releases only)
    val checkUpdates: Boolean = true,
)

/** Passwords and sign-in tokens. Kept out of settings so backups never include them. */
class Secrets(context: Context) {
    private val sp = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    var webdavPassword: String
        get() = sp.getString("dav_password", "") ?: ""
        set(v) { sp.edit().putString("dav_password", v).apply() }
    var supabaseSession: String
        get() = sp.getString("sb_session", "") ?: ""
        set(v) { sp.edit().putString("sb_session", v).apply() }
    fun clear() = sp.edit().clear().apply()
}

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private object K {
        val theme = intPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic")
        val black = booleanPreferencesKey("black")
        val delay = intPreferencesKey("delay")
        val rrDelay = intPreferencesKey("rr_delay")
        val retries = intPreferencesKey("retries")
        val sort = intPreferencesKey("sort")
        val list = booleanPreferencesKey("list")
        val autoSync = booleanPreferencesKey("auto_sync")
        val wifi = booleanPreferencesKey("wifi")
        val provider = intPreferencesKey("sync_provider")
        val davUrl = stringPreferencesKey("dav_url")
        val davUser = stringPreferencesKey("dav_user")
        val sbUrl = stringPreferencesKey("sb_url")
        val sbKey = stringPreferencesKey("sb_key")
        val sbEmail = stringPreferencesKey("sb_email")
        val bkDays = intPreferencesKey("bk_days")
        val bkFolder = stringPreferencesKey("bk_folder")
        val bkKeep = intPreferencesKey("bk_keep")
        val bkChapters = booleanPreferencesKey("bk_chapters")
        val bkLast = longPreferencesKey("bk_last")
        val bkErr = stringPreferencesKey("bk_err")
        val checkUpdates = booleanPreferencesKey("check_updates")
        val lastSync = longPreferencesKey("last_sync")
        val lastErr = stringPreferencesKey("last_err")
        val lastVersion = longPreferencesKey("last_version")
        val images = booleanPreferencesKey("images")
        val notes = booleanPreferencesKey("notes")
        val stopAfter = intPreferencesKey("stop_after")
        val updInterval = intPreferencesKey("upd_interval")
        val updAuto = booleanPreferencesKey("upd_auto")
        val updWifi = booleanPreferencesKey("upd_wifi")
        val updNotify = booleanPreferencesKey("upd_notify")
    }

    private fun Preferences.toSettings() = AppSettings(
        theme = this[K.theme] ?: 0,
        dynamicColor = this[K.dynamic] ?: true,
        pureBlack = this[K.black] ?: false,
        defaultDelayMs = this[K.delay] ?: 800,
        royalRoadDelayMs = this[K.rrDelay] ?: 2500,
        maxRetries = this[K.retries] ?: 5,
        librarySort = this[K.sort] ?: 0,
        libraryList = this[K.list] ?: false,
        autoSync = this[K.autoSync] ?: true,
        syncWifiOnly = this[K.wifi] ?: false,
        syncProvider = this[K.provider] ?: 0,
        webdavUrl = this[K.davUrl] ?: "",
        webdavUser = this[K.davUser] ?: "",
        supabaseUrl = this[K.sbUrl] ?: "",
        supabaseKey = this[K.sbKey] ?: "",
        supabaseEmail = this[K.sbEmail] ?: "",
        lastSync = this[K.lastSync] ?: 0,
        lastSyncError = this[K.lastErr] ?: "",
        lastSyncedVersion = this[K.lastVersion] ?: 0,
        includeImages = this[K.images] ?: true,
        includeAuthorNotes = this[K.notes] ?: true,
        stopAfterFailures = this[K.stopAfter] ?: 5,
        updateIntervalHours = this[K.updInterval] ?: 12,
        autoDownloadNew = this[K.updAuto] ?: true,
        updateWifiOnly = this[K.updWifi] ?: false,
        notifyNewChapters = this[K.updNotify] ?: true,
        autoBackupDays = this[K.bkDays] ?: 0,
        autoBackupFolder = this[K.bkFolder] ?: "",
        autoBackupKeep = this[K.bkKeep] ?: 5,
        backupIncludeChapters = this[K.bkChapters] ?: false,
        lastBackup = this[K.bkLast] ?: 0,
        lastBackupError = this[K.bkErr] ?: "",
        checkUpdates = this[K.checkUpdates] ?: true,
    )

    val flow: StateFlow<AppSettings> = context.dataStore.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val current: AppSettings get() = flow.value

    suspend fun awaitLoaded(): AppSettings = context.dataStore.data.first().toSettings()

    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val n = block(p.toSettings())
            p[K.theme] = n.theme
            p[K.dynamic] = n.dynamicColor
            p[K.black] = n.pureBlack
            p[K.delay] = n.defaultDelayMs
            p[K.rrDelay] = n.royalRoadDelayMs
            p[K.retries] = n.maxRetries
            p[K.sort] = n.librarySort
            p[K.list] = n.libraryList
            p[K.autoSync] = n.autoSync
            p[K.wifi] = n.syncWifiOnly
            p[K.provider] = n.syncProvider
            p[K.davUrl] = n.webdavUrl
            p[K.davUser] = n.webdavUser
            p[K.sbUrl] = n.supabaseUrl
            p[K.sbKey] = n.supabaseKey
            p[K.sbEmail] = n.supabaseEmail
            p[K.bkDays] = n.autoBackupDays
            p[K.bkFolder] = n.autoBackupFolder
            p[K.bkKeep] = n.autoBackupKeep
            p[K.bkChapters] = n.backupIncludeChapters
            p[K.bkLast] = n.lastBackup
            p[K.bkErr] = n.lastBackupError
            p[K.checkUpdates] = n.checkUpdates
            p[K.lastSync] = n.lastSync
            p[K.lastErr] = n.lastSyncError
            p[K.lastVersion] = n.lastSyncedVersion
            p[K.images] = n.includeImages
            p[K.notes] = n.includeAuthorNotes
            p[K.stopAfter] = n.stopAfterFailures
            p[K.updInterval] = n.updateIntervalHours
            p[K.updAuto] = n.autoDownloadNew
            p[K.updWifi] = n.updateWifiOnly
            p[K.updNotify] = n.notifyNewChapters
        }
    }
}
