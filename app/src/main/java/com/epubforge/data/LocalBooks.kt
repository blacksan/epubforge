package com.epubforge.data

import android.net.Uri
import android.provider.OpenableColumns
import com.epubforge.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Ebook files the reader can open (foliate-js formats). */
object BookFormats {
    val extensions = listOf("epub", "kepub", "mobi", "azw", "azw3", "prc", "fb2", "fbz", "cbz", "pdf")
    val mimeTypes = arrayOf(
        "application/epub+zip", "application/x-mobipocket-ebook", "application/vnd.amazon.ebook",
        "application/x-fictionbook+xml", "application/x-zip-compressed-fb2", "application/vnd.comicbook+zip",
        "application/x-cbz", "application/pdf", "application/octet-stream", "application/zip",
    )

    fun label(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "epub", "kepub" -> "EPUB"
        "mobi", "prc" -> "MOBI"
        "azw", "azw3" -> "Kindle (AZW3)"
        "fb2", "fbz" -> "FictionBook"
        "cbz" -> "Comic (CBZ)"
        "pdf" -> "PDF"
        else -> "Ebook"
    }

    fun mime(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "epub", "kepub" -> "application/epub+zip"
        "mobi", "prc", "azw", "azw3" -> "application/x-mobipocket-ebook"
        "fb2" -> "application/x-fictionbook+xml"
        "fbz" -> "application/x-zip-compressed-fb2"
        "cbz" -> "application/vnd.comicbook+zip"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
}

class UnsupportedBook(message: String) : Exception(message)

data class BookMeta(
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val language: String = "",
    val subjects: List<String> = emptyList(),
    val cover: ByteArray? = null,
)

/** Imports ebook files into the library. Each book is a story with a single "book" chapter. */
class LocalBooks(private val app: App) {
    companion object {
        const val BOOK_CHAPTER = "book"
        fun urlFor(sha1: String) = "local:$sha1"
    }

    private val db get() = app.db
    private val storage get() = app.storage

    /** Copies [uri] into the library. Returns the story (an existing one if the same file was imported before). */
    suspend fun import(uri: Uri, intoStory: Story? = null): Story = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val tmp = File.createTempFile("import", ".$ext", app.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-1")
            app.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            } ?: throw UnsupportedBook("Couldn't open that file.")
            if (tmp.length() == 0L) throw UnsupportedBook("That file is empty.")
            val kind = sniff(tmp, ext) ?: throw UnsupportedBook(
                "“$name” isn't a supported ebook. EpubForge opens EPUB, MOBI, AZW3, FB2, CBZ and PDF files."
            )
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val url = urlFor(sha)
            val now = System.currentTimeMillis()
            val fileName = Storage.safe(name.substringBeforeLast('.').ifBlank { "book" }).take(60) + "." + (ext.takeIf { it in BookFormats.extensions } ?: kind)

            // Re-attaching a missing file to a story synced from another device.
            if (intoStory != null) {
                placeFile(intoStory.id, tmp, fileName)
                val updated = intoStory.copy(localFile = fileName, updatedAt = now)
                db.stories().upsert(updated)
                ensureChapter(updated, now)
                return@withContext updated
            }

            db.stories().findByUrl(url)?.let { existing ->
                val revived = existing.copy(inLibrary = true, deleted = false, localFile = fileName, updatedAt = now)
                placeFile(existing.id, tmp, fileName)
                db.stories().upsert(revived)
                ensureChapter(revived, now)
                return@withContext revived
            }

