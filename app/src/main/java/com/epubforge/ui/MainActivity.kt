package com.epubforge.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.epubforge.ui.screens.AboutScreen
import com.epubforge.ui.screens.DownloadsScreen
import com.epubforge.ui.screens.HistoryScreen
import com.epubforge.ui.screens.LibraryScreen
import com.epubforge.ui.screens.MoreScreen
import com.epubforge.ui.screens.OptionsScreen
import com.epubforge.ui.screens.SettingsScreen
import com.epubforge.ui.screens.StoryScreen
import com.epubforge.ui.screens.SyncScreen
import com.epubforge.ui.screens.UpdatesScreen
import com.epubforge.ui.theme.EpubForgeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_STORY = "story"
    }

    private val incoming = mutableStateOf<String?>(null)
    private val incomingFiles = mutableStateOf<List<android.net.Uri>>(emptyList())
    private val navRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val settings by app.settings.flow.collectAsStateWithLifecycle()
            EpubForgeTheme(settings.theme, settings.dynamicColor, settings.pureBlack) {
                AppRoot(
                    incoming.value, { incoming.value = null }, navRequest.value, { navRequest.value = null },
                    incomingFiles.value, { incomingFiles.value = emptyList() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_TAB)?.let { navRequest.value = it }
        intent.getStringExtra(EXTRA_STORY)?.let { navRequest.value = "story/$it" }
        // Ebook files opened or shared from a file manager or another app.
        val files = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" })
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE -> (
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            ).orEmpty()
            else -> emptyList()
        }
        if (files.isNotEmpty()) { incomingFiles.value = files; return }
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        Regex("""https?://\S+""").find(text)?.let { incoming.value = it.value }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab("library", "Library", Icons.AutoMirrored.Outlined.MenuBook, Icons.AutoMirrored.Filled.MenuBook),
    Tab("updates", "Updates", Icons.Outlined.NewReleases, Icons.Filled.NewReleases),
    Tab("history", "History", Icons.Outlined.History, Icons.Filled.History),
    Tab("downloads", "Downloads", Icons.Outlined.Download, Icons.Filled.Download),
    Tab("more", "More", Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
)

@Composable
private fun AppRoot(
    incoming: String?, consumeIncoming: () -> Unit, navRequest: String?, consumeNav: () -> Unit,
    incomingFiles: List<android.net.Uri>, consumeFiles: () -> Unit,
) {
    val context = LocalContext.current
    val nav = rememberNavController()
    var addUrl by remember { mutableStateOf<String?>(null) }

    // Importing ebook files.
    var importing by remember { mutableStateOf(false) }
    var importErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    val importScope = rememberCoroutineScope()
    fun importBooks(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        addUrl = null
        importing = true
        importScope.launch {
            val added = mutableListOf<String>()
            val errors = mutableListOf<String>()
            for (u in uris) {
                try { added += context.app.books.import(u).id } catch (e: Exception) { errors += (e.message ?: "Couldn't import a file") }
            }
            importing = false
            importErrors = errors
            if (added.size == 1) nav.navigate("story/${added.first()}") else if (added.isNotEmpty()) nav.navigateTab("library")
        }
    }
    val pickBooks = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importBooks(it) }

    // Look for a newer release each time the app opens (official builds only; at most every 30 min).
    LaunchedEffect(Unit) {
        context.app.settings.awaitLoaded()
        context.app.updater.check(manual = false)
    }
    UpdateDialog()
    LaunchedEffect(incomingFiles) {
        if (incomingFiles.isNotEmpty()) { importBooks(incomingFiles); consumeFiles() }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(incoming) {
        if (incoming != null) { addUrl = incoming; consumeIncoming() }
    }
    LaunchedEffect(navRequest) {
        if (navRequest != null) {
            if (navRequest.startsWith("story/")) nav.navigate(navRequest) else nav.navigateTab(navRequest)
            consumeNav()
        }
    }

    // Bring verification pages to the front while the app is open.
    val challenge by context.app.challenges.pending.collectAsStateWithLifecycle()
    var lastChallenge by remember { mutableLongStateOf(0L) }
    LaunchedEffect(challenge) {
        val c = challenge
        if (c != null && c.id != lastChallenge) {
            lastChallenge = c.id
            context.startActivity(ChallengeActivity.intent(context))
        }
    }

    val queue by context.app.db.queue().observe().collectAsStateWithLifecycle(initialValue = emptyList())
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = tabs.any { it.route == route }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(showBar, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = route == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = {
                                BadgedBox(badge = { if (tab.route == "downloads" && queue.isNotEmpty()) Badge { Text("${queue.size}") } }) {
                                    Icon(if (selected) tab.selectedIcon else tab.icon, tab.label)
                                }
                            },
                            label = { Text(tab.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "library", modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            val openStory: (String) -> Unit = { nav.navigate("story/$it") }
            composable("library") {
                LibraryScreen(onOpenStory = openStory, onAdd = { addUrl = "" }, onImport = { pickBooks.launch(com.epubforge.data.BookFormats.mimeTypes) })
            }
            composable("updates") { UpdatesScreen(onOpenStory = openStory) }
            composable("history") { HistoryScreen(onOpenStory = openStory) }
            composable("downloads") { DownloadsScreen(onOpenStory = openStory) }
            composable("more") { MoreScreen(onNavigate = { nav.navigate(it) }) }
            composable("story/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                StoryScreen(storyId = entry.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() })
            }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
            composable("sync") { SyncScreen(onBack = { nav.popBackStack() }) }
            composable("options") { OptionsScreen(onBack = { nav.popBackStack() }) }
            composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
        }
    }

    if (importing) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Importing") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Adding to your library…") } },
        )
    }
    if (importErrors.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { importErrors = emptyList() },
            confirmButton = { TextButton(onClick = { importErrors = emptyList() }) { Text("OK") } },
            title = { Text("Couldn't import") },
            text = { Text(importErrors.joinToString("\n\n")) },
        )
    }

    addUrl?.let { initial ->
        AddStorySheet(
            initial = initial,
            onImport = { addUrl = null; pickBooks.launch(com.epubforge.data.BookFormats.mimeTypes) },
            onDismiss = { addUrl = null },
            onAdded = { id ->
                addUrl = null
                nav.navigate("story/$id")
            },
        )
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Paste a story link; the app reads the story's details and chapter list and adds it to the library. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddStorySheet(initial: String, onImport: () -> Unit, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(initial) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    fun add() {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            error = "Enter a link starting with https://"
            return
        }
        working = true
        error = null
        status = "Reading the story's chapter list…"
        app.scope.launch {
            try {
                val story = app.repo.addStory(u) { line -> status = line.take(120) }
                scope.launch { onAdded(story.id) }
            } catch (e: Exception) {
                error = com.epubforge.engine.ErrorText.friendly(e.message ?: "Couldn't read that link")
            } finally {
                working = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!working) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text("Add a story", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Paste the link to a story or any of its chapters. Royal Road and the 400+ other sites WebToEpub supports work.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null },
                label = { Text("Story link") },
                placeholder = { Text("https://www.royalroad.com/fiction/…") },
                singleLine = true,
                enabled = !working,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (!working) add() }),
                trailingIcon = {
                    IconButton(onClick = { clipboard.getText()?.text?.let { url = it.trim() } }, enabled = !working) {
                        Icon(Icons.Filled.ContentPaste, "Paste")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (working) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onImport, enabled = !working) { Text("Import a file") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { add() }, enabled = url.isNotBlank() && !working) { Text("Add to library") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
