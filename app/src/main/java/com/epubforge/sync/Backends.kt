package com.epubforge.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** The sync document as stored remotely, plus an opaque version used to detect concurrent writes. */
data class RemoteDoc(val text: String, val version: String?)

/** Another device wrote in between our read and write; read, merge and try again. */
class SyncConflict : Exception("Changed on another device")

/** Credentials were rejected; the user has to sign in again. */
class SyncAuthError(message: String) : Exception(message)

interface SyncBackend {
    val label: String
    suspend fun check()
    suspend fun read(): RemoteDoc?
    /** Writes [text]. [previous] is what [read] returned (null if nothing existed). */
    suspend fun write(text: String, previous: RemoteDoc?)
}

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

// ---------------------------------------------------------------- WebDAV

/**
 * Any WebDAV server: Nextcloud, ownCloud, Koofr, pCloud, Box, a NAS, Apache/nginx.
 * The library lives in `<base>/EpubForge/library.json`; ETags guard against two devices writing at once.
 */
class WebDavBackend(
    private val http: OkHttpClient,
    baseUrl: String,
    private val user: String,
    private val password: String,
) : SyncBackend {
    override val label = "WebDAV"
    private val base = baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
    private val folder = base + "EpubForge/"
    private val file = folder + "library.json"

    private fun req(url: String) = Request.Builder().url(url).apply {
        if (user.isNotEmpty() || password.isNotEmpty()) header("Authorization", Credentials.basic(user, password, Charsets.UTF_8))
    }

    private fun failure(code: Int, what: String): Exception = when (code) {
        401, 403 -> SyncAuthError("The server rejected the username or password (HTTP $code).")
        404 -> IOException("$what: folder not found (HTTP 404). Check the server address.")
        else -> IOException("$what failed (HTTP $code).")
    }

    override suspend fun check() {
        try { base.toHttpUrl() } catch (e: IllegalArgumentException) { throw IOException("That doesn't look like a web address.") }
        val propfind = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
        http.newCall(
            req(base).method("PROPFIND", propfind.toRequestBody("application/xml".toMediaType())).header("Depth", "0").build()
        ).execute().use { r -> if (r.code != 207 && !r.isSuccessful) throw failure(r.code, "Connecting") }
        ensureFolder()
    }

    private fun ensureFolder() {
        http.newCall(req(folder).method("MKCOL", null).build()).execute().use { r ->
            // 201 created, 405 already exists; some servers answer 301/409 for existing collections.
            if (r.code !in listOf(201, 405, 301, 409) && !r.isSuccessful) throw failure(r.code, "Creating the EpubForge folder")
        }
    }

    override suspend fun read(): RemoteDoc? =
        http.newCall(req(file).get().header("Cache-Control", "no-cache").build()).execute().use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> throw failure(r.code, "Reading the library")
                else -> RemoteDoc(r.body?.string().orEmpty(), r.header("ETag"))
            }
        }

    override suspend fun write(text: String, previous: RemoteDoc?) {
        ensureFolder()
        // Many servers (Apache, wsgidav) derive ETags from size + mtime in whole seconds, so two quick
        // same-sized writes can share an ETag. Compare the content as well before overwriting.
        if (previous != null) {
            val current = read() ?: throw SyncConflict()
            if (current.text != previous.text) throw SyncConflict()
        }
        val version = previous?.version
        val b = req(file).put(text.toRequestBody(JSON_TYPE))
        if (previous == null) b.header("If-None-Match", "*") else if (version != null) b.header("If-Match", version)
        http.newCall(b.build()).execute().use { r ->
            if (r.code == 412) throw SyncConflict()
            if (!r.isSuccessful) throw failure(r.code, "Saving the library")
        }
    }
}

// ---------------------------------------------------------------- Supabase

/** A signed-in Supabase session. */
data class SupabaseSession(val accessToken: String, val refreshToken: String, val expiresAt: Long, val userId: String, val email: String)

/**
 * Your own Supabase project (free tier is plenty). Uses Supabase Auth (email + password) and one
 * table protected by row-level security — see docs/supabase.sql. No SDK, just the REST APIs.
 */
class SupabaseClient(private val http: OkHttpClient, projectUrl: String, private val anonKey: String) {
    val url = projectUrl.trim().trimEnd('/')

    private fun req(path: String) = Request.Builder().url(url + path).header("apikey", anonKey)

