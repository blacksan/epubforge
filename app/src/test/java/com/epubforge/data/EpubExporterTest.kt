package com.epubforge.data

import com.adobe.epubcheck.api.EpubCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile

class EpubExporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
    )

    /** Chapter files exactly as Storage.saveChapter writes them from WebToEpub output. */
    private fun chapter(dir: File, n: Int, withImage: Boolean): File {
        val img = if (withImage) "<div><img src=\"../img/k${n}_0000_pic.png\" /><!--  https://i.imgur.com/pic.png  --></div>" else ""
        val f = File(dir, "c$n.xhtml")
        f.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"utf-8\"/><title>Chapter $n</title></head><body>" +
                "<div xmlns=\"http://www.w3.org/1999/xhtml\" class=\"chapter-content\"><h1>Chapter $n: The Test</h1>" +
                "<div class=\"chapter-inner chapter-content\"><p>Paragraph of chapter $n.</p><p>Two &amp; more.</p>$img<p></p></div></div>" +
                "</body></html>"
        )
        return f
    }

    @Test
    fun exportIsValidEpub() {
        val chDir = tmp.newFolder("ch")
        val imgDir = tmp.newFolder("img")
        File(imgDir, "k2_0000_pic.png").writeBytes(png)
        val cover = tmp.newFile("cover").apply { writeBytes(png) }
        val chapters = (1..3).map { n ->
            EpubExporter.ChapterFile("Chapter $n: The Test", "https://www.royalroad.com/fiction/1/s/chapter/$n/c", chapter(chDir, n, n == 2))
        }
        val out = File(tmp.root, "book.epub")
        EpubExporter.export(
            EpubExporter.Meta(
                title = "Test & Story", author = "Tester", language = "en",
                description = "Line one.\n\nLine two.", subjects = listOf("LitRPG", "Comedy"),
                sourceUrl = "https://www.royalroad.com/fiction/1/s",
            ),
            chapters, imgDir, cover, out,
        )
        ZipFile(out).use { zip ->
            assertEquals("mimetype", zip.entries().nextElement().name)
            assertTrue(zip.getEntry("OEBPS/Images/k2_0000_pic.png") != null)
            val c2 = String(zip.getInputStream(zip.getEntry("OEBPS/Text/0002.xhtml")).readBytes())
            assertTrue(c2, c2.contains("../Images/k2_0000_pic.png"))
        }
        assertEquals("epubcheck reported errors (see test output)", 0, EpubCheck(out).doValidate())
    }
}
