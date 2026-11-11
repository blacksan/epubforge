package com.epubforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epubforge.update.AppRelease
import com.epubforge.update.UpdateState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Release notes as plain text: GitHub's generated notes are Markdown with links and headings. */
private fun plainNotes(md: String): String = md
    .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
    .replace(Regex("""https?://\S+"""), "")
    .lines()
    .map { it.trim().removePrefix("#").removePrefix("#").removePrefix("#").trim().removePrefix("* ").removePrefix("- ") }
    .filter { it.isNotBlank() && !it.startsWith("**Full Changelog**") && !it.startsWith("Full Changelog") }
    .joinToString("\n")
    .take(1500)

/** Shows "Update available", download progress and the install step. */
@Composable
fun UpdateDialog() {
    val context = LocalContext.current
    val updater = context.app.updater
    val state by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var hiddenDownload by remember { mutableStateOf(false) }
    // Re-check the install permission when coming back from Android's settings page.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }

    fun download(r: AppRelease) {
        hiddenDownload = false
        scope.launch { updater.download(r) }
    }

    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("Update available") },
            text = {
                Column {
                    Text("EpubForge ${s.release.version} is out. You have ${updater.currentVersion}.")
                    val notes = plainNotes(s.release.notes)
                    if (notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text("What's new", style = MaterialTheme.typography.titleSmall)
                        Text(
                            notes, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = { Button(onClick = { download(s.release) }) { Text("Update") } },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { updater.skip(s.release) }) { Text("Skip this version") }
                    TextButton(onClick = { updater.dismiss() }) { Text("Later") }
                }
            },
        )

        is UpdateState.Downloading -> if (!hiddenDownload) AlertDialog(
            onDismissRequest = { hiddenDownload = true },
            title = { Text("Downloading update") },
            text = {
                Column {
                    Text("EpubForge ${s.release.version} • ${(s.progress * 100).roundToInt()}%")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { hiddenDownload = true }) { Text("Hide") } },
        )

        is UpdateState.ReadyToInstall -> {
            val needsPermission = remember(resumes) { updater.needsInstallPermission() }
            AlertDialog(
                onDismissRequest = { updater.dismiss() },
                title = { Text("Ready to install") },
                text = {
                    Text(
                        if (needsPermission) "Android needs your permission for EpubForge to install its own updates. " +
                            "Turn on “Allow from this source”, come back, then tap Install."
                        else "EpubForge ${s.release.version} is downloaded. Android will ask you to confirm the update; " +
                            "your library and settings stay as they are."
                    )
                },
                confirmButton = {
                    if (needsPermission) Button(onClick = { updater.openInstallPermissionSettings(context) }) { Text("Open settings") }
                    else Button(onClick = { updater.install(s.file) }) { Text("Install") }
                },
                dismissButton = { TextButton(onClick = { updater.dismiss() }) { Text("Later") } },
            )
        }

        is UpdateState.Error -> if (s.release != null) AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("Update failed") },
            text = { Text(s.message) },
            confirmButton = { Button(onClick = { download(s.release) }) { Text("Try again") } },
            dismissButton = { TextButton(onClick = { updater.dismiss() }) { Text("Close") } },
        )

        else -> Unit
    }
}

