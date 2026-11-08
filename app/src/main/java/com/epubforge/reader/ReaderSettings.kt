package com.epubforge.reader

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.text.font.FontFamily
import org.json.JSONObject

data class ReaderTheme(val name: String, val bg: String, val fg: String, val link: String, val dark: Boolean)

/** Reading themes, modelled on Readest's palette. */
object ReaderThemes {
    val all = listOf(
        ReaderTheme("Light", "#FFFFFF", "#171717", "#0066CC", false),
        ReaderTheme("Sepia", "#F1E8D0", "#5B4636", "#8B5A2B", false),
        ReaderTheme("Paper", "#F2F0EB", "#3A3A3A", "#2F6CA3", false),
        ReaderTheme("Green", "#D7E8D2", "#24382A", "#1E5631", false),
        ReaderTheme("Solarized", "#FDF6E3", "#586E75", "#268BD2", false),
        ReaderTheme("Gruvbox", "#282828", "#EBDBB2", "#83A598", true),
        ReaderTheme("Nord", "#2E3440", "#D8DEE9", "#88C0D0", true),
        ReaderTheme("Dark", "#1E1E1E", "#D0D0D0", "#8AB4F8", true),
        ReaderTheme("Black", "#000000", "#BDBDBD", "#8AB4F8", true),
    )
    fun get(i: Int) = all[i.coerceIn(0, all.lastIndex)]
}

data class ReaderFont(val name: String, val stack: String, val preview: FontFamily)

object ReaderFonts {
    val all = listOf(
        ReaderFont("Original", "", FontFamily.Default),
        ReaderFont("Serif", "'Noto Serif', Georgia, serif", FontFamily.Serif),
        ReaderFont("Sans", "Roboto, 'Noto Sans', sans-serif", FontFamily.SansSerif),
        ReaderFont("Mono", "'Droid Sans Mono', monospace", FontFamily.Monospace),
    )
    fun get(i: Int) = all[i.coerceIn(0, all.lastIndex)]
}

data class ReaderSettings(
    val theme: Int = 0,
    val fontSize: Int = 18,
    val font: Int = 1,
    val fontWeight: Int = 400,
    val lineHeight: Float = 1.55f,
    val paragraphSpacing: Float = 0.6f,
    val indent: Float = 0f,
    val letterSpacing: Float = 0f,
    val gap: Int = 6,
    val marginTop: Int = 44,
    val marginBottom: Int = 40,
    val flow: String = "paginated",
    val justify: Boolean = true,
    val hyphenate: Boolean = true,
    val animated: Boolean = true,
    val volumeKeys: Boolean = true,
    val keepScreenOn: Boolean = true,
    val showHeader: Boolean = true,
    val showFooter: Boolean = true,
) {
    fun toJson(): String {
        val t = ReaderThemes.get(theme)
        val f = ReaderFonts.get(font)
        return JSONObject()
            .put("bg", t.bg).put("fg", t.fg).put("link", t.link).put("dark", t.dark)
            .put("fontSize", fontSize).put("fontStack", f.stack).put("fontWeight", fontWeight)
            .put("lineHeight", lineHeight.toDouble()).put("paragraphSpacing", paragraphSpacing.toDouble())
            .put("indent", indent.toDouble()).put("letterSpacing", letterSpacing.toDouble())
            .put("gap", gap).put("marginTop", marginTop).put("marginBottom", marginBottom).put("columns", 1)
            .put("flow", flow).put("justify", justify).put("hyphenate", hyphenate).put("animated", animated)
            .toString()
    }
}

class ReaderPrefs(private val context: Context) {
    private val sp = context.getSharedPreferences("reader2", Context.MODE_PRIVATE)

    fun load(): ReaderSettings {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val d = ReaderSettings(theme = if (night) 7 else 0)
        return ReaderSettings(
            theme = sp.getInt("theme", d.theme),
            fontSize = sp.getInt("fontSize", d.fontSize),
            font = sp.getInt("font", d.font),
            fontWeight = sp.getInt("fontWeight", d.fontWeight),
            lineHeight = sp.getFloat("lineHeight", d.lineHeight),
            paragraphSpacing = sp.getFloat("paragraphSpacing", d.paragraphSpacing),
            indent = sp.getFloat("indent", d.indent),
            letterSpacing = sp.getFloat("letterSpacing", d.letterSpacing),
            gap = sp.getInt("gap", d.gap),
            marginTop = sp.getInt("marginTop", d.marginTop),
            marginBottom = sp.getInt("marginBottom", d.marginBottom),
            flow = sp.getString("flow", d.flow) ?: d.flow,
            justify = sp.getBoolean("justify", d.justify),
            hyphenate = sp.getBoolean("hyphenate", d.hyphenate),
            animated = sp.getBoolean("animated", d.animated),
            volumeKeys = sp.getBoolean("volumeKeys", d.volumeKeys),
            keepScreenOn = sp.getBoolean("keepScreenOn", d.keepScreenOn),
            showHeader = sp.getBoolean("showHeader", d.showHeader),
            showFooter = sp.getBoolean("showFooter", d.showFooter),
        )
    }

    fun save(s: ReaderSettings) {
        sp.edit()
            .putInt("theme", s.theme).putInt("fontSize", s.fontSize).putInt("font", s.font).putInt("fontWeight", s.fontWeight)
            .putFloat("lineHeight", s.lineHeight).putFloat("paragraphSpacing", s.paragraphSpacing)
            .putFloat("indent", s.indent).putFloat("letterSpacing", s.letterSpacing)
            .putInt("gap", s.gap).putInt("marginTop", s.marginTop).putInt("marginBottom", s.marginBottom)
            .putString("flow", s.flow).putBoolean("justify", s.justify).putBoolean("hyphenate", s.hyphenate)
            .putBoolean("animated", s.animated).putBoolean("volumeKeys", s.volumeKeys).putBoolean("keepScreenOn", s.keepScreenOn)
            .putBoolean("showHeader", s.showHeader).putBoolean("showFooter", s.showFooter)
            .apply()
    }
}
