package com.epubforge.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.epubforge.App
import com.epubforge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A published release with an APK attached. */
data class AppRelease(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val pageUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: AppRelease, val file: File) : UpdateState
    data class Error(val message: String, val release: AppRelease? = null) : UpdateState
}

/**
 * Checks the project's GitHub releases for a newer version when the app opens, downloads the APK
 * and hands it to Android's installer. Releases are signed with the same key, so they install over
 * the current version and keep the library.
 */
class Updater(private val app: App) {
    private val repo = BuildConfig.UPDATE_REPO
    val enabled: Boolean get() = repo.isNotBlank()
    val currentVersion: String = BuildConfig.VERSION_NAME

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val prefs = app.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val dir get() = File(app.cacheDir, "updates")

    var skippedVersion: String
        get() = prefs.getString("skipped", "") ?: ""
        set(v) { prefs.edit().putString("skipped", v).apply() }

    /**
     * Checks GitHub for a newer release. On launch ([manual] = false) this is skipped if a check ran in
     * the last 30 minutes and ignores a version the user chose to skip. Uses ETags, so an unchanged
     * answer costs almost nothing and doesn't count against GitHub's rate limit.
     */
    suspend fun check(manual: Boolean, repoOverride: String? = null, currentOverride: String? = null): UpdateState = mutex.withLock {
        val source = repoOverride ?: repo
        if (source.isBlank()) return@withLock UpdateState.Idle.also { _state.value = it }
        if (!manual && !app.settings.current.checkUpdates) return@withLock _state.value
        val now = System.currentTimeMillis()
        if (!manual && repoOverride == null && now - prefs.getLong("lastCheck", 0) < 30 * 60_000L) {
            // Recently checked: reuse the stored answer.
            val cached = prefs.getString("body", null)?.let { parse(it) }
            return@withLock decide(cached, currentOverride ?: currentVersion, manual)
        }
        _state.value = UpdateState.Checking
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/$source/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "EpubForge/$currentVersion")
                    .apply { prefs.getString("etag", null)?.takeIf { repoOverride == null }?.let { header("If-None-Match", it) } }
                    .build()
                val body = http.newCall(req).execute().use { r ->
                    when {
                        r.code == 304 -> prefs.getString("body", null)
                        r.code == 404 -> null // no releases yet
                        !r.isSuccessful -> throw IOException("GitHub answered HTTP ${r.code}")
                        else -> r.body?.string().also { b ->
                            if (repoOverride == null) prefs.edit().putString("etag", r.header("ETag")).putString("body", b).apply()
                        }
                    }
                }
                if (repoOverride == null) prefs.edit().putLong("lastCheck", now).apply()
                decide(body?.let { parse(it) }, currentOverride ?: currentVersion, manual)
            } catch (e: Exception) {
                Log.w("Updater", "Update check failed", e)
                // A failed check on launch stays quiet; only a manual check reports it.
                (if (manual) UpdateState.Error("Couldn't check for updates: ${e.message}") else UpdateState.Idle).also { _state.value = it }
            }
        }
    }

    private fun decide(release: AppRelease?, current: String, manual: Boolean): UpdateState {
        val s = when {
            release == null -> if (manual) UpdateState.UpToDate else UpdateState.Idle
            !isNewer(release.version, current) -> if (manual) UpdateState.UpToDate else UpdateState.Idle
            !manual && release.version == skippedVersion -> UpdateState.Idle
            else -> pendingFile(release)?.let { UpdateState.ReadyToInstall(release, it) } ?: UpdateState.Available(release)
        }
        _state.value = s
        return s
    }

    private fun parse(body: String): AppRelease? = try {
        val o = json.parseToJsonElement(body).jsonObject
        if (o["draft"]?.jsonPrimitive?.booleanOrNull == true || o["prerelease"]?.jsonPrimitive?.booleanOrNull == true) null
        else {
            val asset = o["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.str("name").endsWith(".apk") }
            asset?.let {
                AppRelease(
                    version = o.str("tag_name").removePrefix("v"),
                    notes = o.str("body"),
                    apkUrl = it.str("browser_download_url"),
                    apkSize = it["size"]?.jsonPrimitive?.longOrNull ?: 0,
                    pageUrl = o.str("html_url"),
                )
            }
        }
    } catch (e: Exception) {
        Log.w("Updater", "Unreadable release info", e)
        null
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull ?: ""

    private fun fileFor(r: AppRelease) = File(dir, "EpubForge-${r.version}.apk")
    private fun pendingFile(r: AppRelease) = fileFor(r).takeIf { it.isFile && (r.apkSize <= 0 || it.length() == r.apkSize) }

    fun dismiss() { if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle }

    fun skip(r: AppRelease) { skippedVersion = r.version; _state.value = UpdateState.Idle }

    /** Downloads the APK (with progress), then opens the installer. */
    suspend fun download(r: AppRelease) = withContext(Dispatchers.IO) {
        try {
            dir.mkdirs()
            dir.listFiles()?.filter { it.name != fileFor(r).name }?.forEach { it.delete() }
            val out = fileFor(r)
            val part = File(dir, out.name + ".part")
            _state.value = UpdateState.Downloading(r, 0f)
            http.newCall(Request.Builder().url(r.apkUrl).header("User-Agent", "EpubForge/$currentVersion").build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed (HTTP ${resp.code})")
                val body = resp.body ?: throw IOException("Empty download")
                val total = body.contentLength().takeIf { it > 0 } ?: r.apkSize
                var done = 0L
                var lastShown = 0f
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = done.toFloat() / total
                                if (p - lastShown >= 0.01f) { lastShown = p; _state.value = UpdateState.Downloading(r, p) }
                            }
                        }
                    }
                }
                if (r.apkSize > 0 && part.length() != r.apkSize) throw IOException("The download was incomplete. Try again.")
            }
            if (!part.renameTo(out)) throw IOException("Couldn't save the update")
            Log.i("Updater", "Downloaded ${out.length()} bytes for ${r.version}")
            _state.value = UpdateState.ReadyToInstall(r, out)
            withContext(Dispatchers.Main) { install(out) }
        } catch (e: Exception) {
            Log.w("Updater", "Update download failed", e)
            _state.value = UpdateState.Error(e.message ?: "Download failed", r)
        }
    }

    /** True when Android needs the user to allow EpubForge to install apps first. */
    fun needsInstallPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Hands the APK to Android's package installer, which asks the user to confirm. */
    fun install(file: File) {
        if (needsInstallPermission()) return // the dialog explains and offers the settings page
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        /** Compares dotted versions numerically ("1.10.0" > "1.9.2"); a "-suffix" counts as older than the plain version. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String): Pair<List<Int>, Boolean> {
                val clean = v.trim().removePrefix("v")
                val main = clean.substringBefore('-').substringBefore('+')
                return main.split('.').map { it.toIntOrNull() ?: 0 } to clean.contains('-')
            }
            val (a, aPre) = parts(candidate)
            val (b, bPre) = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return bPre && !aPre
        }
    }
}
