package com.epubforge.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Scales down oversized images when they're saved. Phone screens are at most ~1440 px wide, but
 * web novels sometimes embed multi-megapixel photos, and the reader decodes every image at full
 * size: a 4000×3000 picture alone takes ~48 MB of RAM. Smaller files are also quicker to page
 * through and to export.
 */
object ImageSlim {
    const val CHAPTER_MAX_WIDTH = 1440
    const val CHAPTER_MAX_PIXELS = 6_000_000
    const val COVER_MAX_WIDTH = 600
    const val COVER_MAX_PIXELS = 600 * 900

    /** Shrinks [f] in place if it's larger than the limits. Keeps the format (GIFs are left alone). */
    fun shrink(f: File, maxWidth: Int = CHAPTER_MAX_WIDTH, maxPixels: Int = CHAPTER_MAX_PIXELS): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        val mime = bounds.outMimeType
        val scale = min(maxWidth.toFloat() / w, sqrt(maxPixels.toFloat() / (w.toFloat() * h)))
        if (w <= 0 || h <= 0 || mime == null || mime == "image/gif" || scale >= 1f) {
            false
        } else {
            // Decode at a reduced power-of-two size first so huge images never need full-size memory.
            var sample = 1
            while (w / (sample * 2) >= w * scale && h / (sample * 2) >= h * scale) sample *= 2
            val decoded = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            if (decoded == null) false else {
                val tw = (w * scale).roundToInt().coerceAtLeast(1)
                val th = (h * scale).roundToInt().coerceAtLeast(1)
                val scaled = if (decoded.width > tw) Bitmap.createScaledBitmap(decoded, tw, th, true) else decoded
                val (format, quality) = when (mime) {
                    "image/png" -> Bitmap.CompressFormat.PNG to 100
                    "image/webp" -> (if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP) to 85
                    else -> Bitmap.CompressFormat.JPEG to 85
                }
                val tmp = File(f.path + ".slim")
                tmp.outputStream().use { scaled.compress(format, quality, it) }
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
                // Pixels, not bytes, decide memory use, so keep the smaller image even if the file grew.
                if (tmp.length() > 0) tmp.renameTo(f) else { tmp.delete(); false }
            }
        }
    } catch (_: Throwable) {
        // OutOfMemoryError or a corrupt image: keep the original.
        File(f.path + ".slim").delete()
        false
    }

    fun shrinkCover(f: File) = shrink(f, COVER_MAX_WIDTH, COVER_MAX_PIXELS)
}
