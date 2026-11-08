package com.epubforge.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** A story in the library (like a manga in Mihon). */
@Serializable
@Entity(tableName = "stories", indices = [Index(value = ["url"], unique = true)])
data class Story(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    val tags: String = "",
    val site: String = "",
    val language: String = "",
    val coverUrl: String = "",
    val inLibrary: Boolean = true,
    val addedAt: Long,
    /** Last change to the story's own fields (used by sync). */
    val updatedAt: Long,
    val lastCheckedAt: Long = 0,
    /** When an update last found new chapters. */
    val lastNewChaptersAt: Long = 0,
    /** Per-story download options. */
    val downloadImages: Boolean = true,
    val includeAuthorNotes: Boolean = true,
    /** Delay between requests in ms, or -1 for the site default from Settings. */
    val delayMs: Int = -1,
    val deleted: Boolean = false,
    /** For an imported ebook: its file name under the story folder's book/ directory. Empty for web stories. */
    val localFile: String = "",
    /** For an imported ebook: the reader's last position (an EPUB CFI). */
    val readerPosition: String = "",
) {
    val isLocalBook: Boolean get() = localFile.isNotEmpty()
}

/** One chapter of a story, in site order. */
@Serializable
@Entity(tableName = "chapters", primaryKeys = ["storyId", "url"], indices = [Index(value = ["storyId", "position"])])
data class Chapter(
    val storyId: String,
    val url: String,
    val title: String,
    /** Order on the site (0-based). */
    val position: Int,
    /** When the app first saw this chapter. */
    val fetchedAt: Long,
    val read: Boolean = false,
    /** Reading position within the chapter (0..1). */
    val progress: Float = 0f,
    val lastReadAt: Long = 0,
    val bookmark: Boolean = false,
    /** When the chapter was saved for offline reading (0 = not downloaded). */
    val downloadedAt: Long = 0,
    /** No longer listed on the site. */
    val removed: Boolean = false,
    /** Last change to read state (used by sync). */
    val updatedAt: Long = 0,
) {
    val downloaded: Boolean get() = downloadedAt > 0
}

/** Latest reading activity per story (Mihon's History tab). */
@Serializable
@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey val storyId: String,
    val chapterUrl: String,
    val chapterTitle: String,
    val readAt: Long,
)

/** A chapter waiting to be downloaded. */
@Entity(tableName = "download_queue", primaryKeys = ["storyId", "chapterUrl"])
data class QueuedDownload(
    val storyId: String,
    val chapterUrl: String,
    val addedAt: Long,
    /** Lower runs first. */
    val priority: Int = 0,
)

data class LibraryItem(
    @Embedded val story: Story,
    val total: Int,
    val unread: Int,
    val downloaded: Int,
    val lastReadAt: Long,
)

data class UpdateItem(
    @Embedded val chapter: Chapter,
    val storyTitle: String,
)

data class HistoryItem(
    @Embedded val entry: HistoryEntry,
    val storyTitle: String,
    val storyAuthor: String,
)

@Dao
interface StoryDao {
    @Query(
        """SELECT s.*,
            (SELECT COUNT(*) FROM chapters c WHERE c.storyId = s.id AND c.removed = 0) AS total,
            (SELECT COUNT(*) FROM chapters c WHERE c.storyId = s.id AND c.removed = 0 AND c.read = 0) AS unread,
            (SELECT COUNT(*) FROM chapters c WHERE c.storyId = s.id AND c.downloadedAt > 0) AS downloaded,
            (SELECT IFNULL(MAX(c.lastReadAt), 0) FROM chapters c WHERE c.storyId = s.id) AS lastReadAt
        FROM stories s WHERE s.inLibrary = 1 AND s.deleted = 0"""
    )
    fun observeLibrary(): Flow<List<LibraryItem>>

    @Query("SELECT * FROM stories WHERE id = :id")
    fun observe(id: String): Flow<Story?>

    @Query("SELECT * FROM stories WHERE id = :id")
    suspend fun get(id: String): Story?