    private fun call(r: Request): JsonObject = http.newCall(r).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            val msg = try {
                val o = SyncJson.parseToJsonElement(body).jsonObject
                (o["error_description"] ?: o["msg"] ?: o["message"] ?: o["error"])?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) { null }
            if (resp.code == 400 || resp.code == 401 || resp.code == 403 || resp.code == 422) throw SyncAuthError(msg ?: "Sign-in failed (HTTP ${resp.code}).")
            throw IOException("Supabase error ${resp.code}: ${msg ?: body.take(200)}")
        }
        if (body.isBlank()) JsonObject(emptyMap()) else SyncJson.parseToJsonElement(body).jsonObject
    }

    private fun session(o: JsonObject, fallbackEmail: String): SupabaseSession {
        val user = o["user"]?.jsonObject
        return SupabaseSession(
            accessToken = o["access_token"]!!.jsonPrimitive.content,
            refreshToken = o["refresh_token"]!!.jsonPrimitive.content,
            expiresAt = System.currentTimeMillis() + ((o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600) - 60) * 1000,
            userId = user?.get("id")?.jsonPrimitive?.content ?: "",
            email = user?.get("email")?.jsonPrimitive?.contentOrNull ?: fallbackEmail,
        )
    }

    /** Creates an account. Returns a session, or null if the project wants the email confirmed first. */
    fun signUp(email: String, password: String): SupabaseSession? {
        val o = call(req("/auth/v1/signup").post(buildJsonObject { put("email", email); put("password", password) }.toString().toRequestBody(JSON_TYPE)).build())
        return if (o["access_token"] != null) session(o, email) else null
    }

    fun signIn(email: String, password: String): SupabaseSession =
        session(call(req("/auth/v1/token?grant_type=password").post(buildJsonObject { put("email", email); put("password", password) }.toString().toRequestBody(JSON_TYPE)).build()), email)

    fun refresh(s: SupabaseSession): SupabaseSession =
        session(call(req("/auth/v1/token?grant_type=refresh_token").post(buildJsonObject { put("refresh_token", s.refreshToken) }.toString().toRequestBody(JSON_TYPE)).build()), s.email)

    fun signOut(s: SupabaseSession) {
        try { http.newCall(req("/auth/v1/logout").post("".toRequestBody(JSON_TYPE)).header("Authorization", "Bearer ${s.accessToken}").build()).execute().close() } catch (_: Exception) { }
    }

    fun authed(path: String, s: SupabaseSession) = req(path).header("Authorization", "Bearer ${s.accessToken}")

    fun rawCall(r: Request): Pair<Int, String> = http.newCall(r).execute().use { it.code to (it.body?.string().orEmpty()) }
}

class SupabaseBackend(
    private val client: SupabaseClient,
    private val sessionProvider: suspend () -> SupabaseSession,
) : SyncBackend {
    override val label = "Supabase"
    private val table = "/rest/v1/epubforge_sync"

    private fun check(code: Int, body: String, what: String) {
        if (code == 401 || code == 403) throw SyncAuthError("Supabase refused access. Sign in again.")
        if (code == 404 || body.contains("PGRST205") || body.contains("does not exist")) {
            throw IOException("The epubforge_sync table is missing. Run docs/supabase.sql in your project's SQL editor.")
        }
        if (code !in 200..299) throw IOException("$what failed (HTTP $code): ${body.take(200)}")
    }

    override suspend fun check() {
        val s = sessionProvider()
        val (code, body) = client.rawCall(client.authed("$table?select=version&limit=1", s).get().build())
        check(code, body, "Checking the sync table")
    }

    override suspend fun read(): RemoteDoc? {
        val s = sessionProvider()
        val (code, body) = client.rawCall(client.authed("$table?select=data,version&user_id=eq.${s.userId}", s).get().build())
        check(code, body, "Reading the library")
        val row = SyncJson.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject ?: return null
        return RemoteDoc(row["data"].toString(), row["version"]?.jsonPrimitive?.content)
    }

    override suspend fun write(text: String, previous: RemoteDoc?) {
        val version = previous?.version
        val s = sessionProvider()
        val data = SyncJson.parseToJsonElement(text)
        if (version == null) {
            val payload = buildJsonObject { put("user_id", s.userId); put("data", data); put("version", 1) }.toString()
            val (code, body) = client.rawCall(
                client.authed(table, s).post(payload.toRequestBody(JSON_TYPE)).header("Prefer", "return=minimal").build()
            )
            if (code == 409) throw SyncConflict()
            check(code, body, "Saving the library")
        } else {
            val next = (version.toLongOrNull() ?: 0) + 1
            val payload = buildJsonObject { put("data", data); put("version", next) }.toString()
            // Only succeeds if nobody bumped the version since we read it.
            val (code, body) = client.rawCall(
                client.authed("$table?user_id=eq.${s.userId}&version=eq.$version", s)
                    .patch(payload.toRequestBody(JSON_TYPE)).header("Prefer", "return=representation").build()
            )
            check(code, body, "Saving the library")
            if (SyncJson.parseToJsonElement(body).jsonArray.isEmpty()) throw SyncConflict()
        }
    }
}
