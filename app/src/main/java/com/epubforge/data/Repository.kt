package com.epubforge.data

import com.epubforge.App
import com.epubforge.engine.ChapterContent
import com.epubforge.engine.DownloadOptions
import com.epubforge.engine.StoryInfo
import com.epubforge.engine.W2EEngine
import com.epubforge.net.Http
import com.epubforge.net.NativeProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.UUID

class Repository(private val app: App) {
    private val db get() = app.db
    private val storage get() = app.storage
    private val chapterLock = Mutex()

    fun newProxy(delayMs: Int = -1): NativeProxy {
        val s = app.settings.current
        return NativeProxy(app.http, app.gate, app.challenges, Http.userAgent(app), { s.maxRetries }).also {
            it.delayOverrideMs = delayMs.takeIf { d -> d >= 0 }?.toLong()
        }
    }

    /** Reads a story's details and chapter list from its site. */
    suspend fun fetchStoryInfo(url: String, log: (String) -> Unit = {}): StoryInfo {
        val proxy = newProxy()
        proxy.log = NativeProxy.LogListener(log)
        val s = app.settings.current
        return W2EEngine(app, proxy).analyse(url, DownloadOptions(s.includeImages, s.includeAuthorNotes, s.maxRetries))
    }

    /** Adds a story to the library (or returns it if it's already there). */
    suspend fun addStory(rawUrl: String, log: (String) -> Unit = {}): Story {
        val url = canonicalStoryUrl(rawUrl)
        db.stories().findByUrl(url)?.let { existing ->
            if (!existing.deleted && existing.inLibrary) return existing
        }
        val info = fetchStoryInfo(url, log)
        val now = System.currentTimeMillis()
        val existing = db.stories().findByUrl(url)
        val s = app.settings.current
        val story = Story(
            id = existing?.id ?: UUID.randomUUID().toString(),
            url = url,
            title = info.title.ifBlank { url },
            author = info.author,
            description = info.description,
            tags = info.subjects,
            site = hostOf(url),
            language = info.language,
            coverUrl = info.coverUrl,
            inLibrary = true,
            addedAt = now,
            updatedAt = now,
            lastCheckedAt = now,
            downloadImages = s.includeImages,
            includeAuthorNotes = s.includeAuthorNotes,
        )
        db.stories().upsert(story)
        applyStoryInfo(story.id, info, isUpdate = false)
        downloadCover(story)
        return story
    }

    /**
     * Merges a fresh chapter list into the database. Existing chapters keep their read and
     * download state; new chapters are added; chapters gone from the site are flagged.
     * Returns the URLs of chapters that are new.
     */
    suspend fun applyStoryInfo(storyId: String, info: StoryInfo, isUpdate: Boolean): List<String> = chapterLock.withLock {
        val story = db.stories().get(storyId) ?: return emptyList()
        val now = System.currentTimeMillis()
        val existing = db.chapters().list(storyId).associateBy { norm(it.url) }
        val seen = HashSet<String>()
        val newUrls = mutableListOf<String>()
        val rows = mutableListOf<Chapter>()
        info.chapters.forEachIndexed { i, ref ->
            val k = norm(ref.url)
            if (!seen.add(k)) return@forEachIndexed
            val old = existing[k]
            if (old == null) {
                newUrls += ref.url
                rows += Chapter(
                    storyId = storyId, url = ref.url, title = ref.title.ifBlank { "Chapter ${i + 1}" }, position = i,
                    fetchedAt = if (isUpdate) now else story.addedAt,
                )
            } else if (old.position != i || old.removed || (ref.title.isNotBlank() && old.title != ref.title)) {
                rows += old.copy(position = i, removed = false, title = ref.title.ifBlank { old.title })
            }
        }
        existing.forEach { (k, c) -> if (k !in seen && !c.removed) rows += c.copy(removed = true) }
        if (rows.isNotEmpty()) db.chapters().upsertAll(rows)

        val changed = (info.title.isNotBlank() && info.title != story.title) ||
            (info.author.isNotBlank() && info.author != story.author) ||
            (info.description.isNotBlank() && info.description != story.description) ||
            (info.coverUrl.isNotBlank() && info.coverUrl != story.coverUrl) ||
            (info.subjects.isNotBlank() && info.subjects != story.tags)
        db.stories().upsert(
            story.copy(
                title = info.title.ifBlank { story.title },
                author = info.author.ifBlank { story.author },
                description = info.description.ifBlank { story.description },
                tags = info.subjects.ifBlank { story.tags },
                coverUrl = info.coverUrl.ifBlank { story.coverUrl },
                language = info.language.ifBlank { story.language },
                lastCheckedAt = now,
                lastNewChaptersAt = if (isUpdate && newUrls.isNotEmpty()) now else story.lastNewChaptersAt,
                updatedAt = if (changed) now else story.updatedAt,
            )
        )
        if (info.coverUrl.isNotBlank() && (info.coverUrl != story.coverUrl || !storage.coverFile(storyId).exists())) {
            db.stories().get(storyId)?.let { downloadCover(it) }
        }
        newUrls
    }

