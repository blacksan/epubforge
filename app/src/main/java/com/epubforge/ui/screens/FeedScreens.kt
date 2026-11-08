package com.epubforge.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.epubforge.data.Story
import com.epubforge.engine.DownloadState
import com.epubforge.reader.ReaderActivity
import com.epubforge.ui.ChallengeActivity
import com.epubforge.ui.EmptyState
import com.epubforge.ui.Fmt
import com.epubforge.ui.StoryCover
import com.epubforge.ui.app
import kotlinx.coroutines.launch

/** Mihon's Updates tab: newly found chapters across the library. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(onOpenStory: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val updates by remember { app.db.chapters().observeUpdates() }.collectAsStateWithLifecycle(initialValue = null)
    val stories by remember { app.db.stories().observeLibrary() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val byId = remember(stories) { stories.associate { it.story.id to it.story } }
    val dl by app.downloads.states.collectAsStateWithLifecycle()
    val progress by app.updates.progress.collectAsStateWithLifecycle()

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text("Updates")
                    if (progress.running) Text(
                        "Checking ${progress.current}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            actions = {
                IconButton(onClick = { app.scope.launch { app.updates.check() } }, enabled = !progress.running) {
                    Icon(Icons.Filled.Refresh, "Update library")
                }
            },
        )
    }) { padding ->
        PullToRefreshBox(
            isRefreshing = progress.running,
            onRefresh = { app.scope.launch { app.updates.check() } },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val list = updates
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(
                    Icons.Outlined.NewReleases, "No recent updates",
                    "New chapters found when your library is checked appear here. Pull down to check now.",
                )
                else -> {
                    val groups = remember(list) { list.groupBy { Fmt.relativeDay(it.chapter.fetchedAt) } }
                    LazyColumn(Modifier.fillMaxSize()) {
                        groups.forEach { (day, entries) ->
                            item(key = "h-$day") { DayHeader(day) }
                            items(entries, key = { it.chapter.storyId + it.chapter.url }) { u ->
                                val c = u.chapter
                                val story = byId[c.storyId]
                                val state = dl[app.downloads.key(c.storyId, c.url)]
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        if (c.downloaded) context.startActivity(ReaderActivity.intent(context, c.storyId, c.url))
                                        else onOpenStory(c.storyId)
                                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (story != null) StoryCover(story, Modifier.width(40.dp).aspectRatio(2f / 3f).clickable { onOpenStory(c.storyId) })
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f).alpha(if (c.read) 0.45f else 1f)) {
                                        Text(u.storyTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(c.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    when {
                                        c.downloaded -> Icon(Icons.Filled.CheckCircle, "Downloaded", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                                        state?.status == DownloadState.Status.Error -> Icon(Icons.Filled.Error, "Failed", tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                                        state != null -> CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.5.dp)
                                        else -> IconButton(onClick = { scope.launch { app.downloads.enqueue(c.storyId, listOf(c.url)) } }) {
                                            Icon(Icons.Outlined.ArrowCircleDown, "Download chapter")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: String) {
    Text(
        day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** Mihon's History tab: the last chapter read in each story. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpenStory: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val history by remember { app.db.history().observe() }.collectAsStateWithLifecycle(initialValue = null)
    val stories by remember { app.db.stories().observeLibrary() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val byId = remember(stories) { stories.associate { it.story.id to it.story } }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("History") },
            actions = {
                if (!history.isNullOrEmpty()) IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.DeleteSweep, "Clear history") }
            },
        )
    }) { padding ->
        val list = history
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(Icons.Outlined.History, "Nothing read yet", "Chapters you read show up here so you can jump back in.", Modifier.padding(padding))
            else -> {
                val groups = remember(list) { list.groupBy { Fmt.relativeDay(it.entry.readAt) } }
                LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                    groups.forEach { (day, entries) ->
                        item(key = "h-$day") { DayHeader(day) }
                        items(entries, key = { it.entry.storyId }) { h ->
                            val story = byId[h.entry.storyId]
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    context.startActivity(ReaderActivity.intent(context, h.entry.storyId, null))
                                }.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (story != null) StoryCover(story, Modifier.width(48.dp).aspectRatio(2f / 3f).clickable { onOpenStory(h.entry.storyId) })
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(h.storyTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${h.entry.chapterTitle} • ${Fmt.time(h.entry.readAt)}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { scope.launch { app.db.history().delete(h.entry.storyId) } }) {
                                    Icon(Icons.Filled.Delete, "Remove from history")
                                }
                                IconButton(onClick = { context.startActivity(ReaderActivity.intent(context, h.entry.storyId, null)) }) {
                                    Icon(Icons.Filled.PlayArrow, "Resume")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear history?") },
        text = { Text("Your library and reading progress are kept.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { app.db.history().clear() } }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

/** Download queue, grouped by story, with live status (Mihon's download queue). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onOpenStory: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val queue by remember { app.db.queue().observe() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val states by app.downloads.states.collectAsStateWithLifecycle()
    val status by app.downloads.status.collectAsStateWithLifecycle()
    val challenge by app.challenges.pending.collectAsStateWithLifecycle()
    val stories by remember { app.db.stories().observeLibrary() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val storyById = remember(stories) { stories.associate { it.story.id to it.story } }
    var settingsFor by remember { mutableStateOf<Story?>(null) }
    var menu by remember { mutableStateOf(false) }

    // Chapter titles for queued items.
    val titles = remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(queue) {
        val ids = queue.map { it.storyId }.distinct()
        val map = HashMap<String, String>()
        ids.forEach { id -> app.db.chapters().list(id).forEach { map[app.downloads.key(id, it.url)] = it.title } }
        titles.value = map
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Downloads")
                        if (queue.isNotEmpty()) Text(
                            "${queue.size} chapter${if (queue.size == 1) "" else "s"} queued",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (queue.isNotEmpty()) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Retry failed") }, onClick = { menu = false; app.downloads.retryFailed() })
                            DropdownMenuItem(text = { Text("Cancel all") }, onClick = { menu = false; scope.launch { app.downloads.clear() } })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (queue.isNotEmpty()) {
                val paused = status.paused || app.downloads.isPaused
                ExtendedFloatingActionButton(
                    onClick = { if (paused || !status.running) app.downloads.resume() else app.downloads.pause() },
                    icon = { Icon(if (paused || !status.running) Icons.Filled.PlayArrow else Icons.Filled.Pause, null) },
                    text = { Text(if (paused || !status.running) "Resume" else "Pause") },
                )
            }
        },
    ) { padding ->
        if (queue.isEmpty()) {
            EmptyState(
                Icons.Outlined.Download, "No downloads",
                "Download chapters from a story's page. They're saved on your phone for reading offline.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        val groups = queue.groupBy { it.storyId }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            challenge?.let { c ->
                item(key = "challenge") {
                    Card(
                        Modifier.fillMaxWidth().padding(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.VerifiedUser, null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Verification needed", style = MaterialTheme.typography.titleSmall)
                                Text("${c.host} wants to check you're human.", style = MaterialTheme.typography.bodySmall)
                            }
                            FilledTonalButton(onClick = { context.startActivity(ChallengeActivity.intent(context)) }) { Text("Open") }
                        }
                    }
                }
            }
            if (status.message.isNotBlank()) item(key = "status") {
                Text(
                    status.message, style = MaterialTheme.typography.bodySmall,
                    color = if (status.paused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            groups.forEach { (storyId, items) ->
                val story = storyById[storyId]
                item(key = "s-$storyId") {
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenStory(storyId) }.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (story != null) StoryCover(story, Modifier.width(32.dp).aspectRatio(2f / 3f))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(story?.title ?: "Story", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${items.size} queued", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { settingsFor = story }) { Icon(Icons.Filled.MoreVert, "Download settings and log") }
                    }
                }
                items(items, key = { it.storyId + it.chapterUrl }) { q ->
                    val k = app.downloads.key(q.storyId, q.chapterUrl)
                    val st = states[k]
                    Row(
                        Modifier.fillMaxWidth().padding(start = 58.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(titles.value[k] ?: q.chapterUrl, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            when (st?.status) {
                                DownloadState.Status.Downloading -> {
                                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp))
                                }
                                DownloadState.Status.Error -> Text(
                                    st.message.ifBlank { "Failed" }, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                else -> Text("Queued", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (st?.status == DownloadState.Status.Error) {
                            IconButton(onClick = { app.downloads.retryFailed(q.storyId) }) { Icon(Icons.Filled.Refresh, "Retry") }
                        }
                        IconButton(onClick = { scope.launch { app.downloads.cancel(q.storyId, q.chapterUrl) } }) { Icon(Icons.Filled.Close, "Cancel") }
                    }
                }
            }
        }
    }

    settingsFor?.let { s -> QueueStorySheet(s) { settingsFor = null } }
}

/** Per-story sheet on the Downloads tab: options in use, actions and the download log. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueStorySheet(story: Story, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val logs by app.downloads.logs.collectAsStateWithLifecycle()
    val log = logs[story.id] ?: emptyList()
    val settings = app.settings.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(story.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height4())
            Line("Images", if (story.downloadImages) "Downloaded" else "Skipped")
            Line("Author's notes", if (story.includeAuthorNotes) "Included" else "Removed")
            Line(
                "Delay between requests",
                if (story.delayMs >= 0) "%.2f s".format(story.delayMs / 1000.0)
                else "Site default (%.2f s)".format((if (story.site.contains("royalroad")) settings.royalRoadDelayMs else settings.defaultDelayMs) / 1000.0),
            )
            Line("Pause after failures in a row", if (settings.stopAfterFailures > 0) "${settings.stopAfterFailures}" else "Never")
            Text(
                "Change these on the story's page (⋮ → Download settings).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { scope.launch { app.downloads.moveToTop(story.id) }; onDismiss() }) { Text("Move to top") }
                TextButton(onClick = { scope.launch { app.downloads.cancelStory(story.id) }; onDismiss() }) { Text("Cancel all") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(story.url + "\n" + log.joinToString("\n")))
                    android.widget.Toast.makeText(context, "Log copied", android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy log")
                }
            }
            val scroll = rememberScrollState()
            LaunchedEffect(log.size) { scroll.scrollTo(scroll.maxValue) }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                    .padding(8.dp).verticalScroll(scroll)
            ) {
                Text(log.ifEmpty { listOf("No log entries yet.") }.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Modifier.height4() = this.then(Modifier.padding(top = 4.dp))
