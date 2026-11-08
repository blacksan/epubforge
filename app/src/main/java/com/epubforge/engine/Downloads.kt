package com.epubforge.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.epubforge.App
import com.epubforge.R
import com.epubforge.data.QueuedDownload
import com.epubforge.data.Story
import com.epubforge.net.Challenge
import com.epubforge.net.Http
import com.epubforge.net.NativeProxy
import com.epubforge.ui.ChallengeActivity
import com.epubforge.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/** Live state of one queued chapter. */
data class DownloadState(val status: Status, val message: String = "") {
    enum class Status { Queued, Downloading, Error }
}

/** What the downloader is doing right now. */
data class DownloaderStatus(
    val running: Boolean = false,
    val paused: Boolean = false,
    val storyId: String? = null,
    val storyTitle: String = "",
    val chapterUrl: String? = null,
    val message: String = "",
)

/**
 * Mihon-style download queue: chapters are queued (persisted in the database) and downloaded
 * one at a time through WebToEpub. Each story's table of contents is read once per run; the
 * next chapter is pulled from the live queue, so it can be reordered, cancelled or paused.
 */
class DownloadManager(private val app: App) {
    private val _status = MutableStateFlow(DownloaderStatus())
    val status: StateFlow<DownloaderStatus> = _status.asStateFlow()

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    private val _logs = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    /** Recent log lines per story. */
    val logs: StateFlow<Map<String, List<String>>> = _logs.asStateFlow()

    /** Queue snapshot readable from the JS thread. */
    private val snapshot = AtomicReference<List<QueuedDownload>>(emptyList())
    private val runLock = Mutex()
    @Volatile private var paused = false
    @Volatile private var currentEngine: W2EEngine? = null
    @Volatile private var currentJob: Job? = null

    init {
        app.scope.launch {
            app.db.queue().observe().collect { list ->
                snapshot.set(list)
                // Forget state for chapters no longer queued.
                val keys = list.map { key(it.storyId, it.chapterUrl) }.toSet()
                _states.update { m -> m.filterKeys { it in keys } }
            }
        }
    }

    fun key(storyId: String, url: String) = "$storyId|$url"

    private fun log(storyId: String, line: String) {
        _logs.update { m -> m + (storyId to ((m[storyId] ?: emptyList()) + line).takeLast(300)) }
    }

    fun logFor(storyId: String): List<String> = _logs.value[storyId] ?: emptyList()

    /** Queue chapters (in the given order). [front] puts them before everything else. */
    suspend fun enqueue(storyId: String, urls: List<String>, front: Boolean = false) {
        if (urls.isEmpty()) return
        val now = System.currentTimeMillis()
        val prio = if (front) app.db.queue().minPriority() - 1 else 0
        val existing = snapshot.get().filter { it.storyId == storyId }.map { it.chapterUrl }.toSet()
        if (front) urls.filter { it in existing }.forEach { app.db.queue().setPriority(storyId, it, prio) }
        app.db.queue().add(urls.filterNot { it in existing }.mapIndexed { i, u -> QueuedDownload(storyId, u, now + i, prio) })
        _states.update { m -> m + urls.associate { key(storyId, it) to (m[key(storyId, it)]?.takeIf { s -> s.status == DownloadState.Status.Downloading } ?: DownloadState(DownloadState.Status.Queued)) } }
        start()
    }

    fun start() {
        if (paused) return
        try {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        } catch (e: Exception) {
            // Background start not allowed; the update worker runs the queue itself.
        }
    }

    fun pause() {
        paused = true
        _status.update { it.copy(paused = true, message = "Paused") }
        currentEngine?.cancel()
    }

    fun resume() {
        paused = false
        _states.update { m -> m.mapValues { (_, s) -> if (s.status == DownloadState.Status.Error) s else DownloadState(DownloadState.Status.Queued) } }
        _status.update { it.copy(paused = false, message = "") }
        start()
    }

    /** Clears errors so failed chapters are tried again. */
    fun retryFailed(storyId: String? = null) {
        _states.update { m ->
            m.mapValues { (k, s) ->
                if (s.status == DownloadState.Status.Error && (storyId == null || k.startsWith("$storyId|"))) DownloadState(DownloadState.Status.Queued) else s
            }
        }
        resume()
    }

