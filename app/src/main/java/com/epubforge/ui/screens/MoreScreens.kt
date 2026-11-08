package com.epubforge.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.epubforge.BuildConfig
import com.epubforge.R
import com.epubforge.engine.EngineEvent
import com.epubforge.engine.POPUP_URL
import com.epubforge.engine.W2EBridge
import com.epubforge.engine.W2EWebView
import com.epubforge.net.Http
import com.epubforge.net.NativeProxy
import com.epubforge.sync.SyncProvider
import com.epubforge.ui.BookActions
import com.epubforge.ui.Fmt
import com.epubforge.ui.app
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubScreen(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding -> content(Modifier.padding(padding)) }
}

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    val settings by LocalContext.current.app.settings.flow.collectAsState()
    Scaffold { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), null,
                    Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary,
                )
                Text("EpubForge", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
            MoreItem(Icons.Filled.CloudSync, "Backup and sync",
                when {
                    settings.syncProvider != SyncProvider.OFF ->
                        SyncProvider.name(settings.syncProvider) + " • " + if (settings.lastSync > 0) "synced " + Fmt.relativeDay(settings.lastSync).lowercase() else "not synced yet"
                    settings.autoBackupDays > 0 -> "Automatic backups on"
                    else -> "Backups, WebDAV and Supabase sync"
                }) { onNavigate("sync") }
            MoreItem(Icons.Filled.Settings, "Settings", "Appearance, request pacing, retries") { onNavigate("settings") }
            MoreItem(Icons.Filled.Tune, "WebToEpub options", "Author notes, images, chapter limits and more") { onNavigate("options") }
            MoreItem(Icons.Filled.Info, "About", "Versions and licenses") { onNavigate("about") }
        }
    }
}

