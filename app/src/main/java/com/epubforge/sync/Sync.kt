package com.epubforge.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.epubforge.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object SyncProvider {
    const val OFF = 0
    const val WEBDAV = 1
    const val SUPABASE = 2
    fun name(p: Int) = when (p) { WEBDAV -> "WebDAV"; SUPABASE -> "Supabase"; else -> "Off" }
}

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val message: String) : SyncState
    data class Error(val message: String) : SyncState
}

@Serializable
private data class StoredSession(val accessToken: String, val refreshToken: String, val expiresAt: Long, val userId: String, val email: String)

class SyncManager(private val app: App) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(2, TimeUnit.MINUTES).writeTimeout(2, TimeUnit.MINUTES)
        .build()
    private val merge = LibraryMerge(app)

    val enabled: Boolean get() = app.settings.current.syncProvider != SyncProvider.OFF

    // ---------- Supabase session

    fun supabaseClient(url: String = app.settings.current.supabaseUrl, key: String = app.settings.current.supabaseKey) =
        SupabaseClient(http, url, key)

    fun storeSession(s: SupabaseSession?) {
        app.secrets.supabaseSession = s?.let {
            SyncJson.encodeToString(StoredSession.serializer(), StoredSession(it.accessToken, it.refreshToken, it.expiresAt, it.userId, it.email))
        } ?: ""
    }

    fun storedSession(): SupabaseSession? = app.secrets.supabaseSession.takeIf { it.isNotBlank() }?.let {
        try {
            val s = SyncJson.decodeFromString(StoredSession.serializer(), it)
            SupabaseSession(s.accessToken, s.refreshToken, s.expiresAt, s.userId, s.email)
        } catch (_: Exception) { null }
    }

    private suspend fun freshSession(): SupabaseSession {
        val s = storedSession() ?: throw SyncAuthError("Not signed in to Supabase.")
        if (System.currentTimeMillis() < s.expiresAt) return s
        val refreshed = try {
            supabaseClient().refresh(s)
        } catch (e: SyncAuthError) {
            storeSession(null)
            throw SyncAuthError("Your Supabase sign-in expired. Sign in again.")
        }
        storeSession(refreshed)
        return refreshed
    }

    fun backend(): SyncBackend? {
        val s = app.settings.current
        return when (s.syncProvider) {
            SyncProvider.WEBDAV -> WebDavBackend(http, s.webdavUrl, s.webdavUser, app.secrets.webdavPassword)
            SyncProvider.SUPABASE -> SupabaseBackend(supabaseClient()) { freshSession() }
            else -> null
        }
    }

    // ---------- scheduling

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(if (app.settings.current.syncWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /** Queue a background sync soon (after reading, adding or removing stories). */
    fun requestSync() {
        val s = app.settings.current
        if (!enabled || !s.autoSync) return
        WorkManager.getInstance(app).enqueueUniqueWork(
            "sync-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints())
                .setInitialDelay(20, TimeUnit.SECONDS) // batch bursts of page turns
                .build(),
        )
    }

    fun schedulePeriodic() {
        val wm = WorkManager.getInstance(app)
        if (!enabled || !app.settings.current.autoSync) {
            wm.cancelUniqueWork("sync-periodic")
            return
        }
        wm.enqueueUniquePeriodicWork(
            "sync-periodic", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(constraints()).build(),
        )
    }

    // ---------- running

    suspend fun syncNow(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val backend = backend() ?: return@withContext Result.success(Unit)
            try {
                _state.value = SyncState.Running("Connecting to ${backend.label}")
                runSync(backend)
                app.settings.update { it.copy(lastSync = System.currentTimeMillis(), lastSyncError = "") }
                _state.value = SyncState.Idle
                Result.success(Unit)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                app.settings.update { it.copy(lastSyncError = msg) }
                _state.value = SyncState.Error(msg)
                Result.failure(e)
            }
        }
    }

    private suspend fun runSync(backend: SyncBackend) {
        repeat(4) { attempt ->
            _state.value = SyncState.Running("Reading library")
            val remote = backend.read()
            val manifest = remote?.text?.takeIf { it.isNotBlank() }?.let {
                try { SyncJson.decodeFromString(SyncManifest.serializer(), it) } catch (_: Exception) { null }
            }
            _state.value = SyncState.Running("Merging")
            val arrived = if (manifest != null) merge.apply(manifest) else emptyList()
            _state.value = SyncState.Running("Saving")
            val out = SyncJson.encodeToString(SyncManifest.serializer(), merge.snapshot())
            try {
                backend.write(out, remote)
                merge.fetchMissingCovers(arrived)
                return
            } catch (e: SyncConflict) {
                if (attempt == 3) throw e
            }
        }
    }

    /** Verifies the current settings work, creating folders/rows if needed, then syncs. */
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val b = backend() ?: return@withContext Result.success(Unit)
            _state.value = SyncState.Running("Checking ${b.label}")
            b.check()
            _state.value = SyncState.Idle
            schedulePeriodic()
            syncNow()
        } catch (e: Exception) {
            _state.value = SyncState.Error(e.message ?: "Could not connect")
            Result.failure(e)
        }
    }

    suspend fun disconnect() {
        WorkManager.getInstance(app).cancelUniqueWork("sync-periodic")
        WorkManager.getInstance(app).cancelUniqueWork("sync-now")
        if (app.settings.current.syncProvider == SyncProvider.SUPABASE) {
            storedSession()?.let { s -> withContext(Dispatchers.IO) { supabaseClient().signOut(s) } }
        }
        storeSession(null)
        app.secrets.webdavPassword = ""
        app.settings.update { it.copy(syncProvider = SyncProvider.OFF, lastSyncError = "", lastSync = 0) }
        _state.value = SyncState.Idle
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        if (!app.sync.enabled) return Result.success()
        return app.sync.syncNow().fold(
            onSuccess = { Result.success() },
            onFailure = { if (it is SyncAuthError) Result.failure() else Result.retry() },
        )
    }
}
