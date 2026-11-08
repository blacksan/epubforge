package com.epubforge.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.epubforge.App
import com.epubforge.BuildConfig
import com.epubforge.sync.LibraryMerge
import com.epubforge.sync.SyncJson
import com.epubforge.sync.SyncManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupInfo(
    val format: Int = 1,
    val app: String = "EpubForge",
    val appVersion: String = "",
    val createdAt: Long = 0,
    val includesChapters: Boolean = false,
    val library: SyncManifest = SyncManifest(),
)

data class RestoreResult(val stories: Int, val chapterFiles: Int)

/**
 * Backup files are plain zip archives:
 *   backup.json            library, chapter lists, read state, history
 *   stories/<id>/cover     covers
 *   stories/<id>/ch/…      downloaded chapters (optional)
 *   stories/<id>/img/…     their images (optional)
 *   stories/<id>/book/…    imported ebook files (optional)
 * Restoring merges into the current library rather than wiping it.
 */
class BackupManager(private val app: App) {
    private val merge = LibraryMerge(app)

    fun fileName(now: Long = System.currentTimeMillis()): String =
        "EpubForge_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(now)) + ".zip"

    suspend fun create(out: OutputStream, includeChapters: Boolean): Int = withContext(Dispatchers.IO) {
        val manifest = merge.snapshot()
        val info = BackupInfo(appVersion = BuildConfig.VERSION_NAME, createdAt = System.currentTimeMillis(), includesChapters = includeChapters, library = manifest)
        var files = 0
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(SyncJson.encodeToString(BackupInfo.serializer(), info).toByteArray())
            zip.closeEntry()
            for (story in manifest.stories.filter { !it.deleted }) {
                val dir = app.storage.storyDir(story.id)
                val cover = app.storage.coverFile(story.id)
                if (cover.isFile) { zip.addFile("stories/${story.id}/cover", cover); files++ }
                if (includeChapters) {
                    for (sub in listOf("ch", "img", "book")) {
                        File(dir, sub).listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.forEach {
                            zip.addFile("stories/${story.id}/$sub/${it.name}", it); files++
                        }
                    }
                }
            }
        }
        files
    }

    private fun ZipOutputStream.addFile(name: String, f: File) {
        putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
        f.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    suspend fun restore(input: InputStream): RestoreResult = withContext(Dispatchers.IO) {
        // Pass 1 needs backup.json before any files, but zip order isn't guaranteed: stage files first.
        val staging = File(app.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var info: BackupInfo? = null
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    if (e.isDirectory || name.contains("..") || name.startsWith("/")) continue
                    if (name == "backup.json") {
                        info = SyncJson.decodeFromString(BackupInfo.serializer(), zip.readBytes().decodeToString())
                    } else if (name.startsWith("stories/")) {
                        val dest = File(staging, name)
                        if (!dest.canonicalPath.startsWith(staging.canonicalPath)) continue
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            val backup = info ?: throw IllegalArgumentException("This isn't an EpubForge backup (backup.json is missing).")
            val idMap = merge.idMap(backup.library)
            val arrived = merge.apply(backup.library)

            // Move files into place for stories that exist after the merge.
            var chapterFiles = 0
            val known = app.db.stories().all().filter { !it.deleted }.associateBy { it.id }
            File(staging, "stories").listFiles()?.forEach { storyDir ->
                val id = idMap[storyDir.name] ?: storyDir.name
                if (known[id] == null) return@forEach
                val target = app.storage.storyDir(id)
                storyDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(storyDir).path
                    val dest = File(target, rel)
                    if (rel == "cover" && dest.exists()) return@forEach
                    dest.parentFile?.mkdirs()
                    if (!f.renameTo(dest)) { f.copyTo(dest, overwrite = true) }
                    if (rel.startsWith("ch/")) chapterFiles++
                }
            }
            app.repo.reconcileDownloads()
            merge.fetchMissingCovers(arrived)
            app.sync.requestSync()
            RestoreResult(backup.library.stories.count { !it.deleted }, chapterFiles)
        } finally {
            staging.deleteRecursively()
        }
    }

    // ---------- automatic backups to a user-chosen folder

    fun schedule() {
        val s = app.settings.current
        val wm = WorkManager.getInstance(app)
        if (s.autoBackupDays <= 0 || s.autoBackupFolder.isBlank()) {
            wm.cancelUniqueWork("auto-backup")
            return
        }
        wm.enqueueUniquePeriodicWork(
            "auto-backup", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(s.autoBackupDays.toLong(), TimeUnit.DAYS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                .build(),
        )
    }

    /** Writes a backup into the chosen folder and keeps only the newest few. */
    suspend fun runAutomatic(): Result<String> = withContext(Dispatchers.IO) {
        val s = app.settings.current
        try {
            val tree = DocumentFile.fromTreeUri(app, Uri.parse(s.autoBackupFolder))
                ?.takeIf { it.canWrite() } ?: throw IllegalStateException("Can't write to the backup folder. Choose it again.")
            val name = fileName()
            val doc = tree.createFile("application/zip", name.removeSuffix(".zip")) ?: throw IllegalStateException("Couldn't create a file in the backup folder.")
            app.contentResolver.openOutputStream(doc.uri, "w")!!.use { create(it, s.backupIncludeChapters) }
            tree.listFiles()
                .filter { it.name?.startsWith("EpubForge_") == true && it.name?.endsWith(".zip") == true }
                .sortedByDescending { it.name }
                .drop(s.autoBackupKeep.coerceAtLeast(1))
                .forEach { it.delete() }
            app.settings.update { it.copy(lastBackup = System.currentTimeMillis(), lastBackupError = "") }
            Result.success(name)
        } catch (e: Exception) {
            app.settings.update { it.copy(lastBackupError = e.message ?: "Backup failed") }
            Result.failure(e)
        }
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        return if (app.backups.runAutomatic().isSuccess) Result.success() else Result.retry()
    }
}
