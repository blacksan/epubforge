package com.epubforge.net

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.epubforge.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/** Shares cookies between the in-app browser (WebView) and OkHttp, as Mihon does. */
class WebViewCookieJar : CookieJar {
    private val manager: CookieManager = CookieManager.getInstance().apply { setAcceptCookie(true) }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val u = url.toString()
        cookies.forEach { manager.setCookie(u, it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = manager.getCookie(url.toString()) ?: return emptyList()
        return header.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }

    fun flush() = manager.flush()
}

/**
 * Per-host request pacing. Each host gets one request at a time, a minimum gap between
 * requests (with jitter), and an adaptive extra delay after the server signals overload
 * (429/503), which decays again as requests succeed.
 */
class RequestGate(private val settings: SettingsStore) {
    private class HostState {
        val lock = ReentrantLock(true)
        var lastRequest = 0L
        var penaltyMs = 0L
    }

    private val hosts = ConcurrentHashMap<String, HostState>()

    private fun key(host: String) = host.removePrefix("www.").split('.').takeLast(2).joinToString(".")

    fun baseDelay(host: String): Long {
        val s = settings.current
        return if (key(host) == "royalroad.com") s.royalRoadDelayMs.toLong() else s.defaultDelayMs.toLong()
    }

    fun <T> run(host: String, delayOverrideMs: Long? = null, block: () -> T): T {
        val st = hosts.getOrPut(key(host)) { HostState() }
        st.lock.withLock {
            val base = (delayOverrideMs ?: baseDelay(host)) + st.penaltyMs
            val jitter = if (base > 0) Random.nextLong(0, base / 4 + 1) else 0
            val wait = st.lastRequest + base + jitter - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            try {
                return block()
            } finally {
                st.lastRequest = System.currentTimeMillis()
            }
        }
    }

    fun penalize(host: String) {
        val st = hosts.getOrPut(key(host)) { HostState() }
        st.penaltyMs = (st.penaltyMs * 2).coerceIn(2_000, 20_000)
    }

    fun reward(host: String) {
        val st = hosts[key(host)] ?: return
        st.penaltyMs = (st.penaltyMs * 3 / 4).let { if (it < 500) 0 else it }
    }
}

/** A site asked the user to prove they're human; the app shows it in a real browser view. */
data class Challenge(val id: Long, val url: String, val host: String)

class ChallengeCoordinator {
    private val _pending = MutableStateFlow<Challenge?>(null)
    val pending: StateFlow<Challenge?> = _pending.asStateFlow()
    private var waiter: CompletableDeferred<Boolean>? = null
    private val lock = Any()
    private var counter = 0L
    var onChallenge: ((Challenge) -> Unit)? = null

    /** Blocks the calling (network) thread until the user finishes or dismisses the page. */
    fun awaitSolved(url: String, host: String, timeoutMs: Long = 10 * 60_000): Boolean {
        val deferred: CompletableDeferred<Boolean>
        synchronized(lock) {
            val existing = waiter
            if (existing != null && !existing.isCompleted) {
                deferred = existing
            } else {
                deferred = CompletableDeferred()
                waiter = deferred
                val c = Challenge(++counter, url, host)
                _pending.value = c
                onChallenge?.invoke(c)
            }
        }
        return kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        }.also { if (!it) resolve(false) }
    }

    fun resolve(solved: Boolean) {
        synchronized(lock) {
            waiter?.complete(solved)
            waiter = null
            _pending.value = null
        }
    }
}

object Http {
    fun userAgent(context: Context): String = WebSettings.getDefaultUserAgent(context)

    fun client(cookieJar: CookieJar): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val challengeMarkers = listOf(
        "cf-browser-verification", "challenge-platform", "cf_chl_opt", "<title>just a moment",
        "making sure you're not a bot", "attention required! | cloudflare", "ddos-guard",
        "verifying you are human", "checking your browser before accessing",
    )

    private val titleMarkers = listOf(
        "just a moment", "making sure you're not a bot", "attention required", "ddos-guard",
        "verifying you are human", "security check",
    )

    /** True when a response is an anti-bot verification page rather than real content. */
    fun isChallenge(code: Int, headers: okhttp3.Headers, bodyStart: String?): Boolean {
        if (headers["cf-mitigated"].equals("challenge", ignoreCase = true)) return true
        val lower = bodyStart?.lowercase() ?: return false
        // Cloudflare injects challenge-platform scripts into normal pages too, so a 200
        // only counts when the page title itself is a verification page.
        val title = Regex("<title[^>]*>([^<]*)</title>").find(lower)?.groupValues?.get(1)?.trim() ?: ""
        if (titleMarkers.any { title.contains(it) }) return true
        if (code == 403 || code == 503 || code == 429) {
            if (challengeMarkers.any { lower.contains(it) }) return true
        }
        return false
    }

    /** Parses Retry-After (seconds or HTTP-date) into milliseconds. */
    fun retryAfterMs(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.trim().toLongOrNull()?.let { return it * 1000 }
        return try {
            val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(value)
            date?.let { (it.time - System.currentTimeMillis()).coerceAtLeast(1000) }
        } catch (e: Exception) {
            null
        }
    }
}
