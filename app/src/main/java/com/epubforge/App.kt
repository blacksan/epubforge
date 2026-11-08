package com.epubforge

import android.app.Application
import com.epubforge.backup.BackupManager
import com.epubforge.data.AppDatabase
import com.epubforge.data.Secrets
import com.epubforge.data.Repository
import com.epubforge.data.Storage
import com.epubforge.data.SettingsStore
import com.epubforge.engine.DownloadManager
import com.epubforge.engine.Notifications
import com.epubforge.engine.UpdateManager
import com.epubforge.net.ChallengeCoordinator
import com.epubforge.net.Http
import com.epubforge.net.RequestGate
import com.epubforge.net.WebViewCookieJar
import com.epubforge.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: SettingsStore
    lateinit var secrets: Secrets
    lateinit var backups: BackupManager
    lateinit var books: com.epubforge.data.LocalBooks
    lateinit var db: AppDatabase
    lateinit var storage: Storage
    lateinit var repo: Repository
    lateinit var cookieJar: WebViewCookieJar
    lateinit var http: OkHttpClient
    lateinit var gate: RequestGate
    lateinit var challenges: ChallengeCoordinator
    lateinit var downloads: DownloadManager
    lateinit var sync: SyncManager
    lateinit var updates: UpdateManager

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this, scope)
        secrets = Secrets(this)
        db = AppDatabase.create(this)
        storage = Storage(this)
        cookieJar = WebViewCookieJar()
        http = Http.client(cookieJar)
        gate = RequestGate(settings)
        challenges = ChallengeCoordinator()
        repo = Repository(this)
        downloads = DownloadManager(this)
        sync = SyncManager(this)
        updates = UpdateManager(this)
        backups = BackupManager(this)
        books = com.epubforge.data.LocalBooks(this)
        Notifications.createChannels(this)
        challenges.onChallenge = { Notifications.challenge(this, it) }

        scope.launch {
            // Wait for stored settings before scheduling background sync.
            settings.awaitLoaded()
            kotlinx.coroutines.delay(500)
            // Android's backup may have restored the database without the chapter files.
            // Checking every chapter file is slow on big libraries, so only do it when the database may
            // not match the files: first launch after an install, update or Android restore. The marker
            // lives in no_backup/, so Android's backup never brings it along.
            val marker = java.io.File(noBackupFilesDir, "files-checked-${BuildConfig.VERSION_CODE}")
            if (!marker.exists()) try {
                repo.reconcileDownloads()
                noBackupFilesDir.listFiles { f -> f.name.startsWith("files-checked-") }?.forEach { it.delete() }
                marker.createNewFile()
                // Covers aren't in Android's cloud backup; fetch them for stories that arrived that way.
                val missing = db.stories().library().filter { !storage.storyDir(it.id).exists() }
                if (missing.isNotEmpty()) launch { com.epubforge.sync.LibraryMerge(this@App).fetchMissingCovers(missing) }
            } catch (_: Exception) { }
            sync.schedulePeriodic()
            updates.schedule()
            backups.schedule()
            // Resume a download queue left over from last time.
            if (db.queue().all().isNotEmpty()) downloads.start()
            launch {
                settings.flow.map { Triple(it.updateIntervalHours, it.updateWifiOnly, 0) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { updates.schedule() }
            }
            launch {
                settings.flow.map { Triple(it.autoBackupDays, it.autoBackupFolder, 0) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { backups.schedule() }
            }
            settings.flow.map { Triple(it.syncProvider, it.autoSync, it.syncWifiOnly) }
                .distinctUntilChanged()
                .drop(1)
                .collect { sync.schedulePeriodic() }
        }
    }
}
