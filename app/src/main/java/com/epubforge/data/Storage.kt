package com.epubforge.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Offline files, one folder per story:
 *   stories/<storyId>/cover
 *   stories/<storyId>/ch/<chapterKey>.xhtml   (chapter text, images referenced as ../img/…)
 *   stories/<storyId>/img/<chapterKey>_<name> (chapter images)
 */
class Storage(context: Context) {
    val root = File(context.filesDir, "stories").apply { mkdirs() }
    private val incoming = File(context.cacheDir, "incoming").apply { mkdirs() }

    fun storyDir(storyId: String) = File(root, storyId)
    fun coverFile(storyId: String) = File(storyDir(storyId), "cover")
    fun chapterFile(storyId: String, url: String) = File(File(storyDir(storyId), "ch"), "${key(url)}.xhtml")
    fun imageDir(storyId: String) = File(storyDir(storyId), "img")
    /** Imported ebooks keep their original file here. */
    fun bookDir(storyId: String) = File(storyDir(storyId), "book")
    fun bookFile(story: Story): File? = story.localFile.takeIf { it.isNotEmpty() }?.let { File(bookDir(story.id), it) }

    fun newIncomingFile(): File = File.createTempFile("w2e", ".bin", incoming)

    /**
     * Saves a chapter's cleaned XHTML (from WebToEpub) and its images.
     * Image references arrive as `@@IMG@@/<name>`.
     */
    fun saveChapter(storyId: String, url: String, title: String, body: String, images: List<Pair<String, File>>) {
        val k = key(url)
        val imgDir = imageDir(storyId).apply { mkdirs() }
        for ((name, file) in images) {
            val dest = File(imgDir, "${k}_${safe(name)}")
            if (!file.renameTo(dest)) {
                file.copyTo(dest, overwrite = true)
                file.delete()
            }
        }
        var html = body
        for ((name, _) in images) html = html.replace("@@IMG@@/$name", "../img/${k}_${safe(name)}")
        val doc = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<!DOCTYPE html>\n")
            append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"utf-8\"/>")
            append("<title>").append(escape(title)).append("</title></head><body>")
            append(html)
            append("</body></html>")
        }
        val dest = chapterFile(storyId, url)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeText(doc)
        tmp.renameTo(dest)
    }

    fun deleteChapter(storyId: String, url: String) {
        chapterFile(storyId, url).delete()
        val prefix = key(url) + "_"
        imageDir(storyId).listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    fun deleteStoryFiles(storyId: String) {
        storyDir(storyId).deleteRecursively()
    }

    fun deleteAllChapters(storyId: String) {
        File(storyDir(storyId), "ch").deleteRecursively()
        imageDir(storyId).deleteRecursively()
    }

    fun storySize(storyId: String): Long =
        storyDir(storyId).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        /** Stable short file key for a chapter URL. */
        fun key(url: String): String {
            val d = MessageDigest.getInstance("SHA-1").digest(url.trim().toByteArray())
            return d.take(8).joinToString("") { "%02x".format(it) }
        }

        fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