@Composable
private fun MoreItem(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
internal fun SliderRow(title: String, value: Int, range: IntRange, step: Int, label: (Int) -> String, onChange: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label(v.toInt()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onChange((v.toInt() / step) * step) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
        )
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val s by app.settings.flow.collectAsState()
    var themeDialog by remember { mutableStateOf(false) }
    var clearCookies by remember { mutableStateOf(false) }
    var intervalDialog by remember { mutableStateOf(false) }
    fun update(block: (com.epubforge.data.AppSettings) -> com.epubforge.data.AppSettings) = scope.launch { app.settings.update(block) }

    SubScreen("Settings", onBack) { mod ->
        Column(mod.verticalScroll(rememberScrollState())) {
            SectionTitle("Appearance")
            ListItem(
                leadingContent = { Icon(Icons.Filled.Brightness6, null) },
                headlineContent = { Text("Theme") },
                supportingContent = { Text(listOf("Follow system", "Light", "Dark")[s.theme]) },
                modifier = Modifier.clickable { themeDialog = true },
            )
            SwitchRow("Dynamic color", "Use colors from your wallpaper (Android 12+)", s.dynamicColor) { v -> update { it.copy(dynamicColor = v) } }
            SwitchRow("Pure black dark mode", "Better for OLED screens", s.pureBlack) { v -> update { it.copy(pureBlack = v) } }

            SectionTitle("Downloading")
            Text(
                "Requests to each site are spaced out and retried politely. When a site asks you to verify you're " +
                    "human, the page opens for you to complete, then downloading resumes.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SliderRow("Royal Road delay between requests", s.royalRoadDelayMs, 500..10_000, 250, { "${"%.2f".format(it / 1000.0)} s" }) { v ->
                update { it.copy(royalRoadDelayMs = v) }
            }
            SliderRow("Other sites delay between requests", s.defaultDelayMs, 0..10_000, 250, { "${"%.2f".format(it / 1000.0)} s" }) { v ->
                update { it.copy(defaultDelayMs = v) }
            }
            SliderRow("Retries when rate limited", s.maxRetries, 0..10, 1, { "$it" }) { v -> update { it.copy(maxRetries = v) } }
            SectionTitle("Download defaults")
            Text(
                "Used for stories you add from now on; change them per story on its page (⋮ → Download settings).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SwitchRow("Download images", null, s.includeImages) { v -> update { it.copy(includeImages = v) } }
            SwitchRow("Include author's notes", null, s.includeAuthorNotes) { v -> update { it.copy(includeAuthorNotes = v) } }
            SliderRow("Pause downloads after failures in a row", s.stopAfterFailures, 0..20, 1, { if (it == 0) "Never" else "$it" }) { v ->
                update { it.copy(stopAfterFailures = v) }
            }

            SectionTitle("Library updates")
            ListItem(
                headlineContent = { Text("Check for new chapters") },
                supportingContent = { Text(if (s.updateIntervalHours == 0) "Off" else "Every ${s.updateIntervalHours} hours") },
                modifier = Modifier.clickable { intervalDialog = true },
            )
            SwitchRow("Download new chapters automatically", "New chapters are queued for offline reading", s.autoDownloadNew) { v ->
                update { it.copy(autoDownloadNew = v) }
            }
            SwitchRow("Only on Wi-Fi", null, s.updateWifiOnly) { v -> update { it.copy(updateWifiOnly = v) } }
            SwitchRow("Notify about new chapters", null, s.notifyNewChapters) { v -> update { it.copy(notifyNewChapters = v) } }

            SectionTitle("Site data")
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Cookie, null) },
                headlineContent = { Text("Clear site cookies") },
                supportingContent = { Text("Signs you out of sites and forgets verifications") },
                modifier = Modifier.clickable { clearCookies = true },
            )
        }
    }

    if (themeDialog) {
        AlertDialog(
            onDismissRequest = { themeDialog = false },
            title = { Text("Theme") },
            text = {
                Column {
                    listOf("Follow system", "Light", "Dark").forEachIndexed { i, label ->
                        ListItem(
                            headlineContent = { Text(label) },
                            trailingContent = { if (s.theme == i) Icon(Icons.Filled.Check, null) },
                            modifier = Modifier.clickable { update { it.copy(theme = i) }; themeDialog = false },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { themeDialog = false }) { Text("Close") } },
        )
    }
    if (intervalDialog) {
        AlertDialog(
            onDismissRequest = { intervalDialog = false },
            title = { Text("Check for new chapters") },
            text = {
                Column {
                    listOf(0 to "Off", 6 to "Every 6 hours", 12 to "Every 12 hours", 24 to "Daily", 48 to "Every 2 days").forEach { (h, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            trailingContent = { if (s.updateIntervalHours == h) Icon(Icons.Filled.Check, null) },
                            modifier = Modifier.clickable { update { it.copy(updateIntervalHours = h) }; intervalDialog = false },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { intervalDialog = false }) { Text("Close") } },
        )
    }
    if (clearCookies) {
        AlertDialog(
            onDismissRequest = { clearCookies = false },
            title = { Text("Clear cookies?") },
            text = { Text("You'll be signed out of sites and may need to verify again.") },
            confirmButton = {
                TextButton(onClick = {
                    clearCookies = false
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { clearCookies = false }) { Text("Cancel") } },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OptionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    SubScreen("WebToEpub options", onBack) { mod ->
        Column(mod.fillMaxSize()) {
            Text(
                "This is WebToEpub's own page. Open “Advanced Options” to change how books are built " +
                    "(how pages are cleaned up, chapter title handling and more). Settings apply to every download.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val proxy = NativeProxy(app.http, app.gate, app.challenges, Http.userAgent(ctx), { app.settings.current.maxRetries })
                    val bridge = W2EBridge(ctx, proxy) { e ->
                        if (e is EngineEvent.OpenUrl) BookActions.openUrl(ctx, e.url)
                    }
                    WebView(ctx).also {
                        W2EWebView.configure(it, proxy, bridge)
                        it.loadUrl(POPUP_URL)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val w2eVersion = remember {
        try {
            val manifest = context.assets.open("w2e/manifest.json").bufferedReader().readText()
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(manifest)?.groupValues?.get(1) ?: "?"
        } catch (e: Exception) { "?" }
    }
    val w2eCommit = remember {
        try { context.assets.open("w2e/w2e_commit.txt").bufferedReader().readText().trim() } catch (e: Exception) { "" }
    }
    SubScreen("About", onBack) { mod ->
        Column(mod.verticalScroll(rememberScrollState())) {
            ListItem(headlineContent = { Text("Version") }, supportingContent = { Text(BuildConfig.VERSION_NAME) })
            ListItem(
                headlineContent = { Text("WebToEpub") },
                supportingContent = { Text("$w2eVersion${if (w2eCommit.isNotBlank()) " ($w2eCommit)" else ""} — by David Teviotdale and contributors") },
                modifier = Modifier.clickable { BookActions.openUrl(context, "https://github.com/dteviot/WebToEpub") },
            )
            ListItem(
                headlineContent = { Text("Reader engine") },
                supportingContent = { Text("foliate-js by John Factotum, via Readest's fork (MIT)") },
                modifier = Modifier.clickable { BookActions.openUrl(context, "https://github.com/readest/foliate-js") },
            )
            ListItem(
                headlineContent = { Text("License") },
                supportingContent = { Text("Free software under GPL-3.0-or-later. EpubForge bundles WebToEpub (GPL-3.0) and foliate-js (MIT).") },
            )
            ListItem(
                headlineContent = { Text("Design") },
                supportingContent = { Text("Interface inspired by Mihon.") },
                modifier = Modifier.clickable { BookActions.openUrl(context, "https://github.com/mihonapp/mihon") },
            )
        }
    }
}
