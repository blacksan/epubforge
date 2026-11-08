@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.epubforge.reader

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.epubforge.App
import com.epubforge.data.Chapter
import com.epubforge.data.Story
import com.epubforge.engine.ASSET_HOST
import com.epubforge.engine.AssetServer
import com.epubforge.ui.theme.EpubForgeTheme
import com.epubforge.ui.BookActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import android.net.Uri
import kotlin.math.roundToInt
import java.io.FileInputStream
import java.text.DateFormat
import java.util.Date

/** Position report from the reader page. */
private data class Position(
    val index: Int = -1,
    val fraction: Float = 0f,
    val page: Int = 0,
    val pages: Int = 0,
    val atEnd: Boolean = false,
    val bookFraction: Float = 0f,
    /** Imported ebooks: location and current table-of-contents entry. */
    val cfi: String = "",
    val tocLabel: String = "",
    val tocHref: String = "",
)

private data class TocEntry(val label: String, val href: String, val depth: Int)

/**
 * Offline reader for a story's downloaded chapters, built on Readest's fork of foliate-js.
 * Reads chapter by chapter, saves the position in each chapter, and marks chapters read
 * when their last page is reached.
 */
class ReaderActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_STORY = "story"
        private const val EXTRA_CHAPTER = "chapter"
        private val SAFE_NAME = Regex("^[A-Za-z0-9._-]+$")
        private const val SAVE_EVERY_MS = 30_000L
        /** With "keep screen on", the screen may still sleep after this long without turning a page. */
        private const val AWAKE_AFTER_LAST_TURN_MS = 5 * 60_000L

        fun intent(context: Context, storyId: String, chapterUrl: String? = null) =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_STORY, storyId).putExtra(EXTRA_CHAPTER, chapterUrl)
    }

    private val app get() = application as App
    private lateinit var prefs: ReaderPrefs
    private val settings = MutableStateFlow(ReaderSettings())
    private val position = MutableStateFlow(Position())
    private val loading = MutableStateFlow(true)
    private val loadingStep = MutableStateFlow("Starting reader")
    private val error = MutableStateFlow<String?>(null)
    private val controls = MutableStateFlow(false)
    private val consoleErrors = java.util.Collections.synchronizedList(mutableListOf<String>())

    private var webView: WebView? = null
    private lateinit var story: Story
    /** Chapters in the book, in order (downloaded ones only). */
    private var sections: List<Chapter> = emptyList()
    private var startIndex = 0
    private var startFraction = 0f
    private var lastSavedAt = 0L
    /** Space taken by the camera cutout / hidden status bar, and the bottom cutout, in dp. */
    @Volatile private var safeTopDp = 0
    @Volatile private var safeBottomDp = 0
    /** Battery level (0-100, -1 unknown) and whether it's charging, for the footer. */
    private val battery = mutableStateOf(-1 to false)
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) = updateBattery(intent)
    }
    private fun updateBattery(intent: Intent?) {
        intent ?: return
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
        battery.value = (if (level >= 0) level * 100 / scale else -1) to charging
    }
    private var lastSavedIndex = -1
    private var lastSavedHref: String? = null
    /** Imported ebook: its single chapter row, and its table of contents from the reader. */
    private var bookChapter: Chapter? = null
    private val toc = MutableStateFlow<List<TocEntry>>(emptyList())
    private val isBook get() = ::story.isInitialized && story.isLocalBook

    private fun js(code: String) = runOnUiThread { webView?.evaluateJavascript(code, null) }

    inner class Bridge {
        @JavascriptInterface fun settings(): String = effectiveSettings().toJson()

        @JavascriptInterface fun story(): String {
            if (isBook) {
                val f = app.storage.bookFile(story)!!
                val stored = story.readerPosition
                val storedFraction = stored.substringBefore('|', "").toFloatOrNull()
                val cfi = stored.substringAfter('|', "")
                val synced = bookChapter?.progress ?: 0f
                // Progress synced from another device is newer than this device's saved spot: use it.
                val useCfi = cfi.isNotEmpty() && storedFraction != null && kotlin.math.abs(storedFraction - synced) < 0.01f
                return JSONObject()
                    .put("mode", "file")
                    .put("id", story.id).put("title", story.title)
                    .put("name", f.name)
                    .put("type", com.epubforge.data.BookFormats.mime(f.name))
                    .put("src", "https://$ASSET_HOST/book/${story.id}/${Uri.encode(f.name)}")
                    .put("wantMeta", !app.storage.coverFile(story.id).exists() || story.author.isBlank())
                    .put("start", JSONObject().put("cfi", if (useCfi) cfi else "").put("fraction", synced.toDouble()))
                    .toString()
            }
            val arr = JSONArray()
            sections.forEach { c ->
                val f = app.storage.chapterFile(story.id, c.url)
                arr.put(
                    JSONObject().put("url", c.url).put("title", c.title).put("position", c.position)
                        .put("size", f.length()).put("src", "https://$ASSET_HOST/story/${story.id}/ch/${f.name}")
                )
            }
            return JSONObject()
                .put("id", story.id).put("title", story.title).put("author", story.author).put("language", story.language.ifBlank { "en" })
                .put("chapters", arr)
                .put("start", JSONObject().put("index", startIndex).put("fraction", startFraction.toDouble()))
                .toString()
        }

        @JavascriptInterface fun ready() { opened = true; loading.value = false }

        @JavascriptInterface fun toc(raw: String) {
            val a = try { JSONArray(raw) } catch (e: Exception) { return }
            toc.value = (0 until a.length()).map { a.getJSONObject(it) }
                .map { TocEntry(it.optString("label"), it.optString("href"), it.optInt("depth")) }
                .filter { it.label.isNotBlank() && it.href.isNotBlank() }
        }

        @JavascriptInterface fun bookInfo(raw: String, coverDataUrl: String) {
            val o = try { JSONObject(raw) } catch (e: Exception) { return }
            val cover = coverDataUrl.substringAfter("base64,", "").takeIf { it.isNotEmpty() }?.let {
                try { android.util.Base64.decode(it, android.util.Base64.DEFAULT) } catch (_: Exception) { null }
            }
            app.scope.launch { app.books.applyReaderMeta(story, o.optString("title"), o.optString("author"), cover) }
        }
        @JavascriptInterface fun progress(step: String) { loadingStep.value = step }
        @JavascriptInterface fun error(message: String) {
            Log.e("Reader", message)
            // Once the chapter is on screen, a stray script error shouldn't cover it.
            if (opened && loading.value == false) return
            if (error.value == null) error.value = message
            loading.value = false
        }
        @JavascriptInterface fun openLink(href: String) { runOnUiThread { BookActions.openUrl(this@ReaderActivity, href) } }

        @JavascriptInterface fun tap(zone: String) {
            runOnUiThread { stayAwake() }
            runOnUiThread {
                when {
                    controls.value -> controls.value = false
                    zone == "left" -> js("reader.prev()")
                    zone == "right" -> js("reader.next()")
                    else -> controls.value = true
                }
            }
        }

        @JavascriptInterface fun relocate(raw: String) {
            runOnUiThread { stayAwake() }
            val o = try { JSONObject(raw) } catch (e: Exception) { return }
            val p = Position(
                index = o.optInt("index", -1), fraction = o.optDouble("fraction", 0.0).toFloat(),
                page = o.optInt("page"), pages = o.optInt("pages"), atEnd = o.optBoolean("atEnd"),
                bookFraction = o.optDouble("bookFraction", 0.0).toFloat(),
                cfi = o.optString("cfi"), tocLabel = o.optString("tocLabel"), tocHref = o.optString("tocHref"),
            )
            loading.value = false
            position.value = p
            save(p)
        }
    }

    private fun save(p: Position, force: Boolean = false) {
        if (isBook) {
            val chapter = bookChapter ?: return
            val now = System.currentTimeMillis()
            // Every write wakes the database and its observers; the position lives in memory and is
            // saved on leaving the reader, on a new section, at the end, and otherwise every 30 s.
            val sectionChanged = p.tocHref != lastSavedHref
            if (!force && !sectionChanged && p.bookFraction < 0.995f && now - lastSavedAt < SAVE_EVERY_MS) return
            lastSavedHref = p.tocHref
            lastSavedAt = now
            val finished = p.bookFraction >= 0.995f
            app.scope.launch {
                app.repo.recordReading(story.id, chapter, p.bookFraction, finished, label = p.tocLabel)
                if (p.cfi.isNotEmpty()) app.db.stories().setReaderPosition(story.id, "${p.bookFraction}|${p.cfi}")
            }
            return
        }
        val chapter = sections.getOrNull(p.index) ?: return
        val now = System.currentTimeMillis()
        // Save on every chapter change and when a chapter ends; otherwise at most every 30 s.
        if (!force && !p.atEnd && p.index == lastSavedIndex && now - lastSavedAt < SAVE_EVERY_MS) return
        lastSavedIndex = p.index
        lastSavedAt = now
        lifecycleScope.launch {
            app.repo.recordReading(story.id, chapter, if (p.atEnd) 1f else p.fraction, finished = p.atEnd)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = ReaderPrefs(this)
        settings.value = prefs.load()
        val storyId = intent.getStringExtra(EXTRA_STORY) ?: return finish()
        val chapterUrl = intent.getStringExtra(EXTRA_CHAPTER)
        lifecycleScope.launch {
            val s = app.db.stories().get(storyId) ?: return@launch finish()
            story = s
            if (s.isLocalBook) {
                if (app.storage.bookFile(s)?.isFile != true) {
                    android.widget.Toast.makeText(this@ReaderActivity, "This book's file isn't on this device", android.widget.Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                bookChapter = app.db.chapters().get(storyId, com.epubforge.data.LocalBooks.BOOK_CHAPTER)
                    ?: com.epubforge.data.Chapter(storyId = storyId, url = com.epubforge.data.LocalBooks.BOOK_CHAPTER, title = s.title, position = 0, fetchedAt = s.addedAt).also {
                        app.db.chapters().upsertAll(listOf(it.copy(downloadedAt = System.currentTimeMillis())))
                    }
                show(emptyList())
                return@launch
            }
            val all = app.db.chapters().list(storyId).filter { !it.removed || it.downloaded }
            sections = all.filter { it.downloaded && app.storage.chapterFile(storyId, it.url).exists() }
            if (sections.isEmpty()) {
                android.widget.Toast.makeText(this@ReaderActivity, "No downloaded chapters yet", android.widget.Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            val target = chapterUrl?.let { u -> sections.indexOfFirst { it.url == u } }?.takeIf { it >= 0 }
                ?: app.db.history().get(storyId)?.chapterUrl?.let { u -> sections.indexOfFirst { it.url == u } }?.takeIf { it >= 0 }
                ?: sections.indexOfFirst { !it.read }.takeIf { it >= 0 }
                ?: 0
            startIndex = target
            val c = sections[target]
            startFraction = if (!c.read && c.progress in 0.01f..0.98f) c.progress else 0f
            show(all)
        }
    }

    private fun applyWindow(s: ReaderSettings, showBars: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showBars) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        val theme = ReaderThemes.get(s.theme)
        c.isAppearanceLightStatusBars = !theme.dark
        c.isAppearanceLightNavigationBars = !theme.dark
        if (s.keepScreenOn) stayAwake() else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // "Keep screen on" holds the screen only while you're reading: each page turn or tap renews it,
    // and after five idle minutes the phone's normal screen timeout applies again.
    private val sleepAllowed = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private fun stayAwake() {
        if (!settings.value.keepScreenOn) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.removeCallbacks(sleepAllowed)
        window.decorView.postDelayed(sleepAllowed, AWAKE_AFTER_LAST_TURN_MS)
    }

    /** Battery saver on: no page-turn animation (each animated turn renders dozens of frames). */
    private fun effectiveSettings(): ReaderSettings {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val s = settings.value
        // Keep the text clear of the camera cutout and of the header/footer that sit beside it.
        val withInsets = s.copy(
            marginTop = s.marginTop + safeTopDp,
            marginBottom = s.marginBottom + safeBottomDp,
        )
        return if (pm.isPowerSaveMode) withInsets.copy(animated = false) else withInsets
    }

    private fun serveStoryFile(path: String): WebResourceResponse {
        // /story/<id>/ch/<file> or /story/<id>/img/<file>
        val parts = path.removePrefix("/story/").split('/')
        val notFound = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        if (parts.size != 3 || parts[0] != story.id || !SAFE_NAME.matches(parts[2])) return notFound
        val dir = when (parts[1]) {
            "ch" -> File(app.storage.storyDir(story.id), "ch")
            "img" -> app.storage.imageDir(story.id)
            else -> return notFound
        }
        val f = File(dir, parts[2])
        if (!f.exists()) return notFound
        val mime = if (parts[1] == "ch") "text/html" else AssetServer.mime(f.name).takeIf { it != "application/octet-stream" } ?: "image/jpeg"
        return WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), FileInputStream(f))
    }

    private fun serveBookFile(path: String): WebResourceResponse {
        val notFound = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        if (!isBook || path.removePrefix("/book/").substringBefore('/') != story.id) return notFound
        val f = app.storage.bookFile(story)?.takeIf { it.isFile } ?: return notFound
        return WebResourceResponse(
            com.epubforge.data.BookFormats.mime(f.name), null, 200, "OK",
            mapOf("Cache-Control" to "no-store", "Content-Length" to f.length().toString()), FileInputStream(f),
        )
    }

    @Volatile private var opened = false

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.textZoom = 100
        setBackgroundColor(android.graphics.Color.parseColor(ReaderThemes.get(this@ReaderActivity.settings.value.theme).bg))
        addJavascriptInterface(Bridge(), "ReaderBridge")
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d("Reader", "${m.messageLevel()}: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    consoleErrors += "${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})"
                }
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            // Android reclaimed the renderer (low memory, reader in the background): reopen where we were.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                Log.w("Reader", "Renderer gone (crashed=${detail.didCrash()}); reopening")
                if (webView === view) webView = null
                view.tag = "destroyed"
                view.destroy()
                if (!isFinishing) recreate()
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != ASSET_HOST) {
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                val path = url.path ?: return null
                return when {
                    path.startsWith("/assets/") -> AssetServer.respond(context.assets, path.removePrefix("/assets/"))
                    path.startsWith("/story/") -> serveStoryFile(path)
                    path.startsWith("/book/") -> serveBookFile(path)
                    // pdf.js, which foliate loads from /vendor/pdfjs/ (minified names map to the bundled files).
                    path == "/vendor/pdfjs/pdf.worker.min.mjs" -> AssetServer.respond(context.assets, "reader/pdf-worker.mjs")
                    path.startsWith("/vendor/pdfjs/") -> AssetServer.respond(
                        context.assets, "foliate" + path.replace(".min.mjs", ".mjs"),
                    )
                    else -> null
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host != ASSET_HOST) BookActions.openUrl(context, request.url.toString())
                return true
            }
        }
        addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if ((r - l != or - ol || b - t != ob - ot) && r > l && b > t) {
                (v as WebView).evaluateJavascript("window.reader && window.reader.relayout()", null)
            }
        }
        // Let Android reclaim the page's renderer while the reader is in the background; if it does,
        // onRenderProcessGone reopens the book where you were.
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        loadUrl("https://$ASSET_HOST/assets/reader/reader.html")
        webView = this
    }

    private fun reload(atIndex: Int) {
        if (isBook) {
            opened = false
            loading.value = true
            error.value = null
            webView?.loadUrl("https://$ASSET_HOST/assets/reader/reader.html")
            return
        }
        val chapters = sections
        lifecycleScope.launch {
            val fresh = app.db.chapters().list(story.id).filter { it.downloaded && app.storage.chapterFile(story.id, it.url).exists() }
            val target = chapters.getOrNull(atIndex)?.url
            sections = fresh
            startIndex = target?.let { u -> fresh.indexOfFirst { it.url == u } }?.takeIf { it >= 0 } ?: atIndex.coerceAtMost(fresh.lastIndex)
            startFraction = 0f
            opened = false
            loading.value = true
            error.value = null
            position.value = Position()
            webView?.loadUrl("https://$ASSET_HOST/assets/reader/reader.html")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(initialAll: List<Chapter>) {
        setContent {
            val appSettings by app.settings.flow.collectAsState()
            val s by settings.collectAsState()
            val pos by position.collectAsState()
            val isLoading by loading.collectAsState()
            val err by error.collectAsState()
            val step by loadingStep.collectAsState()
            val showControls by controls.collectAsState()
            val allChapters by remember { app.db.chapters().observe(story.id) }.collectAsState(initial = initialAll)
            val queue by app.downloads.states.collectAsState()
            var sheet by remember { mutableStateOf<String?>(null) }
            val theme = ReaderThemes.get(s.theme)
            val fg = Color(android.graphics.Color.parseColor(theme.fg))
            val bg = Color(android.graphics.Color.parseColor(theme.bg))
            val current = sections.getOrNull(pos.index)

            LaunchedEffect(showControls, s) { applyWindow(s, showControls) }
            BackHandler(enabled = showControls) { controls.value = false }

            EpubForgeTheme(if (theme.dark) 2 else 1, appSettings.dynamicColor, appSettings.pureBlack) {
                Box(Modifier.fillMaxSize().background(bg)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { createWebView(it) },
                        onRelease = { if (it.tag != "destroyed") it.destroy(); webView = null },
                    )

                    // The status bar is hidden while reading, but its area (and any camera cutout) still
                    // isn't safe to draw text in: measure it even when hidden.
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val topInsets = WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top)
                    val bottomInsets = WindowInsets.displayCutout.only(WindowInsetsSides.Bottom)
                    val topDp = with(density) { topInsets.getTop(this).toDp().value.roundToInt() }
                    val bottomDp = with(density) { bottomInsets.getBottom(this).toDp().value.roundToInt() }
                    LaunchedEffect(topDp, bottomDp) {
                        if (topDp != safeTopDp || bottomDp != safeBottomDp) {
                            safeTopDp = topDp
                            safeBottomDp = bottomDp
                            js("window.reader && reader.apply()")
                        }
                    }

                    // Header and footer, like Readest's.
                    if (!isLoading && err == null && !showControls) {
                        val headerText = if (isBook) pos.tocLabel.ifBlank { story.title } else current?.title
                        if (s.showHeader && headerText != null) Text(
                            headerText, color = fg.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(topInsets).padding(horizontal = 24.dp, vertical = 10.dp),
                        )
                        if (s.showFooter) Row(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().windowInsetsPadding(bottomInsets)
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val style = fg.copy(alpha = 0.55f)
                            Text(if (pos.pages > 0) "${pos.page + 1} / ${pos.pages}" else "", color = style, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()), color = style, fontSize = 11.sp)
                            val (level, charging) = battery.value
                            if (level >= 0) {
                                Spacer(Modifier.width(10.dp))
                                Icon(
                                    if (charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
                                    contentDescription = "Battery", tint = style,
                                    modifier = Modifier.size(12.dp).graphicsLayer { rotationZ = 90f },
                                )
                                Text("$level%", color = style, fontSize = 11.sp)
                            }
                            Text(
                                if (isBook) "${(pos.bookFraction * 100).roundToInt()}%"
                                else current?.let { "${it.position + 1} / ${allChapters.count { c -> !c.removed }}" } ?: "",
                                color = style, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        }
                    }

                    // End of the downloaded chapters: offer the next ones.
                    val last = sections.lastOrNull()
                    if (!isBook && !isLoading && pos.atEnd && pos.index == sections.lastIndex && last != null) {
                        val after = allChapters.filter { it.position > last.position && !it.removed }
                        if (after.isNotEmpty()) EndOfDownloads(
                            next = after,
                            downloadedNext = after.firstOrNull()?.downloaded == true,
                            downloading = after.take(5).any { queue.containsKey(app.downloads.key(story.id, it.url)) },
                            fg = fg,
                            onDownload = { n ->
                                lifecycleScope.launch { app.downloads.enqueue(story.id, after.take(n).filter { !it.downloaded }.map { it.url }, front = true) }
                            },
                            onContinue = { reload(sections.lastIndex + 1) },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    if (isLoading && err == null) {
                        var slow by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { kotlinx.coroutines.delay(20_000); if (loading.value) slow = true }
                        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(step, color = fg.copy(alpha = 0.7f), fontSize = 13.sp)
                            if (slow) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "This is taking unusually long." + consoleErrors.lastOrNull()?.let { "\n$it" }.orEmpty(),
                                    color = fg, fontSize = 13.sp,
                                )
                            }
                        }
                    }
                    err?.let { message ->
                        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("The reader couldn't open this story.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                (listOf(message) + consoleErrors.takeLast(3)).joinToString("\n").take(900),
                                color = fg, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            )
                            TextButton(onClick = { reload(pos.index.coerceAtLeast(0)) }) { Text("Try again") }
                        }
                    }

                    AnimatedVisibility(showControls, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
                        Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                                Column(Modifier.weight(1f)) {
                                    Text(story.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val sub = if (isBook) pos.tocLabel.ifBlank { null } else current?.title
                                    if (sub != null) Text(
                                        sub, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        showControls, modifier = Modifier.align(Alignment.BottomCenter),
                        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                var dragging by remember { mutableStateOf(false) }
                                var sliderValue by remember { mutableFloatStateOf(0f) }
                                if (!dragging) sliderValue = if (isBook) pos.bookFraction else pos.fraction
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { js("reader.prevChapter()") }, enabled = isBook || pos.index > 0) {
                                        Icon(Icons.Filled.SkipPrevious, "Previous chapter")
                                    }
                                    Slider(
                                        value = sliderValue,
                                        onValueChange = { dragging = true; sliderValue = it },
                                        onValueChangeFinished = {
                                            dragging = false
                                            js(if (isBook) "reader.goToFraction($sliderValue)" else "reader.goToChapter(${pos.index.coerceAtLeast(0)}, $sliderValue)")
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { js("reader.nextChapter()") }, enabled = isBook || pos.index < sections.lastIndex) {
                                        Icon(Icons.Filled.SkipNext, "Next chapter")
                                    }
                                }
                                Text(
                                    when {
                                        isBook -> "${(pos.bookFraction * 100).roundToInt()}% of the book" + if (pos.pages > 1) " • page ${pos.page + 1} of ${pos.pages} in this section" else ""
                                        pos.pages > 0 -> "Page ${pos.page + 1} of ${pos.pages}"
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    BarButton(Icons.AutoMirrored.Filled.List, "Chapters") { sheet = "chapters" }
                                    BarButton(Icons.Filled.FormatSize, "Display") { sheet = "display" }
                                }
                            }
                        }
                    }
                }

                if (sheet == "chapters" && isBook) {
                    ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        val entries by toc.collectAsState()
                        val currentIdx = entries.indexOfLast { it.href == pos.tocHref }.coerceAtLeast(0)
                        val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIdx - 4).coerceAtLeast(0))
                        Text("Contents", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
                        if (entries.isEmpty()) Text(
                            "This book has no table of contents.",
                            modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().height(540.dp)) {
                            itemsIndexed(entries) { i, e ->
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            e.label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (i == currentIdx) FontWeight.Bold else FontWeight.Normal,
                                            color = if (i == currentIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        )
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier.padding(start = (16 * e.depth).dp).clickable {
                                        sheet = null
                                        controls.value = false
                                        js("reader.goToHref(" + JSONObject.quote(e.href) + ")")
                                    },
                                )
                            }
                        }
                    }
                }
                if (sheet == "chapters" && !isBook) {
                    ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        val list = allChapters.filter { !it.removed || it.downloaded }
                        val currentIdx = list.indexOfFirst { it.url == current?.url }.coerceAtLeast(0)
                        val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIdx - 4).coerceAtLeast(0))
                        Text("Chapters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
                        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().height(540.dp)) {
                            itemsIndexed(list, key = { _, c -> c.url }) { i, c ->
                                val isCurrent = i == currentIdx
                                val queued = queue.containsKey(app.downloads.key(story.id, c.url))
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            c.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            color = when {
                                                isCurrent -> MaterialTheme.colorScheme.primary
                                                c.read -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    },
                                    trailingContent = {
                                        when {
                                            c.downloaded -> Icon(Icons.Filled.CheckCircle, "Downloaded", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                            queued -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                            else -> Icon(Icons.Filled.Download, "Download", modifier = Modifier.size(20.dp))
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier.clickable {
                                        val idx = sections.indexOfFirst { it.url == c.url }
                                        if (idx >= 0) {
                                            sheet = null
                                            controls.value = false
                                            js("reader.goToChapter($idx, 0)")
                                        } else if (c.downloaded) {
                                            sheet = null
                                            reload(sections.indexOfFirst { it.position > c.position }.let { if (it < 0) sections.size else it })
                                        } else {
                                            lifecycleScope.launch { app.downloads.enqueue(story.id, listOf(c.url), front = true) }
                                            android.widget.Toast.makeText(this@ReaderActivity, "Downloading “${c.title}”", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                if (sheet == "display") {
                    ModalBottomSheet(onDismissRequest = { sheet = null }) {
                        DisplaySettings(s) { updated ->
                            settings.value = updated
                            prefs.save(updated)
                            js("reader.apply()")
                            webView?.setBackgroundColor(android.graphics.Color.parseColor(ReaderThemes.get(updated.theme).bg))
                        }
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (settings.value.volumeKeys && !controls.value &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                js(if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) "reader.next()" else "reader.prev()")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        // The battery broadcast is sticky: registering returns the current level right away.
        updateBattery(androidx.core.content.ContextCompat.registerReceiver(
            this, batteryReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        ))
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        stayAwake()
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) { }
        // Stops this page's timers and animations while hidden (downloads use their own WebView).
        webView?.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) webView?.clearCache(false)
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(sleepAllowed)
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        if (!opened) return
        if (isBook) {
            save(position.value, force = true)
        } else if (::story.isInitialized) {
            val p = position.value
            sections.getOrNull(p.index)?.let { c ->
                app.scope.launch { app.repo.recordReading(story.id, c, if (p.atEnd) 1f else p.fraction, finished = p.atEnd) }
            }
        }
        app.sync.requestSync()
    }
}

@Composable
private fun EndOfDownloads(
    next: List<Chapter>,
    downloadedNext: Boolean,
    downloading: Boolean,
    fg: Color,
    onDownload: (Int) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier,
) {
    Card(modifier.padding(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(20.dp)) {
            Text("Next: ${next.first().title}", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    downloadedNext -> "The next chapter is downloaded."
                    downloading -> "Downloading…"
                    else -> "That's the last downloaded chapter. ${next.size} more on the site."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (downloadedNext) {
                Button(onClick = onContinue) { Text("Continue reading") }
            } else if (!downloading) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDownload(5) }) { Text(if (next.size > 1) "Download next ${minOf(5, next.size)}" else "Download") }
                    if (next.size > 5) OutlinedButton(onClick = { onDownload(next.size) }) { Text("All ${next.size}") }
                }
            }
        }
    }
}

@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplaySettings(s: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text("Theme", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReaderThemes.all.forEachIndexed { i, t ->
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { onChange(s.copy(theme = i)) }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(48.dp).background(Color(android.graphics.Color.parseColor(t.bg)), CircleShape)
                            .border(
                                if (s.theme == i) 3.dp else 1.dp,
                                if (s.theme == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) { Text("Aa", color = Color(android.graphics.Color.parseColor(t.fg)), fontWeight = FontWeight.SemiBold) }
                    Text(t.name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Font size", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { onChange(s.copy(fontSize = (s.fontSize - 1).coerceAtLeast(10))) }) { Icon(Icons.Filled.Remove, "Smaller") }
            Text("${s.fontSize}", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onChange(s.copy(fontSize = (s.fontSize + 1).coerceAtMost(40))) }) { Icon(Icons.Filled.Add, "Larger") }
        }
        Text("Font", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderFonts.all.forEachIndexed { i, f ->
                FilterChip(selected = s.font == i, onClick = { onChange(s.copy(font = i)) }, label = { Text(f.name, fontFamily = f.preview) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(300 to "Light", 400 to "Regular", 500 to "Medium").forEach { (w, label) ->
                FilterChip(selected = s.fontWeight == w, onClick = { onChange(s.copy(fontWeight = w)) }, label = { Text(label) })
            }
        }
        LabeledSlider("Line spacing", "%.2f".format(s.lineHeight), s.lineHeight, 1.0f..2.4f) { onChange(s.copy(lineHeight = it)) }
        LabeledSlider("Paragraph spacing", "%.1f".format(s.paragraphSpacing), s.paragraphSpacing, 0f..2f) { onChange(s.copy(paragraphSpacing = it)) }
        LabeledSlider("Paragraph indent", "%.1f".format(s.indent), s.indent, 0f..3f) { onChange(s.copy(indent = it)) }
        LabeledSlider("Letter spacing", "%.2f".format(s.letterSpacing), s.letterSpacing, -0.05f..0.2f) { onChange(s.copy(letterSpacing = it)) }
        LabeledSlider("Side margins", "${s.gap}%", s.gap.toFloat(), 0f..20f) { onChange(s.copy(gap = it.toInt())) }
        LabeledSlider("Top margin", "${s.marginTop}", s.marginTop.toFloat(), 0f..120f) { onChange(s.copy(marginTop = it.toInt())) }
        LabeledSlider("Bottom margin", "${s.marginBottom}", s.marginBottom.toFloat(), 0f..120f) { onChange(s.copy(marginBottom = it.toInt())) }
        Spacer(Modifier.height(8.dp))
        Text("Layout", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("paginated" to "Pages", "scrolled" to "Scroll").forEachIndexed { i, (v, label) ->
                SegmentedButton(selected = s.flow == v, onClick = { onChange(s.copy(flow = v)) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
        ToggleRow("Justify text", s.justify) { onChange(s.copy(justify = it)) }
        ToggleRow("Hyphenation", s.hyphenate) { onChange(s.copy(hyphenate = it)) }
        ToggleRow("Page turn animation", s.animated) { onChange(s.copy(animated = it)) }
        ToggleRow("Show chapter title at top", s.showHeader) { onChange(s.copy(showHeader = it)) }
        ToggleRow("Show page and time at bottom", s.showFooter) { onChange(s.copy(showFooter = it)) }
        ToggleRow("Volume keys turn pages", s.volumeKeys) { onChange(s.copy(volumeKeys = it)) }
        ToggleRow("Keep screen on", s.keepScreenOn) { onChange(s.copy(keepScreenOn = it)) }
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    var local by remember(v) { mutableFloatStateOf(v) }
    Column(Modifier.padding(top = 8.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
