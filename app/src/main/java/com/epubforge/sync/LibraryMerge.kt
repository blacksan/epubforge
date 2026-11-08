package com.epubforge.sync

import com.epubforge.App
import com.epubforge.data.Chapter
import com.epubforge.data.HistoryEntry
import com.epubforge.data.Story
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything that follows you between devices: the library, chapter lists with read state, and
 * reading history. Downloaded chapter files stay per device (like Mihon).
 */
@Serializable
data class SyncManifest(
    val version: Int = 2,
    val updatedAt: Long = 0,
    val stories: List<Story> = emptyList(),
    val chapters: List<SyncedChapter> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
)

@Serializable
data class SyncedChapter(
    val storyId: String,
    val url: String,
    val title: String,
    val position: Int,
    val fetchedAt: Long,
    val read: Boolean,
    val progress: Float,
    val lastReadAt: Long,
    val bookmark: Boolean,
    val removed: Boolean,
    val updatedAt: Long,
)

val SyncJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Merges a manifest into the local database. Newest change wins for each story and chapter. */
class LibraryMerge(private val app: App) {

    suspend fun snapshot(): SyncManifest {
        val db = app.db
        return SyncManifest(
            updatedAt = System.currentTimeMillis(),
            stories = db.stories().all(),
            chapters = db.chapters().all().map {
                SyncedChapter(it.storyId, it.url, it.title, it.position, it.fetchedAt, it.read, it.progress, it.lastReadAt, it.bookmark, it.removed, it.updatedAt)
            },
            history = db.history().all(),
        )
    }

    /**
     * The same story added separately on two devices gets two ids but has one URL. Maps the
     * incoming ids onto the local ones so read progress and files line up.
     */
    suspend fun idMap(remote: SyncManifest): Map<String, String> {
        val byUrl = app.db.stories().all().associateBy { it.url }
        return remote.stories.mapNotNull { r -> byUrl[r.url]?.takeIf { it.id != r.id }?.let { r.id to it.id } }.toMap()
    }

    private fun SyncManifest.remapped(map: Map<String, String>): SyncManifest = if (map.isEmpty()) this else copy(
        stories = stories.map { it.copy(id = map[it.id] ?: it.id) },
        chapters = chapters.map { it.copy(storyId = map[it.storyId] ?: it.storyId) },
        history = history.map { it.copy(storyId = map[it.storyId] ?: it.storyId) },
    )

    /** Applies [incoming] locally. Returns stories that arrived or changed (for fetching covers). */
    suspend fun apply(incoming: SyncManifest): List<Story> {
        val db = app.db
        val remote = incoming.remapped(idMap(incoming))
        val localStories = db.stories().all().associateBy { it.id }
        val changed = mutableListOf<Story>()
        for (r in remote.stories) {
            val l = localStories[r.id]
            if (l != null && r.updatedAt <= l.updatedAt) continue
            db.stories().upsert(r.copy(lastCheckedAt = l?.lastCheckedAt ?: 0))
            if (r.deleted) {
                app.storage.deleteStoryFiles(r.id)
                db.chapters().clearAllDownloaded(r.id)
                db.queue().removeStory(r.id)
            } else {
                changed += r
            }
        }

        val localChapters = db.chapters().all().associateBy { it.storyId + "|" + it.url }
        val known = db.stories().all().associateBy { it.id }
        val toWrite = mutableListOf<Chapter>()
        for (rc in remote.chapters) {
            if (known[rc.storyId] == null) continue
            val lc = localChapters[rc.storyId + "|" + rc.url]
            if (lc == null) {
                toWrite += Chapter(
                    storyId = rc.storyId, url = rc.url, title = rc.title, position = rc.position, fetchedAt = rc.fetchedAt,
                    read = rc.read, progress = rc.progress, lastReadAt = rc.lastReadAt, bookmark = rc.bookmark,
                    removed = rc.removed, updatedAt = rc.updatedAt,
                )
            } else if (rc.updatedAt > lc.updatedAt) {
                toWrite += lc.copy(
                    read = rc.read, progress = rc.progress, lastReadAt = maxOf(lc.lastReadAt, rc.lastReadAt),
                    bookmark = rc.bookmark, updatedAt = rc.updatedAt,
                )
            }
        }
        if (toWrite.isNotEmpty()) db.chapters().upsertAll(toWrite)

        val localHistory = db.history().all().associateBy { it.storyId }
        for (h in remote.history) {
            val l = localHistory[h.storyId]
            if ((l == null || h.readAt > l.readAt) && known[h.storyId]?.deleted == false) db.history().upsert(h)
        }
        return changed
    }

    /** Fetch covers for stories that arrived from elsewhere (best effort). */
    suspend fun fetchMissingCovers(stories: List<Story>) {
        stories.filter { !it.deleted && !app.storage.coverFile(it.id).exists() }.forEach {
            try { app.repo.downloadCover(it) } catch (_: Exception) { }
        }
    }
}