    @Query("SELECT * FROM stories WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): Story?

    @Query("SELECT * FROM stories WHERE inLibrary = 1 AND deleted = 0")
    suspend fun library(): List<Story>

    @Query("SELECT * FROM stories")
    suspend fun all(): List<Story>

    @Upsert
    suspend fun upsert(story: Story)

    @Query("UPDATE stories SET readerPosition = :position WHERE id = :id")
    suspend fun setReaderPosition(id: String, position: String)

    @Query("UPDATE stories SET lastCheckedAt = :at WHERE id = :id")
    suspend fun setChecked(id: String, at: Long)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE storyId = :storyId ORDER BY position")
    fun observe(storyId: String): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE storyId = :storyId ORDER BY position")
    suspend fun list(storyId: String): List<Chapter>

    @Query("SELECT * FROM chapters WHERE storyId = :storyId AND url = :url")
    suspend fun get(storyId: String, url: String): Chapter?

    @Query("SELECT * FROM chapters")
    suspend fun all(): List<Chapter>

    @Upsert
    suspend fun upsertAll(chapters: List<Chapter>)

    @Query("UPDATE chapters SET read = :read, progress = CASE WHEN :read THEN 1.0 ELSE 0.0 END, updatedAt = :at WHERE storyId = :storyId AND url IN (:urls)")
    suspend fun setRead(storyId: String, urls: List<String>, read: Boolean, at: Long)

    @Query("UPDATE chapters SET read = 1, progress = 1.0, updatedAt = :at WHERE storyId = :storyId AND position < :position AND read = 0")
    suspend fun markPreviousRead(storyId: String, position: Int, at: Long)

    @Query("UPDATE chapters SET progress = :progress, lastReadAt = :at, updatedAt = :at, read = CASE WHEN :read THEN 1 ELSE read END WHERE storyId = :storyId AND url = :url")
    suspend fun setProgress(storyId: String, url: String, progress: Float, read: Boolean, at: Long)

    @Query("UPDATE chapters SET downloadedAt = :at WHERE storyId = :storyId AND url = :url")
    suspend fun setDownloaded(storyId: String, url: String, at: Long)

    @Query("UPDATE chapters SET downloadedAt = 0 WHERE storyId = :storyId AND url IN (:urls)")
    suspend fun clearDownloaded(storyId: String, urls: List<String>)

    @Query("UPDATE chapters SET downloadedAt = 0 WHERE storyId = :storyId")
    suspend fun clearAllDownloaded(storyId: String)

    @Query(
        """SELECT c.*, s.title AS storyTitle FROM chapters c JOIN stories s ON s.id = c.storyId
        WHERE s.inLibrary = 1 AND s.deleted = 0 AND c.removed = 0 AND c.fetchedAt > s.addedAt + 60000
        ORDER BY c.fetchedAt DESC, c.position DESC LIMIT 500"""
    )
    fun observeUpdates(): Flow<List<UpdateItem>>
}

@Dao
interface HistoryDao {
    @Query(
        """SELECT h.*, s.title AS storyTitle, s.author AS storyAuthor FROM history h
        JOIN stories s ON s.id = h.storyId WHERE s.deleted = 0 ORDER BY h.readAt DESC"""
    )
    fun observe(): Flow<List<HistoryItem>>

    @Query("SELECT * FROM history WHERE storyId = :storyId")
    fun observeFor(storyId: String): Flow<HistoryEntry?>

    @Query("SELECT * FROM history WHERE storyId = :storyId")
    suspend fun get(storyId: String): HistoryEntry?

    @Query("SELECT * FROM history")
    suspend fun all(): List<HistoryEntry>

    @Upsert
    suspend fun upsert(entry: HistoryEntry)

    @Query("DELETE FROM history WHERE storyId = :storyId")
    suspend fun delete(storyId: String)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM download_queue ORDER BY priority, addedAt, rowid")
    fun observe(): Flow<List<QueuedDownload>>

    @Query("SELECT * FROM download_queue ORDER BY priority, addedAt, rowid")
    suspend fun all(): List<QueuedDownload>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(items: List<QueuedDownload>)

    @Query("DELETE FROM download_queue WHERE storyId = :storyId AND chapterUrl = :url")
    suspend fun remove(storyId: String, url: String)

    @Query("DELETE FROM download_queue WHERE storyId = :storyId")
    suspend fun removeStory(storyId: String)

    @Query("DELETE FROM download_queue")
    suspend fun clear()

    @Query("UPDATE download_queue SET priority = :priority WHERE storyId = :storyId AND chapterUrl = :url")
    suspend fun setPriority(storyId: String, url: String, priority: Int)

    @Query("SELECT IFNULL(MIN(priority), 0) FROM download_queue")
    suspend fun minPriority(): Int
}

@Database(
    entities = [Story::class, Chapter::class, HistoryEntry::class, QueuedDownload::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stories(): StoryDao
    abstract fun chapters(): ChapterDao
    abstract fun history(): HistoryDao
    abstract fun queue(): QueueDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "epubforge.db")
                .addMigrations(MIGRATION_4_5)
                // Versions 1-3 stored EPUB conversions; the offline-reader library started fresh at 4.
                .fallbackToDestructiveMigrationFrom(1, 2, 3)
                .build()

        /** Imported ebooks. */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stories ADD COLUMN localFile TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE stories ADD COLUMN readerPosition TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