    suspend fun cancel(storyId: String, url: String) {
        app.db.queue().remove(storyId, url)
        if (_status.value.chapterUrl == url && _status.value.storyId == storyId) currentEngine?.cancel()
    }

    suspend fun cancelStory(storyId: String) {
        app.db.queue().removeStory(storyId)
        if (_status.value.storyId == storyId) currentEngine?.cancel()
    }

    suspend fun clear() {
        app.db.queue().clear()
        currentEngine?.cancel()
    }

    suspend fun moveToTop(storyId: String) {
        val prio = app.db.queue().minPriority() - 1
        snapshot.get().filter { it.storyId == storyId }.forEach { app.db.queue().setPriority(storyId, it.chapterUrl, prio) }
    }

    private fun isErrored(q: QueuedDownload) = _states.value[key(q.storyId, q.chapterUrl)]?.status == DownloadState.Status.Error

    /** Processes the queue until it's empty or paused. Called by [DownloadService] and the update worker. */
    suspend fun runQueue(onProgress: (DownloaderStatus) -> Unit) = runLock.withLock {
        while (!paused) {
            val first = snapshot.get().ifEmpty { app.db.queue().all().also { snapshot.set(it) } }
                .firstOrNull { !isErrored(it) } ?: break
            val story = app.db.stories().get(first.storyId)
            if (story == null || story.deleted) {
                app.db.queue().removeStory(first.storyId)
                continue
            }
            runStory(story, onProgress)
        }
        _status.update { it.copy(running = false, storyId = null, chapterUrl = null, storyTitle = "", message = if (paused) "Paused" else "") }
        onProgress(_status.value)
    }

