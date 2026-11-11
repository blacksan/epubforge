package com.epubforge.net

import android.util.Base64
import android.webkit.WebResourceResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Executes WebToEpub's network requests natively. The page registers a request with
 * [prepare] (via the JS bridge) and then fetches /__proxy/<id> from its own origin; the
 * WebView routes that to [handle], which performs the real request with throttling,
 * Retry-After aware backoff and verification pauses.
 */
class NativeProxy(
    private val client: OkHttpClient,
    private val gate: RequestGate,
    private val challenges: ChallengeCoordinator,
    private val userAgent: String,
    private val maxRetries: () -> Int,
) {
    data class Spec(val url: String, val method: String, val headers: Map<String, String>, val body: ByteArray?)

    fun interface StatusListener { fun onStatus(message: String) }
    fun interface LogListener { fun onLog(message: String) }

    private val pending = ConcurrentHashMap<String, Spec>()
    private val ids = AtomicLong()
    @Volatile var cancelled = false
    @Volatile var listener: StatusListener? = null
    @Volatile var log: LogListener? = null
    @Volatile var requestCount = 0
    /** Per-download delay between requests (ms); null = per-site default. */
    @Volatile var delayOverrideMs: Long? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun prepare(specJson: String): String {
        val o = json.parseToJsonElement(specJson).jsonObject
        val headers = (o["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" } ?: emptyMap()
        val bodyObj = o["body"] as? JsonObject
        val body = bodyObj?.let { b ->
            b["text"]?.jsonPrimitive?.contentOrNull?.toByteArray()
                ?: b["base64"]?.jsonPrimitive?.contentOrNull?.let { Base64.decode(it, Base64.DEFAULT) }
        }
        val id = ids.incrementAndGet().toString()
        pending[id] = Spec(
            url = o["url"]!!.jsonPrimitive.content,
            method = o["method"]?.jsonPrimitive?.contentOrNull ?: "GET",
            headers = headers,
            body = body,
        )
        return id
    }

    private class Result(val code: Int, val message: String, val headers: Headers, val body: ByteArray, val finalUrl: String)

    private val PAGE_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    private val IMAGE_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
    private val IMAGE_PATH = Regex("\\.(png|jpe?g|gif|webp|avif|bmp|svg)$", RegexOption.IGNORE_CASE)
    private val dropRequestHeaders = setOf("accept-encoding", "host", "content-length", "cookie", "connection", "user-agent")
    private val keepResponseHeaders = setOf("retry-after", "last-modified", "etag", "content-disposition", "content-language")

    private fun execute(spec: Spec): Result {
        val builder = Request.Builder().url(spec.url)
            .header("User-Agent", userAgent)
            // Pages get a browser's page Accept header; image URLs get an image one (hosts like Imgur
            // send their HTML viewer page instead of the picture to requests that prefer HTML).
            .header("Accept", if (IMAGE_PATH.containsMatchIn(spec.url.substringBefore('?').substringBefore('#'))) IMAGE_ACCEPT else PAGE_ACCEPT)
            .header("Accept-Language", "en-US,en;q=0.9")
        spec.headers.forEach { (k, v) -> if (k.lowercase() !in dropRequestHeaders) builder.header(k, v) }
        val method = spec.method.uppercase()
        val contentType = spec.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value
        val body = when {
            method == "GET" || method == "HEAD" -> null
            else -> (spec.body ?: ByteArray(0)).toRequestBody(contentType?.toMediaTypeOrNull())
        }
        builder.method(method, body)
        client.newCall(builder.build()).execute().use { r ->
            return Result(r.code, r.message, r.headers, r.body?.bytes() ?: ByteArray(0), r.request.url.toString())
        }
    }

    private fun sleepCancellable(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (!cancelled && System.currentTimeMillis() < end) Thread.sleep(250)
    }

    fun handle(id: String): WebResourceResponse {
        val spec = pending.remove(id) ?: return errorResponse("unknown request")
        val host = try { URI(spec.url).host ?: "" } catch (e: Exception) { "" }
        var attempt = 0
        var challengeTries = 0
        while (true) {
            if (cancelled) return errorResponse("cancelled")
            val result = try {
                gate.run(host, delayOverrideMs) {
                    log?.onLog("… ${spec.method} ${spec.url}")
                    execute(spec)
                }
            } catch (e: Exception) {
                log?.onLog("${spec.method} ${spec.url} → ${e.javaClass.simpleName}: ${e.message}")
                if (e is IOException && attempt < maxRetries()) {
                    attempt++
                    val wait = (2000L shl attempt).coerceAtMost(60_000)
                    listener?.onStatus("Network error on $host, retrying in ${wait / 1000}s")
                    sleepCancellable(wait)
                    continue
                }
                return errorResponse("${e.javaClass.simpleName}: ${e.message ?: ""}")
            }
            requestCount++
            log?.onLog("${spec.method} ${spec.url} → HTTP ${result.code} (${result.body.size / 1024} KB)")
            val ctype = result.headers["Content-Type"] ?: ""
            val peek = if (ctype.contains("html") || ctype.isEmpty()) {
                String(result.body, 0, minOf(result.body.size, 65536), Charsets.UTF_8)
            } else null

            if (Http.isChallenge(result.code, result.headers, peek) && challengeTries < 3) {
                challengeTries++
                listener?.onStatus("Verification needed on $host — tap to continue")
                val solved = challenges.awaitSolved(spec.url, host)
                if (solved) {
                    listener?.onStatus("Verified, continuing")
                    continue
                }
                return toResponse(result)
            }

            val overloaded = result.code == 429 || result.code == 503 || result.code == 502 ||
                result.code == 504 || result.code in 520..524
            if (overloaded) {
                gate.penalize(host)
                if (attempt < maxRetries()) {
                    attempt++
                    val wait = Http.retryAfterMs(result.headers["Retry-After"])?.coerceAtMost(10 * 60_000)
                        ?: (5000L shl attempt).coerceAtMost(180_000)
                    listener?.onStatus("$host is rate limiting (HTTP ${result.code}); waiting ${wait / 1000}s")
                    sleepCancellable(wait)
                    continue
                }
            } else if (result.code in 200..299) {
                gate.reward(host)
            }
            return toResponse(result)
        }
    }

    private fun toResponse(r: Result): WebResourceResponse {
        val ctype = r.headers["Content-Type"] ?: "application/octet-stream"
        val mime = ctype.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
        val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(ctype)?.groupValues?.get(1)?.trim('"', ' ')
        val headers = LinkedHashMap<String, String>()
        for (name in r.headers.names()) {
            if (name.lowercase() !in keepResponseHeaders) continue
            headers[name] = r.headers.values(name).joinToString(", ").filter { it.code in 32..126 }
        }
        headers["Content-Type"] = ctype.filter { it.code in 32..126 }
        headers["Cache-Control"] = "no-store"
        headers["X-W2E-Status"] = r.code.toString()
        headers["X-W2E-Status-Text"] = r.message.ifBlank { "OK" }.filter { it.code in 32..126 }
        headers["X-W2E-Url"] = r.finalUrl.filter { it.code in 32..126 }
        return WebResourceResponse(mime, charset, 200, "OK", headers, ByteArrayInputStream(r.body))
    }

    private fun errorResponse(message: String): WebResourceResponse {
        val headers = mapOf(
            "X-W2E-Error" to message.filter { it.code in 32..126 }.ifBlank { "error" },
            "Cache-Control" to "no-store",
        )
        return WebResourceResponse("text/plain", "utf-8", 200, "OK", headers, ByteArrayInputStream(ByteArray(0)))
    }
}