    suspend fun saveDownloadedChapter(storyId: String, c: ChapterContent) = withContext(Dispatchers.IO) {
        val ch = db.chapters().list(storyId).firstOrNull { norm(it.url) == norm(c.url) }
        val url = ch?.url ?: c.url
        storage.saveChapter(storyId, url, c.title.ifBlank { ch?.title ?: "" }, c.body, c.images)
        db.chapters().setDownloaded(storyId, url, System.currentTimeMillis())
    }

    suspend fun downloadCover(story: Story) = withContext(Dispatchers.IO) {
        if (story.coverUrl.isBlank()) return@withContext
        try {
            val host = URI(story.coverUrl).host ?: ""
            val bytes = app.gate.run(host) {
                app.http.newCall(
                    Request.Builder().url(story.coverUrl).header("User-Agent", Http.userAgent(app)).build()
                ).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
            } ?: return@withContext
            val f = storage.coverFile(story.id)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            // Touch the story so cover images refresh.
            db.stories().get(story.id)?.let { db.stories().upsert(it.copy(lastCheckedAt = System.currentTimeMillis())) }
        } catch (e: Exception) {
            // Cover is optional.
        }
    }

    suspend fun setRead(storyId: String, urls: List<String>, read: Boolean) {
        db.chapters().setRead(storyId, urls, read, System.currentTimeMillis())
        app.sync.requestSync()
    }

    suspend fun markPreviousRead(storyId: String, position: Int) {
        db.chapters().markPreviousRead(storyId, position, System.currentTimeMillis())
        app.sync.requestSync()
    }

    suspend fun recordReading(storyId: String, chapter: Chapter, progress: Float, finished: Boolean, label: String? = null) {
        val now = System.currentTimeMillis()
        db.chapters().setProgress(storyId, chapter.url, progress, finished, now)
        db.history().upsert(HistoryEntry(storyId, chapter.url, label?.takeIf { it.isNotBlank() } ?: chapter.title, now))
    }

    /**
     * Makes the database agree with the files on disk: after restoring a backup, after Android's own
     * backup restores the database without the (excluded) chapter files, or after a crash mid-save.
     */
    suspend fun reconcileDownloads() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val byStory = db.chapters().all().groupBy { it.storyId }
        val books = db.stories().all().filter { it.isLocalBook }.associateBy { it.id }
        for ((storyId, chapters) in byStory) {
            val missing = mutableListOf<String>()
            val book = books[storyId]
            for (c in chapters) {
                // An imported book's single chapter is "downloaded" when its file is here.
                val exists = if (book != null) storage.bookFile(book)?.isFile == true else storage.chapterFile(storyId, c.url).isFile
                if (c.downloaded && !exists) missing += c.url
                else if (!c.downloaded && exists) db.chapters().setDownloaded(storyId, c.url, now)
            }
            if (missing.isNotEmpty()) missing.chunked(500).forEach { db.chapters().clearDownloaded(storyId, it) }
        }
    }

    suspend fun deleteDownloads(storyId: String, urls: List<String>) = withContext(Dispatchers.IO) {
        urls.forEach { storage.deleteChapter(storyId, it) }
        db.chapters().clearDownloaded(storyId, urls)
    }

    suspend fun removeFromLibrary(story: Story) = withContext(Dispatchers.IO) {
        app.downloads.cancelStory(story.id)
        storage.deleteStoryFiles(story.id)
        db.chapters().clearAllDownloaded(story.id)
        db.history().delete(story.id)
        db.stories().upsert(story.copy(inLibrary = false, deleted = true, updatedAt = System.currentTimeMillis()))
        app.sync.requestSync()
    }

    suspend fun updateStoryOptions(story: Story) {
        db.stories().upsert(story.copy(updatedAt = System.currentTimeMillis()))
    }

    /** Exports downloaded chapters (all, or [urls]) to an EPUB file. */
    suspend fun exportEpub(story: Story, urls: Set<String>?, out: File): Int = withContext(Dispatchers.IO) {
        val chapters = db.chapters().list(story.id)
            .filter { it.downloaded && (urls == null || it.url in urls) }
            .mapNotNull { c ->
                val f = storage.chapterFile(story.id, c.url)
                if (f.exists()) EpubExporter.ChapterFile(c.title, c.url, f) else null
            }
        EpubExporter.export(
            EpubExporter.Meta(
                title = story.title, author = story.author, language = story.language,
                description = story.description,
                subjects = story.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                sourceUrl = story.url,
            ),
            chapters, storage.imageDir(story.id), storage.coverFile(story.id).takeIf { it.exists() }, out,
        )
        chapters.size
    }

    companion object {
        fun hostOf(url: String): String = try { URI(url).host?.removePrefix("www.") ?: "" } catch (e: Exception) { "" }

        fun norm(url: String) = url.trim().substringBefore('#').trimEnd('/').lowercase()
            .replace("://royalroad.com", "://www.royalroad.com")

        /** Royal Road chapter links resolve to the story page. */
        fun canonicalStoryUrl(raw: String): String {
            val url = raw.trim().substringBefore('#')
            val rr = Regex("""^(https?://(?:www\.)?royalroad\.com/fiction/\d+(?:/[^/?]+)?)(?:/chapter/.*)?$""", RegexOption.IGNORE_CASE)
            return (rr.find(url)?.groupValues?.get(1) ?: url).trimEnd('/')
        }
    }
}
