package com.epubforge.engine

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.epubforge.App
import com.epubforge.R
import com.epubforge.data.Story
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

data class UpdateProgress(
    val running: Boolean = false,
    val current: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val lastResult: String = "",
)

/** Checks library stories for new chapters (Mihon's library update). */
class UpdateManager(private val app: App) {
    private val _progress = MutableStateFlow(UpdateProgress())
    val progress: StateFlow<UpdateProgress> = _progress.asStateFlow()
    private val mutex = Mutex()

    /**
     * @param storyIds stories to check, or null for the whole library
     * @param inline run resulting downloads in this coroutine (background worker)
     */
    suspend fun check(storyIds: List<String>? = null, inline: Boolean = false): List<Pair<Story, Int>> {
        if (mutex.isLocked) return emptyList()
        return mutex.withLock {
            val settings = app.settings.current
            val stories = app.db.stories().library().filter { !it.isLocalBook && (storyIds == null || it.id in storyIds) }
            val found = mutableListOf<Pair<Story, Int>>()
            stories.forEachIndexed { i, story ->
                _progress.value = UpdateProgress(true, story.title, i, stories.size)
                try {
                    val info = app.repo.fetchStoryInfo(story.url)
                    val newUrls = app.repo.applyStoryInfo(story.id, info, isUpdate = true)
                    if (newUrls.isNotEmpty()) {
                        found += story to newUrls.size
                        if (settings.autoDownloadNew) app.downloads.enqueue(story.id, newUrls)
                    }
                } catch (e: Exception) {
                    Log.w("Updates", "Check failed for ${story.title}", e)
                }
            }
            _progress.value = UpdateProgress(
                running = false,
                lastResult = if (found.isEmpty()) "No new chapters" else "${found.sumOf { it.second }} new chapters in ${found.size} stor${if (found.size == 1) "y" else "ies"}",
            )
            Notifications.newChapters(app, found)
            if (inline && found.isNotEmpty() && settings.autoDownloadNew) {
                app.downloads.runQueue { }
            }
            found
        }
    }

    fun schedule() {
        val s = app.settings.current
        val wm = WorkManager.getInstance(app)
        if (s.updateIntervalHours <= 0) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (s.updateWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UpdateWorker>(s.updateIntervalHours.toLong(), TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(s.updateIntervalHours.toLong(), TimeUnit.HOURS)
                .build(),
        )
    }

    companion object {
        const val PERIODIC = "library-updates"
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        app.settings.awaitLoaded()
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            // Not allowed from the background on some Android versions; run within normal limits.
        }
        return try {
            app.updates.check(null, inline = true)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Checking for new chapters")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifications.ID_UPDATES + 100, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_UPDATES + 100, n)
        }
    }
}
