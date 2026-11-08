package com.epubforge.engine

/** Turns WebToEpub's raw errors (often a JS stack trace) into one readable sentence. */
object ErrorText {
    private val httpCode = Regex("""(?:network error|status|HTTP)\D{0,3}(\d{3})""", RegexOption.IGNORE_CASE)

    fun friendly(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "Something went wrong."
        val host = Regex("""https?://([^/'"\s]+)""").find(text)?.groupValues?.get(1)?.removePrefix("www.")
        val site = host ?: "The site"
        httpCode.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { code ->
            return when (code) {
                401, 403, 418, 503 -> "$site refused the request (HTTP $code). It may be blocking this network or asking for verification. Try again later, or on another connection."
                429 -> "$site is rate-limiting requests (HTTP 429). Wait a few minutes, then retry."
                404, 410 -> "$site says this page doesn't exist (HTTP $code). Check the link."
                in 500..599 -> "$site had a server error (HTTP $code). Try again later."
                else -> "$site returned HTTP $code."
            }
        }
        val lower = text.lowercase()
        return when {
            "no parser" in lower || "not supported" in lower || "unsupported" in lower ->
                "This site isn't supported by WebToEpub."
            "unable to resolve host" in lower || "failed to connect" in lower || "timeout" in lower || "timed out" in lower ->
                "Couldn't reach ${host ?: "the site"}. Check your connection."
            else -> text.lineSequence().first()
                .replace(Regex("""^(Error:\s*)+"""), "")
                .substringBefore("    at ")
                .trim()
                .take(240)
                .ifEmpty { "Something went wrong." }
        }
    }
}
