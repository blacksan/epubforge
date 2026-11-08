package com.epubforge.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds an EPUB 3 (with an EPUB 2 table of contents for older readers) from downloaded chapters. */
object EpubExporter {

    data class Meta(
        val title: String,
        val author: String,
        val language: String,
        val description: String,
        val subjects: List<String>,
        val sourceUrl: String,
    )

    data class ChapterFile(val title: String, val url: String, val file: File)

    /**
     * @param imageDir folder the chapters' `../img/…` references point into
     * @param cover cover image file, if any
     */
    fun export(meta: Meta, chapters: List<ChapterFile>, imageDir: File?, cover: File?, out: File) {
        require(chapters.isNotEmpty()) { "No downloaded chapters to export" }
        val id = "urn:uuid:" + UUID.nameUUIDFromBytes(meta.sourceUrl.toByteArray())
        val lang = meta.language.ifBlank { "en" }
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

        val manifest = StringBuilder()
        val spine = StringBuilder()
        val sources = StringBuilder()
        val navPoints = StringBuilder()
        val navItems = StringBuilder()
        val files = LinkedHashMap<String, ByteArray>()
        val usedImages = LinkedHashMap<String, String>() // file name -> media type

        var coverHref: String? = null
        if (cover != null && cover.exists() && cover.length() > 0) {
            val bytes = cover.readBytes()
            val type = imageType(bytes)
            if (type != null) {
                coverHref = "Images/cover.${ext(type)}"
                files["OEBPS/$coverHref"] = bytes
                manifest.append("<item id=\"cover-image\" href=\"$coverHref\" media-type=\"$type\" properties=\"cover-image\"/>")
                files["OEBPS/Text/cover.xhtml"] = page(
                    meta.title, lang,
                    "<div style=\"text-align:center;\"><img src=\"../$coverHref\" alt=\"${esc(meta.title)}\" style=\"max-width:100%;max-height:100vh;\"/></div>",
                ).toByteArray()
                manifest.append("<item id=\"cover\" href=\"Text/cover.xhtml\" media-type=\"application/xhtml+xml\"/>")
                spine.append("<itemref idref=\"cover\"/>")
            }
        }

        // Title / information page.
        val info = buildString {
            append("<h1>").append(esc(meta.title)).append("</h1>")
            if (meta.author.isNotBlank()) append("<p><b>").append(esc(meta.author)).append("</b></p>")
            if (meta.description.isNotBlank()) {
                meta.description.split(Regex("\n\\s*\n|\n")).filter { it.isNotBlank() }
                    .forEach { append("<p>").append(esc(it.trim())).append("</p>") }
            }
            append("<p>Source: <a href=\"").append(esc(meta.sourceUrl)).append("\">").append(esc(meta.sourceUrl)).append("</a></p>")
        }
        files["OEBPS/Text/info.xhtml"] = page(meta.title, lang, info).toByteArray()
        manifest.append("<item id=\"info\" href=\"Text/info.xhtml\" media-type=\"application/xhtml+xml\"/>")
        spine.append("<itemref idref=\"info\"/>")
        var order = 1
        navPoints.append(navPoint(order++, "Information", "Text/info.xhtml"))
        navItems.append("<li><a href=\"Text/info.xhtml\">Information</a></li>")

        val imgRef = Regex("""\.\./img/([A-Za-z0-9._-]+)""")
        chapters.forEachIndexed { i, ch ->
            val name = "%04d.xhtml".format(i + 1)
            var text = ch.file.readText()
            text = imgRef.replace(text) { m ->
                val fname = m.groupValues[1]
                val src = imageDir?.let { File(it, fname) }
                if (src != null && src.exists()) {
                    val type = imageType(src.readBytes()) ?: "image/jpeg"
                    if (usedImages.put(fname, type) == null) files["OEBPS/Images/$fname"] = src.readBytes()
                }
                "../Images/$fname"
            }
            text = ensureXhtml(text, ch.title, lang)
            files["OEBPS/Text/$name"] = text.toByteArray()
            val itemId = "c%04d".format(i + 1)
            manifest.append("<item id=\"$itemId\" href=\"Text/$name\" media-type=\"application/xhtml+xml\"/>")
            spine.append("<itemref idref=\"$itemId\"/>")
            sources.append("<dc:source id=\"id.$itemId\">${esc(ch.url)}</dc:source>")
            val label = ch.title.ifBlank { "Chapter ${i + 1}" }
            navPoints.append(navPoint(order++, label, "Text/$name"))
            navItems.append("<li><a href=\"Text/$name\">${esc(label)}</a></li>")
        }
        usedImages.forEach { (fname, type) ->
            manifest.append("<item id=\"img_${fname.replace(Regex("[^A-Za-z0-9_-]"), "_")}\" href=\"Images/$fname\" media-type=\"$type\"/>")
        }

        val nav = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="$lang" xml:lang="$lang"><head><meta charset="utf-8"/><title>${esc(meta.title)}</title></head>
<body><nav epub:type="toc" id="toc"><h1>Contents</h1><ol>$navItems</ol></nav></body></html>"""
        files["OEBPS/nav.xhtml"] = nav.toByteArray()
        manifest.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")

        val ncx = """<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1" xml:lang="$lang"><head><meta name="dtb:uid" content="$id"/><meta name="dtb:depth" content="1"/><meta name="dtb:totalPageCount" content="0"/><meta name="dtb:maxPageNumber" content="0"/></head>
<docTitle><text>${esc(meta.title)}</text></docTitle><navMap>$navPoints</navMap></ncx>"""
        files["OEBPS/toc.ncx"] = ncx.toByteArray()
        manifest.append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>")

        val subjects = meta.subjects.filter { it.isNotBlank() }.joinToString("") { "<dc:subject>${esc(it.trim())}</dc:subject>" }
        val opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId" xml:lang="$lang">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="BookId">$id</dc:identifier>
<dc:title>${esc(meta.title)}</dc:title>
<dc:language>$lang</dc:language>
${if (meta.author.isNotBlank()) "<dc:creator>${esc(meta.author)}</dc:creator>" else ""}
${if (meta.description.isNotBlank()) "<dc:description>${esc(meta.description)}</dc:description>" else ""}
$subjects
<dc:source>${esc(meta.sourceUrl)}</dc:source>
$sources
<meta property="dcterms:modified">$modified</meta>
${if (coverHref != null) "<meta name=\"cover\" content=\"cover-image\"/>" else ""}
</metadata>
<manifest>$manifest</manifest>
<spine toc="ncx">$spine</spine>
</package>"""
        files["OEBPS/content.opf"] = opf.toByteArray()

        val container = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            val mt = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = mt.size.toLong()
                crc = CRC32().apply { update(mt) }.value
            })
            zip.write(mt); zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/container.xml")); zip.write(container.toByteArray()); zip.closeEntry()
            for ((name, bytes) in files) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    private fun navPoint(order: Int, label: String, href: String) =
        "<navPoint id=\"np$order\" playOrder=\"$order\"><navLabel><text>${esc(label)}</text></navLabel><content src=\"$href\"/></navPoint>"

    private fun page(title: String, lang: String, body: String) = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" lang="$lang" xml:lang="$lang"><head><meta charset="utf-8"/><title>${esc(title)}</title></head><body>$body</body></html>"""

    /** Stored chapters are already XHTML documents; add language and make sure the shell is right. */
    private fun ensureXhtml(text: String, title: String, lang: String): String {
        val body = text.substringAfter("<body>", "").substringBeforeLast("</body>", "")
        val content = if (body.isNotEmpty()) body else text
        return page(title.ifBlank { "Chapter" }, lang, content)
    }

    fun imageType(b: ByteArray): String? = when {
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "image/png"
        b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "image/gif"
        b.size > 12 && b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() -> "image/webp"
        b.size > 5 && String(b, 0, minOf(b.size, 256)).trimStart().let { it.startsWith("<svg") || it.startsWith("<?xml") } -> "image/svg+xml"
        else -> null
    }

    private fun ext(type: String) = when (type) {
        "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; "image/svg+xml" -> "svg"; else -> "jpg"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
