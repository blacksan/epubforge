package com.epubforge.engine

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.epubforge.net.NativeProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

const val ASSET_HOST = "appassets.androidplatform.net"
const val POPUP_URL = "https://$ASSET_HOST/assets/w2e/popup.html?id=1"

data class ChapterRef(val title: String, val url: String)

/** What WebToEpub found at a story link. */
data class StoryInfo(
    val title: String,
    val author: String,
    val language: String,
    val coverUrl: String,
    val description: String,
    val subjects: String,
    val chapters: List<ChapterRef>,
)

/** A downloaded chapter: cleaned XHTML plus its image files. */
data class ChapterContent(val url: String, val title: String, val body: String, val images: List<Pair<String, File>>)

sealed interface EngineEvent {
    data class Ready(val version: String) : EngineEvent
    data class Phase(val phase: String) : EngineEvent
    data class Story(val info: StoryInfo) : EngineEvent
    data class Message(val level: String, val text: String) : EngineEvent
    data class ChapterStart(val url: String) : EngineEvent
    data class ChapterDone(val content: ChapterContent) : EngineEvent
    data class ChapterFailed(val url: String, val message: String) : EngineEvent
    data object Done : EngineEvent
    data class Failed(val message: String) : EngineEvent
    data class OpenUrl(val url: String) : EngineEvent
}

/** JS → Kotlin bridge used by the headless engine and the WebToEpub options screen. */
class W2EBridge(
    private val context: Context,
    private val proxy: NativeProxy,
    private val sink: (EngineEvent) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile var webView: WebView? = null
    /** Supplies the next chapter URL to download, or null to stop. Called on the JS thread. */
    @Volatile var nextChapterProvider: (() -> String?)? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var out: FileOutputStream? = null
    private var outFile: File? = null
    private var outMeta = ""
    /** Images received for the chapter currently being downloaded. */
    private val pendingImages = mutableListOf<Pair<String, File>>()

    @JavascriptInterface
    fun prepare(spec: String): String = proxy.prepare(spec)

    /** Fires a JS timer from native code so it runs even while the WebView is off-screen. */
    @JavascriptInterface
    fun timer(id: Int, ms: Int) {
        mainHandler.postDelayed({
            try { webView?.evaluateJavascript("window.__w2eFireTimer && __w2eFireTimer($id)", null) } catch (_: Exception) { }
        }, ms.toLong().coerceAtLeast(0))
    }

    @JavascriptInterface
    fun nextChapter(): String = try { nextChapterProvider?.invoke() ?: "" } catch (e: Exception) { "" }

    fun console(message: String) = sink(EngineEvent.Message("info", message))

    @JavascriptInterface
    fun event(raw: String) {
        val o = try { json.parseToJsonElement(raw).jsonObject } catch (e: Exception) { return }
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull ?: ""
        val ev = when (s("type")) {
            "ready" -> EngineEvent.Ready(s("version"))
            "phase" -> EngineEvent.Phase(s("phase"))
            "story" -> EngineEvent.Story(
                StoryInfo(
                    title = s("title"), author = s("author"), language = s("language"), coverUrl = s("cover"),
                    description = s("description"), subjects = s("subject"),
                    chapters = (o["chapters"]?.jsonArray ?: emptyList()).mapNotNull {
                        val c = it.jsonObject
                        val url = c["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        ChapterRef(c["title"]?.jsonPrimitive?.contentOrNull ?: "", url)
                    },
                )
            )
            "warn" -> EngineEvent.Message("warn", s("message"))
            "error" -> EngineEvent.Message("error", s("message"))
            "log" -> EngineEvent.Message("info", s("message"))
            "openUrl" -> EngineEvent.OpenUrl(s("url"))
            "chapterStart" -> {
                discardPendingImages()
                EngineEvent.ChapterStart(s("url"))
            }
            "chapterFailed" -> {
                discardPendingImages()
                EngineEvent.ChapterFailed(s("url"), s("message"))
            }
            "done" -> EngineEvent.Done
            "failed" -> EngineEvent.Failed(s("message"))
            else -> null
        }
        ev?.let(sink)
    }

    @JavascriptInterface
    fun fileBegin(meta: String, kind: String, size: Int) {
        out?.close()
        val dir = File(context.cacheDir, "incoming").apply { mkdirs() }
        outFile = File.createTempFile("w2e", ".bin", dir)
        out = FileOutputStream(outFile)
        outMeta = meta
    }

    @JavascriptInterface
    fun fileChunk(b64: String) {
        out?.write(Base64.decode(b64, Base64.DEFAULT))
    }

    @JavascriptInterface
    fun fileEnd() {
        out?.close()
        out = null
        val f = outFile ?: return
        outFile = null
        val name = try {
            json.parseToJsonElement(outMeta).jsonObject["name"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) { null }
        if (name == null) { f.delete(); return }
        synchronized(pendingImages) { pendingImages += name to f }
    }

    /** A chapter finished: its XHTML plus the images sent just before it. */
    @JavascriptInterface
    fun chapter(meta: String, body: String) {
        val o = try { json.parseToJsonElement(meta).jsonObject } catch (e: Exception) { return }
        val images = synchronized(pendingImages) { pendingImages.toList().also { pendingImages.clear() } }
        sink(
            EngineEvent.ChapterDone(
                ChapterContent(
                    url = o["url"]?.jsonPrimitive?.contentOrNull ?: "",
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
                    body = body,
                    images = images,
                )
            )
        )
    }

    private fun discardPendingImages() = synchronized(pendingImages) {
        pendingImages.forEach { it.second.delete() }
        pendingImages.clear()
    }
}

object W2EWebView {
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun configure(webView: WebView, proxy: NativeProxy, bridge: W2EBridge) {
        val assets = webView.context.assets
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // Images must load: WebToEpub reads image sizes via <img> on blob: URLs.
            // Remote loads are still refused in shouldInterceptRequest.
            blockNetworkImage = false
            loadsImagesAutomatically = true
        }
        bridge.webView = webView
        webView.resumeTimers()
        webView.addJavascriptInterface(bridge, "W2EBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host == ASSET_HOST) {
                    val path = url.path ?: ""
                    if (path.startsWith("/__proxy/")) return proxy.handle(path.removePrefix("/__proxy/"))
                    if (path.startsWith("/assets/")) return AssetServer.respond(assets, path.removePrefix("/assets/"))
                    return null
                }
                // The extension page never loads remote resources directly.
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != ASSET_HOST
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d("W2E", "${m.messageLevel()}: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR && !m.message().startsWith("Failed to load resource")) {
                    bridge.console("console: ${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
                }
                return true
            }
        }
    }
}