            val meta = try { readMeta(tmp, kind) } catch (_: Exception) { BookMeta() }
            val id = UUID.randomUUID().toString()
            placeFile(id, tmp, fileName)
            meta.cover?.let { bytes -> storage.coverFile(id).apply { parentFile?.mkdirs() }.writeBytes(bytes) }
            val story = Story(
                id = id, url = url,
                title = meta.title.ifBlank { titleFromFileName(name) },
                author = meta.author,
                description = meta.description,
                tags = meta.subjects.joinToString(", "),
                site = BookFormats.label(fileName),
                language = meta.language,
                inLibrary = true, addedAt = now, updatedAt = now,
                localFile = fileName,
            )
            db.stories().upsert(story)
            ensureChapter(story, now)
            app.sync.requestSync()
            story
        } finally {
            tmp.delete()
        }
    }

    private fun placeFile(storyId: String, tmp: File, fileName: String) {
        val dir = storage.bookDir(storyId)
        dir.deleteRecursively()
        dir.mkdirs()
        val dest = File(dir, fileName)
        if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true)
    }

    private suspend fun ensureChapter(story: Story, now: Long) {
        val existing = db.chapters().get(story.id, BOOK_CHAPTER)
        db.chapters().upsertAll(listOf(
            (existing ?: Chapter(storyId = story.id, url = BOOK_CHAPTER, title = story.title, position = 0, fetchedAt = now, updatedAt = now))
                .copy(downloadedAt = now)
        ))
    }

    /** Updates title, author and cover reported by the reader (for formats parsed only there). */
    suspend fun applyReaderMeta(story: Story, title: String, author: String, cover: ByteArray?) = withContext(Dispatchers.IO) {
        val current = db.stories().get(story.id) ?: return@withContext
        val fromFileName = current.title == titleFromFileName(current.localFile)
        var updated = current
        if (title.isNotBlank() && fromFileName) updated = updated.copy(title = title.trim())
        if (author.isNotBlank() && current.author.isBlank()) updated = updated.copy(author = author.trim())
        if (updated != current) db.stories().upsert(updated.copy(updatedAt = System.currentTimeMillis()))
        val coverFile = storage.coverFile(story.id)
        if (cover != null && cover.isNotEmpty() && !coverFile.exists()) coverFile.apply { parentFile?.mkdirs() }.writeBytes(cover)
    }

    private fun displayName(uri: Uri): String {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "book"
    }

    private fun titleFromFileName(name: String) =
        name.substringBeforeLast('.').replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifBlank { "Untitled" }

    /** Identifies the format from the content (falls back to the extension). Returns a file extension. */
    private fun sniff(f: File, ext: String): String? {
        val head = ByteArray(80)
        val n = f.inputStream().use { it.read(head) }
        if (n >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) {
            return try {
                ZipFile(f).use { z ->
                    when {
                        z.getEntry("META-INF/container.xml") != null -> "epub"
                        z.entries().asSequence().any { it.name.endsWith(".fb2") } -> "fbz"
                        z.entries().asSequence().any { it.name.lowercase().matches(Regex(".*\\.(jpe?g|png|gif|webp|avif)$")) } -> "cbz"
                        else -> null
                    }
                }
            } catch (_: Exception) { null }
        }
        if (n >= 5 && String(head, 0, 5, Charsets.ISO_8859_1) == "%PDF-") return "pdf"
        if (n >= 68 && String(head, 60, 8, Charsets.ISO_8859_1).let { it == "BOOKMOBI" || it == "TEXtREAd" }) return if (ext in listOf("azw3", "azw")) ext else "mobi"
        val text = String(head, 0, n, Charsets.UTF_8)
        if (text.contains("<?xml") || text.contains("<FictionBook")) return if (ext == "fb2" || text.contains("FictionBook")) "fb2" else null
        return null
    }

    // Android's parser doesn't fetch external entities; we still turn off entity expansion.
    private fun xml(bytes: ByteArray): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        try { isExpandEntityReferences = false } catch (_: Exception) { }
    }.newDocumentBuilder().parse(bytes.inputStream())

    private fun Element.children(local: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = getElementsByTagNameNS("*", local)
        for (i in 0 until nodes.length) out += nodes.item(i) as Element
        return out
    }

    private fun readMeta(f: File, kind: String): BookMeta = when (kind) {
        "epub" -> epubMeta(f)
        "fb2" -> fb2Meta(f.readBytes())
        "fbz" -> ZipFile(f).use { z -> z.entries().asSequence().firstOrNull { it.name.endsWith(".fb2") }?.let { fb2Meta(z.getInputStream(it).readBytes()) } } ?: BookMeta()
        else -> BookMeta() // MOBI/AZW3/CBZ/PDF: the reader reports title and cover on first open.
    }

    private fun epubMeta(f: File): BookMeta = ZipFile(f).use { z ->
        val container = xml(z.getInputStream(z.getEntry("META-INF/container.xml")).readBytes())
        val opfPath = container.documentElement.children("rootfile").firstOrNull()?.getAttribute("full-path") ?: return BookMeta()
        val opf = xml(z.getInputStream(z.getEntry(opfPath) ?: return BookMeta()).readBytes()).documentElement
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        fun dc(name: String) = opf.children(name).map { it.textContent.trim() }.filter { it.isNotEmpty() }
        val items = opf.children("item")
        val coverId = opf.children("meta").firstOrNull { it.getAttribute("name") == "cover" }?.getAttribute("content")
        val coverItem = items.firstOrNull { it.getAttribute("properties").split(' ').contains("cover-image") }
            ?: items.firstOrNull { coverId != null && it.getAttribute("id") == coverId }
            ?: items.firstOrNull { it.getAttribute("media-type").startsWith("image/") && it.getAttribute("id").contains("cover", true) }
        val cover = coverItem?.getAttribute("href")?.let { href ->
            val path = java.net.URI(null, null, base + href, null).normalize().path ?: (base + href)
            (z.getEntry(path) ?: z.getEntry(java.net.URLDecoder.decode(path, "UTF-8")))?.let { z.getInputStream(it).readBytes() }
        }
        BookMeta(
            title = dc("title").firstOrNull().orEmpty(),
            author = dc("creator").joinToString(", "),
            description = dc("description").firstOrNull().orEmpty().replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim(),
            language = dc("language").firstOrNull().orEmpty(),
            subjects = dc("subject"),
            cover = cover,
        )
    }

    private fun fb2Meta(bytes: ByteArray): BookMeta {
        val doc = xml(bytes).documentElement
        val info = doc.children("title-info").firstOrNull() ?: return BookMeta()
        val authors = info.children("author").map { a ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { a.children(it).firstOrNull()?.textContent?.trim() }.joinToString(" ")
        }.filter { it.isNotBlank() }
        val coverRef = info.children("coverpage").firstOrNull()?.children("image")?.firstOrNull()?.let { img ->
            (0 until img.attributes.length).map { img.attributes.item(it) }.firstOrNull { it.localName == "href" }?.nodeValue?.removePrefix("#")
        }
        val cover = coverRef?.let { ref ->
            doc.children("binary").firstOrNull { it.getAttribute("id") == ref }?.textContent?.let {
                try { android.util.Base64.decode(it.trim(), android.util.Base64.DEFAULT) } catch (_: Exception) { null }
            }
        }
        return BookMeta(
            title = info.children("book-title").firstOrNull()?.textContent?.trim().orEmpty(),
            author = authors.joinToString(", "),
            description = info.children("annotation").firstOrNull()?.textContent?.replace(Regex("\\s+"), " ")?.trim().orEmpty(),
            language = info.children("lang").firstOrNull()?.textContent?.trim().orEmpty(),
            subjects = info.children("genre").map { it.textContent.trim() },
            cover = cover,
        )
    }
}
