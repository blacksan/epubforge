package com.epubforge.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.epubforge.sync.SupabaseSetup
import com.epubforge.sync.SyncProvider
import com.epubforge.sync.SyncState
import com.epubforge.sync.WebDavBackend
import com.epubforge.ui.Fmt
import com.epubforge.ui.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val s by app.settings.flow.collectAsStateWithLifecycle()
    val state by app.sync.state.collectAsStateWithLifecycle()

    var choice by remember(s.syncProvider) { mutableStateOf(s.syncProvider) }
    var busy by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    // ---------- local backup launchers
    var backupBusy by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }
    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupBusy = true
        scope.launch {
            backupMessage = try {
                val files = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")!!.use { app.backups.create(it, s.backupIncludeChapters) }
                }
                app.settings.update { it.copy(lastBackup = System.currentTimeMillis(), lastBackupError = "") }
                "Backup saved" + if (s.backupIncludeChapters) " ($files files)." else "."
            } catch (e: Exception) {
                "Backup failed: ${e.message}"
            }
            backupBusy = false
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore = uri
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        scope.launch {
            app.settings.update { it.copy(autoBackupFolder = uri.toString(), autoBackupDays = if (it.autoBackupDays == 0) 1 else it.autoBackupDays) }
        }
    }
    var frequencyDialog by remember { mutableStateOf(false) }

    SubScreen("Backup and sync", onBack) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

            // ================= Sync
            SectionTitle("Sync between devices")
            Text(
                "Keeps your library, chapter lists, reading progress and history the same on every device. " +
                    "Downloaded chapters stay on each device. Pick a service you control; EpubForge has no server of its own.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
            val options = listOf(SyncProvider.OFF to "Off", SyncProvider.WEBDAV to "WebDAV", SyncProvider.SUPABASE to "Supabase")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                options.forEachIndexed { i, (p, label) ->
                    SegmentedButton(
                        selected = choice == p,
                        onClick = {
                            formError = null
                            if (p == SyncProvider.OFF && s.syncProvider != SyncProvider.OFF) {
                                scope.launch { app.sync.disconnect() }
                            }
                            choice = p
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(label) }
                }
            }

            val connected = s.syncProvider != SyncProvider.OFF && s.syncProvider == choice
            if (connected) {
                SyncStatusCard(
                    title = SyncProvider.name(s.syncProvider),
                    account = if (s.syncProvider == SyncProvider.WEBDAV) "${s.webdavUser.ifBlank { "anonymous" }} • ${Uri.parse(s.webdavUrl).host ?: s.webdavUrl}"
                    else s.supabaseEmail + " • " + (Uri.parse(s.supabaseUrl).host ?: s.supabaseUrl),
                    state = state,
                    lastSync = s.lastSync,
                    lastError = s.lastSyncError,
                    onSync = { scope.launch { app.sync.syncNow() } },
                    onDisconnect = { scope.launch { app.sync.disconnect(); choice = SyncProvider.OFF } },
                )
                SwitchRow("Automatic sync", "After changes and every 6 hours", s.autoSync) { v -> scope.launch { app.settings.update { it.copy(autoSync = v) } } }
                SwitchRow("Sync on Wi-Fi only", null, s.syncWifiOnly) { v -> scope.launch { app.settings.update { it.copy(syncWifiOnly = v) } } }
            } else when (choice) {
                SyncProvider.WEBDAV -> WebDavForm(
                    initialUrl = s.webdavUrl, initialUser = s.webdavUser, busy = busy, error = formError,
                    onConnect = { url, user, pass ->
                        busy = true; formError = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { WebDavBackend(app.sync.http, url, user, pass).check() }
                                app.secrets.webdavPassword = pass
                                app.settings.update { it.copy(syncProvider = SyncProvider.WEBDAV, webdavUrl = url.trim(), webdavUser = user.trim(), lastSyncError = "") }
                                app.sync.connect()
                            } catch (e: Exception) {
                                formError = e.message ?: "Couldn't connect"
                            }
                            busy = false
                        }
                    },
                )
                SyncProvider.SUPABASE -> SupabaseForm(
                    initialUrl = s.supabaseUrl, initialKey = s.supabaseKey, initialEmail = s.supabaseEmail, busy = busy, error = formError, notice = notice,
                    onSubmit = { url, key, email, pass, create ->
                        busy = true; formError = null; notice = null
                        scope.launch {
                            try {
                                val client = app.sync.supabaseClient(url, key)
                                val session = withContext(Dispatchers.IO) {
                                    if (create) client.signUp(email.trim(), pass) else client.signIn(email.trim(), pass)
                                }
                                if (session == null) {
                                    notice = "Account created. Confirm it from the email Supabase sent, then sign in here."
                                } else {
                                    app.sync.storeSession(session)
                                    app.settings.update {
                                        it.copy(syncProvider = SyncProvider.SUPABASE, supabaseUrl = client.url, supabaseKey = key.trim(), supabaseEmail = session.email, lastSyncError = "")
                                    }
                                    app.sync.connect().onFailure { e -> formError = e.message }
                                }
                            } catch (e: Exception) {
                                formError = e.message ?: "Sign-in failed"
                            }
                            busy = false
                        }
                    },
                )
                else -> Unit
            }

            // ================= Local backup
            SectionTitle("Backup file")
            Text(
                "A single file with your library, chapter lists, reading progress, history and covers. " +
                    "Keep it anywhere, or move it to a new phone. Restoring merges it into your current library.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SwitchRow("Include downloaded chapters and imported books", "Bigger file, but nothing to download or import again", s.backupIncludeChapters) { v ->
                scope.launch { app.settings.update { it.copy(backupIncludeChapters = v) } }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { createLauncher.launch(app.backups.fileName()) }, enabled = !backupBusy) { Text("Create backup") }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }, enabled = !backupBusy) { Text("Restore") }
                if (backupBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            backupMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }

            ListItem(
                headlineContent = { Text("Automatic backups") },
                supportingContent = {
                    Text(when (s.autoBackupDays) { 0 -> "Off"; 1 -> "Daily, keeping the last ${s.autoBackupKeep}"; else -> "Weekly, keeping the last ${s.autoBackupKeep}" })
                },
                modifier = Modifier.clickable { frequencyDialog = true },
            )
            val folderName = s.autoBackupFolder.takeIf { it.isNotBlank() }?.let {
                try { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name } catch (_: Exception) { null } ?: "Chosen folder"
            }
            ListItem(
                headlineContent = { Text("Backup folder") },
                supportingContent = { Text(folderName ?: "Not set. Tip: pick a folder that Syncthing, Nextcloud or similar copies off the phone.") },
                modifier = Modifier.clickable { folderLauncher.launch(null) },
            )
            if (s.autoBackupDays > 0 && s.autoBackupFolder.isNotBlank()) {
                SliderRow("Backups to keep", s.autoBackupKeep, 1..20, 1, { "$it" }) { v -> scope.launch { app.settings.update { it.copy(autoBackupKeep = v) } } }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        backupBusy = true
                        scope.launch {
                            backupMessage = app.backups.runAutomatic().fold({ "Saved $it to the backup folder." }, { "Backup failed: ${it.message}" })
                            backupBusy = false
                        }
                    }, enabled = !backupBusy) { Text("Back up now") }
                    if (s.lastBackup > 0) Text("Last: ${Fmt.dateTime(s.lastBackup)}", style = MaterialTheme.typography.bodySmall)
                }
                if (s.lastBackupError.isNotBlank()) {
                    Text(s.lastBackupError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            // ================= Android backup
            SectionTitle("Android backup")
            Text(
                "Android also backs up your library, settings and reading progress with your phone's own backup " +
                    "(Google, your phone maker's, or Seedvault), and restores them when you set up a new phone. " +
                    "A cable or phone-to-phone transfer brings your downloaded chapters too. Sync passwords and sign-ins are never included. " +
                    "Turn it on or off in your phone's Settings › System › Backup.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore this backup?") },
            text = { Text("Stories and reading progress from the backup are merged into your library. Newer progress on this phone is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    backupBusy = true
                    scope.launch {
                        backupMessage = try {
                            val r = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { app.backups.restore(it) } }
                            "Restored ${r.stories} ${if (r.stories == 1) "story" else "stories"}" +
                                if (r.chapterFiles > 0) " and ${r.chapterFiles} downloaded chapters." else "."
                        } catch (e: Exception) {
                            "Restore failed: ${e.message}"
                        }
                        backupBusy = false
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }

    if (frequencyDialog) {
        AlertDialog(
            onDismissRequest = { frequencyDialog = false },
            title = { Text("Automatic backups") },
            text = {
                Column {
                    listOf(0 to "Off", 1 to "Daily", 7 to "Weekly").forEach { (d, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = s.autoBackupDays == d) {
                                frequencyDialog = false
                                scope.launch { app.settings.update { it.copy(autoBackupDays = d) } }
                                if (d > 0 && s.autoBackupFolder.isBlank()) folderLauncher.launch(null)
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = s.autoBackupDays == d, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { frequencyDialog = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun SyncStatusCard(
    title: String, account: String, state: SyncState, lastSync: Long, lastError: String,
    onSync: () -> Unit, onDisconnect: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(account, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            val running = state is SyncState.Running
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onSync, enabled = !running) { Text("Sync now") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                if (running) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            if (state is SyncState.Running) Text(state.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            val err = (state as? SyncState.Error)?.message ?: lastError.ifBlank { null }
            if (err != null && !running) Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            if (lastSync > 0) Text("Last synced ${Fmt.dateTime(lastSync)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (visible) "Hide password" else "Show password")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WebDavForm(initialUrl: String, initialUser: String, busy: Boolean, error: String?, onConnect: (String, String, String) -> Unit) {
    var url by remember { mutableStateOf(initialUrl) }
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Works with Nextcloud, ownCloud, Koofr, pCloud, Box, most NAS boxes and any WebDAV server. " +
                "EpubForge keeps one small file in an EpubForge folder there. " +
                "Nextcloud address: https://your.server/remote.php/dav/files/USERNAME/ — use an app password.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(url, { url = it }, label = { Text("Server address") }, singleLine = true,
            placeholder = { Text("https://example.com/dav/") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        PasswordField(pass, { pass = it }, "Password or app password")
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onConnect(url, user, pass) }, enabled = !busy && url.startsWith("http")) { Text(if (busy) "Connecting…" else "Connect") }
            if (busy) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        }
    }
}

@Composable
private fun SupabaseForm(
    initialUrl: String, initialKey: String, initialEmail: String, busy: Boolean, error: String?, notice: String?,
    onSubmit: (url: String, key: String, email: String, password: String, create: Boolean) -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var key by remember { mutableStateOf(initialKey) }
    var email by remember { mutableStateOf(initialEmail) }
    var pass by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Use your own free Supabase project. One-time setup: create a project at supabase.com, open its SQL editor, " +
                "and run the setup script below. Then enter the project URL and its anon (publishable) key from Project Settings › API.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { clipboard.setText(AnnotatedString(SupabaseSetup.SQL)); copied = true }) {
            Text(if (copied) "Setup script copied" else "Copy setup script")
        }
        OutlinedTextField(url, { url = it }, label = { Text("Project URL") }, singleLine = true,
            placeholder = { Text("https://abcd1234.supabase.co") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text("Anon / publishable key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        PasswordField(pass, { pass = it }, "Password")
        if (notice != null) Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        val ready = !busy && url.startsWith("http") && key.isNotBlank() && email.contains("@") && pass.length >= 6
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSubmit(url, key, email, pass, false) }, enabled = ready) { Text("Sign in") }
            OutlinedButton(onClick = { onSubmit(url, key, email, pass, true) }, enabled = ready) { Text("Create account") }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