/** Serves bundled assets with correct MIME types (module scripts are refused otherwise). */
object AssetServer {
    fun mime(path: String) = when (path.substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> "text/javascript"
        "html", "htm" -> "text/html"
        "xhtml" -> "application/xhtml+xml"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }

    fun respond(assets: android.content.res.AssetManager, path: String): WebResourceResponse {
        val clean = java.net.URLDecoder.decode(path.substringBefore('?'), "UTF-8")
        return try {
            WebResourceResponse(mime(clean), "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), assets.open(clean))
        } catch (e: java.io.IOException) {
            Log.w("AssetServer", "Missing asset $clean")
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
        }
    }
}

class EngineFailure(val detail: String) : Exception(ErrorText.friendly(detail))

/** Options WebToEpub uses when downloading a story's chapters. */
data class DownloadOptions(
    val includeImages: Boolean = true,
    val includeAuthorNotes: Boolean = true,
    val maxRetries: Int = 4,
)

/** Runs WebToEpub in a headless WebView. One instance runs one job. */
class W2EEngine(private val context: Context, private val proxy: NativeProxy) {
    @Volatile private var web: WebView? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Story details and chapter list (one request to the site). */
    suspend fun analyse(url: String, options: DownloadOptions, onEvent: (EngineEvent) -> Unit = {}): StoryInfo {
        var info: StoryInfo? = null
        run(url, "analyse", options, null, onEvent) { e ->
            if (e is EngineEvent.Story) info = e.info
        }
        return info ?: throw EngineFailure("No chapters were found at this link.")
    }

    /**
     * Downloads chapters of the story at [url] one at a time. [next] is asked for each chapter
     * URL in turn (on the JS thread) and returns null to stop. [onChapter] is called for each
     * finished chapter before the next one starts.
     */
    suspend fun download(
        url: String,
        options: DownloadOptions,
        next: () -> String?,
        onEvent: (EngineEvent) -> Unit,
        onChapter: suspend (EngineEvent) -> Unit,
    ) {
        run(url, "download", options, next, onEvent) { e ->
            if (e is EngineEvent.ChapterDone || e is EngineEvent.ChapterFailed) onChapter(e)
        }
    }

    fun cancel() {
        main.post { web?.evaluateJavascript("try{W2E.cancel()}catch(e){}", null) }
        proxy.cancelled = true
    }

    private suspend fun run(
        url: String,
        mode: String,
        options: DownloadOptions,
        next: (() -> String?)?,
        onEvent: (EngineEvent) -> Unit,
        handle: suspend (EngineEvent) -> Unit,
    ) {
        val events = Channel<EngineEvent>(Channel.UNLIMITED)
        val bridge = W2EBridge(context, proxy) { events.trySend(it) }
        bridge.nextChapterProvider = next
        var webView: WebView? = null
        try {
            val wv = withContext(Dispatchers.Main) {
                WebView(context).also {
                    W2EWebView.configure(it, proxy, bridge)
                    it.loadUrl(POPUP_URL)
                }
            }
            webView = wv
            web = wv
            withTimeout(60_000) {
                while (true) {
                    val e = events.receive()
                    onEvent(e)
                    if (e is EngineEvent.Ready) break
                }
            }
            val js = JSONObject()
                .put("maxRetries", options.maxRetries)
                .put("includeImages", options.includeImages)
                .put("includeAuthorNotes", options.includeAuthorNotes)
            withContext(Dispatchers.Main) {
                wv.evaluateJavascript("W2E.$mode(${JSONObject.quote(url)}, $js);", null)
            }
            while (true) {
                val e = events.receive()
                onEvent(e)
                handle(e)
                when (e) {
                    is EngineEvent.Failed -> throw EngineFailure(e.message)
                    is EngineEvent.Done -> return
                    else -> Unit
                }
            }
        } finally {
            proxy.cancelled = true
            web = null
            withContext(NonCancellable + Dispatchers.Main) {
                val w = webView
                bridge.webView = null
                bridge.nextChapterProvider = null
                w?.evaluateJavascript("try{W2E.cancel()}catch(e){}", null)
                w?.stopLoading()
                w?.destroy()
            }
        }
    }
}