    private suspend fun runStory(story: Story, onProgress: (DownloaderStatus) -> Unit) {
        val settings = app.settings.current
        val handedOut = HashSet<String>()
        var failuresInRow = 0
        val limit = settings.stopAfterFailures
        val proxy = NativeProxy(app.http, app.gate, app.challenges, Http.userAgent(app), { settings.maxRetries })
        proxy.delayOverrideMs = story.delayMs.takeIf { it >= 0 }?.toLong()
        proxy.listener = NativeProxy.StatusListener { msg ->
            _status.update { it.copy(message = msg) }
            log(story.id, msg)
            onProgress(_status.value)
        }
        proxy.log = NativeProxy.LogListener { msg -> log(story.id, msg) }
        val engine = W2EEngine(app, proxy)
        currentEngine = engine
        _status.update { DownloaderStatus(running = true, storyId = story.id, storyTitle = story.title, message = "Reading table of contents") }
        onProgress(_status.value)
        log(story.id, "Starting downloads for “${story.title}”")

        val next: () -> String? = next@{
            if (paused) return@next null
            val item = snapshot.get().firstOrNull {
                it.storyId == story.id && it.chapterUrl !in handedOut && !isErrored(it)
            } ?: return@next null
            handedOut += item.chapterUrl
            _states.update { m -> m + (key(story.id, item.chapterUrl) to DownloadState(DownloadState.Status.Downloading)) }
            _status.update { it.copy(chapterUrl = item.chapterUrl, message = "") }
            onProgress(_status.value)
            item.chapterUrl
        }

        try {
            engine.download(
                url = story.url,
                options = DownloadOptions(story.downloadImages, story.includeAuthorNotes, settings.maxRetries),
                next = next,
                onEvent = { e ->
                    when (e) {
                        is EngineEvent.Message -> if (e.level != "info") log(story.id, e.text.take(400))
                        is EngineEvent.Story -> app.scope.launch { app.repo.applyStoryInfo(story.id, e.info, isUpdate = true) }
                        is EngineEvent.ChapterStart -> log(story.id, "Downloading ${e.url}")
                        else -> Unit
                    }
                },
                onChapter = { e ->
                    when (e) {
                        is EngineEvent.ChapterDone -> {
                            val c = e.content
                            try {
                                app.repo.saveDownloadedChapter(story.id, c)
                                app.db.queue().remove(story.id, c.url)
                                failuresInRow = 0
                                log(story.id, "Saved “${c.title}”" + if (c.images.isNotEmpty()) " (${c.images.size} image${if (c.images.size == 1) "" else "s"})" else "")
                            } catch (ex: Exception) {
                                markError(story.id, c.url, "Could not save: ${ex.message}")
                            }
                        }
                        is EngineEvent.ChapterFailed -> {
                            failuresInRow++
                            markError(story.id, e.url, ErrorText.friendly(e.message))
                            log(story.id, "Failed ${e.url}: ${e.message}")
                            if (limit in 1..failuresInRow) {
                                log(story.id, "$failuresInRow chapters failed in a row — pausing downloads")
                                paused = true
                                _status.update { it.copy(paused = true, message = "Paused after $failuresInRow failed chapters in a row. The site may be blocking requests; try again later.") }
                                engine.cancel()
                            }
                        }
                        else -> Unit
                    }
                    onProgress(_status.value)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            log(story.id, "Failed: " + ((e as? EngineFailure)?.detail ?: msg))
            // The story itself couldn't be read: mark its queued chapters as failed so the queue moves on.
            snapshot.get().filter { it.storyId == story.id && it.chapterUrl !in handedOut }.forEach { markError(story.id, it.chapterUrl, msg) }
        } finally {
            // Chapters handed out but not finished (cancelled/paused) go back to queued.
            _states.update { m ->
                m.mapValues { (_, s) -> if (s.status == DownloadState.Status.Downloading) DownloadState(DownloadState.Status.Queued) else s }
            }
            currentEngine = null
            app.cookieJar.flush()
        }
    }

    private fun markError(storyId: String, url: String, message: String) {
        _states.update { m -> m + (key(storyId, url) to DownloadState(DownloadState.Status.Error, message)) }
    }

    val isPaused get() = paused
}

object Notifications {
    const val CHANNEL_WORK = "downloads"
    const val CHANNEL_CHALLENGE = "verification"
    const val CHANNEL_UPDATES = "updates"
    const val ID_WORK = 1
    const val ID_CHALLENGE = 2
    const val ID_UPDATES = 3

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_WORK, "Downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_CHALLENGE, "Site verification", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_UPDATES, "New chapters", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun work(context: Context, s: DownloaderStatus, remaining: Int): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "downloads"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(s.storyTitle.ifBlank { "Downloading chapters" })
            .setContentText(s.message.ifBlank { "$remaining chapter${if (remaining == 1) "" else "s"} left" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(open)
            .build()
    }

    fun update(context: Context, s: DownloaderStatus, remaining: Int) {
        if (allowed(context)) NotificationManagerCompat.from(context).notify(ID_WORK, work(context, s, remaining))
    }

    fun challenge(context: Context, c: Challenge) {
        if (!allowed(context)) return
        val intent = PendingIntent.getActivity(
            context, 1, ChallengeActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_CHALLENGE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Verification needed")
            .setContentText("${c.host} wants to check you're human. Tap to continue.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        NotificationManagerCompat.from(context).notify(ID_CHALLENGE, n)
    }

    fun cancelChallenge(context: Context) = NotificationManagerCompat.from(context).cancel(ID_CHALLENGE)

    /** "New chapters" notification after a library update. */
    fun newChapters(context: Context, found: List<Pair<Story, Int>>) {
        if (found.isEmpty() || !allowed(context)) return
        if (!(context.applicationContext as App).settings.current.notifyNewChapters) return
        val total = found.sumOf { it.second }
        val single = found.singleOrNull()?.first
        val intent = Intent(context, MainActivity::class.java).apply {
            if (single != null) putExtra(MainActivity.EXTRA_STORY, single.id) else putExtra(MainActivity.EXTRA_TAB, "updates")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            context, (single?.id ?: "updates").hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val lines = found.map { (s, n) -> "${s.title}: $n new chapter${if (n == 1) "" else "s"}" }
        val n = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(single?.title ?: "$total new chapters")
            .setContentText(if (single != null) "${found[0].second} new chapter${if (found[0].second == 1) "" else "s"}" else lines.joinToString(" · "))
            .setStyle(NotificationCompat.InboxStyle().also { st -> lines.forEach { st.addLine(it) } })
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(context).notify(ID_UPDATES, n)
    }
}

class DownloadService : LifecycleService() {
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val app = application as App
        ServiceCompat.startForeground(
            this, Notifications.ID_WORK, Notifications.work(this, app.downloads.status.value, 0),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (!running) {
            running = true
            lifecycleScope.launch {
                try {
                    app.downloads.runQueue { s ->
                        lifecycleScope.launch {
                            Notifications.update(this@DownloadService, s, app.db.queue().all().size)
                        }
                    }
                } finally {
                    running = false
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }
}
