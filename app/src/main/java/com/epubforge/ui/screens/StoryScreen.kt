package com.epubforge.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.epubforge.data.Chapter
import com.epubforge.data.Story
import com.epubforge.engine.DownloadState
import com.epubforge.reader.ReaderActivity
import com.epubforge.ui.BookActions
import com.epubforge.ui.Fmt
import com.epubforge.ui.StoryCover
import com.epubforge.ui.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class ChapterFilter { All, Unread, Downloaded }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StoryScreen(storyId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val story by remember(storyId) { app.db.stories().observe(storyId) }.collectAsState(initial = null)
    story?.takeIf { it.isLocalBook }?.let { BookDetailScreen(it, onBack); return }
    val chapters by remember(storyId) { app.db.chapters().observe(storyId) }.collectAsState(initial = null)
    val dlStates by app.downloads.states.collectAsState()
    val history by remember(storyId) { app.db.history().observeFor(storyId) }.collectAsState(initial = null)
    val snackbar = remember { SnackbarHostState() }

    var refreshing by remember { mutableStateOf(false) }
    var descending by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(ChapterFilter.All) }
    val selected = remember { mutableStateListOf<String>() }
    var downloadMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var pendingOpen by remember { mutableStateOf<String?>(null) }

    val s = story
    val all = chapters ?: emptyList()
    val visible = remember(all, filter, descending) {
        val list = all.filter { !it.removed || it.downloaded }.filter {
            when (filter) {
                ChapterFilter.All -> true
                ChapterFilter.Unread -> !it.read
                ChapterFilter.Downloaded -> it.downloaded
            }
        }
        if (descending) list.reversed() else list
    }
    val active = all.filter { !it.removed }

    fun state(c: Chapter): DownloadState? = dlStates[app.downloads.key(storyId, c.url)]

    fun download(list: List<Chapter>, front: Boolean = false) {
        val todo = list.filter { !it.downloaded }
        if (todo.isEmpty()) {
            scope.launch { snackbar.showSnackbar("Already downloaded") }
            return
        }
        scope.launch {
            app.downloads.enqueue(storyId, todo.map { it.url }, front)
            snackbar.showSnackbar("Downloading ${todo.size} chapter${if (todo.size == 1) "" else "s"}")
        }
    }

    fun open(c: Chapter) {
        if (c.downloaded) {
            context.startActivity(ReaderActivity.intent(context, storyId, c.url))
        } else {
            pendingOpen = c.url
            download(listOf(c), front = true)
        }
    }

    // Open a chapter as soon as its download finishes (tapping an undownloaded chapter).
    LaunchedEffect(all, pendingOpen) {
        val p = pendingOpen ?: return@LaunchedEffect
        val c = all.firstOrNull { it.url == p } ?: return@LaunchedEffect
        if (c.downloaded) {
            pendingOpen = null
            context.startActivity(ReaderActivity.intent(context, storyId, c.url))
        } else if (state(c)?.status == DownloadState.Status.Error) {
            pendingOpen = null
        }
    }

    BackHandler(enabled = selected.isNotEmpty()) { selected.clear() }

    val resumeChapter = remember(all, history) {
        history?.chapterUrl?.let { u -> all.firstOrNull { it.url == u && !it.read } }
            ?: all.firstOrNull { !it.read && !it.removed }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { selected.clear() }) { Icon(Icons.Filled.Close, "Clear selection") } },
                    title = { Text("${selected.size}") },
                    actions = {
                        IconButton(onClick = {
                            val idx = selected.map { u -> visible.indexOfFirst { it.url == u } }.filter { it >= 0 }
                            if (idx.isNotEmpty()) {
                                val range = visible.subList(idx.min(), idx.max() + 1).map { it.url }
                                range.filter { it !in selected }.forEach { selected += it }
                            }
                        }) { Icon(Icons.Outlined.UnfoldMore, "Select range") }
                        IconButton(onClick = { visible.map { it.url }.filter { it !in selected }.forEach { selected += it } }) {
                            Icon(Icons.Filled.SelectAll, "Select all")
                        }
                        IconButton(onClick = {
                            val now = visible.map { it.url }.filter { it !in selected }
                            selected.clear(); selected.addAll(now)
                        }) { Icon(Icons.Filled.FlipToBack, "Invert selection") }
                    },
                )
            } else {
                TopAppBar(
                    title = {},
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        Box {
                            IconButton(onClick = { downloadMenu = true }) { Icon(Icons.Outlined.FileDownload, "Download") }
                            DropdownMenu(downloadMenu, onDismissRequest = { downloadMenu = false }) {
                                val unread = active.filter { !it.read && !it.downloaded }
                                listOf(1, 5, 10, 25).forEach { n ->
                                    DropdownMenuItem(
                                        text = { Text(if (n == 1) "Next chapter" else "Next $n chapters") },
                                        onClick = { downloadMenu = false; download(unread.take(n)) },
                                    )
                                }
                                DropdownMenuItem(text = { Text("Unread") }, onClick = { downloadMenu = false; download(active.filter { !it.read }) })
                                DropdownMenuItem(text = { Text("All") }, onClick = { downloadMenu = false; download(active) })
                                DropdownMenuItem(text = { Text("Custom range…") }, onClick = { downloadMenu = false; dialog = "range" })
                            }
                        }
                        Box {
                            IconButton(onClick = { dialog = "filter" }) { Icon(Icons.Filled.FilterList, "Filter and sort") }
                        }
                        Box {
                            IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(overflow, onDismissRequest = { overflow = false }) {
                                DropdownMenuItem(text = { Text("Refresh chapter list") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) }, onClick = {
                                    overflow = false
                                    refreshing = true
                                    scope.launch {
                                        val found = app.updates.check(listOf(storyId))
                                        refreshing = false
                                        snackbar.showSnackbar(if (found.isEmpty()) "No new chapters" else "${found.sumOf { it.second }} new chapters")
                                    }
                                })
                                DropdownMenuItem(text = { Text("Export EPUB") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) }, onClick = {
                                    overflow = false; dialog = "export"
                                })
                                DropdownMenuItem(text = { Text("Download settings") }, leadingIcon = { Icon(Icons.Filled.Settings, null) }, onClick = {
                                    overflow = false; dialog = "settings"
                                })
                                DropdownMenuItem(text = { Text("Open in browser") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }, onClick = {
                                    overflow = false; s?.let { BookActions.openUrl(context, it.url) }
                                })
                                DropdownMenuItem(text = { Text("Delete all downloads") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = {
                                    overflow = false; dialog = "deleteDownloads"
                                })
                                DropdownMenuItem(text = { Text("Remove from library") }, leadingIcon = { Icon(Icons.Filled.Close, null) }, onClick = {
                                    overflow = false; dialog = "remove"
                                })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(selected.isNotEmpty()) {
                val sel = all.filter { it.url in selected }
                BottomAppBar {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        SelectionAction(Icons.Filled.Download, "Download") { download(sel); selected.clear() }
                        SelectionAction(Icons.Filled.Delete, "Delete") {
                            scope.launch { app.repo.deleteDownloads(storyId, sel.filter { it.downloaded }.map { it.url }) }
                            selected.clear()
                        }
                        SelectionAction(Icons.Filled.DoneAll, "Mark read") {
                            scope.launch { app.repo.setRead(storyId, sel.map { it.url }, true) }
                            selected.clear()
                        }
                        SelectionAction(Icons.Filled.RemoveDone, "Mark unread") {
                            scope.launch { app.repo.setRead(storyId, sel.map { it.url }, false) }
                            selected.clear()
                        }
                        if (sel.size == 1) SelectionAction(Icons.Filled.ArrowDownward, "Read before") {
                            scope.launch { app.repo.markPreviousRead(storyId, sel[0].position) }
                            selected.clear()
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (selected.isEmpty() && resumeChapter != null) {
                ExtendedFloatingActionButton(
                    onClick = { open(resumeChapter) },
                    icon = { Icon(Icons.Filled.PlayArrow, null) },
                    text = { Text(if (all.any { it.read } || (history != null)) "Resume" else "Start") },
                )
            }
        },
    ) { padding ->
        if (s == null) return@Scaffold
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    val found = app.updates.check(listOf(storyId))
                    refreshing = false
                    snackbar.showSnackbar(if (found.isEmpty()) "No new chapters" else "${found.sumOf { it.second }} new chapters")
                }
            },
            modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item { Header(s, active.size, all.count { it.downloaded }, padding.calculateTopPadding()) }
                if (s.description.isNotBlank()) item { Description(s.description) }
                if (s.tags.isNotBlank()) item { Tags(s.tags) }
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { dialog = "filter" }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (active.size == 1) "1 chapter" else "${active.size} chapters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (filter != ChapterFilter.All) Text(
                            filter.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Icon(Icons.AutoMirrored.Filled.Sort, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(visible, key = { it.url }) { c ->
                    ChapterRow(
                        chapter = c,
                        state = state(c),
                        selected = c.url in selected,
                        selecting = selected.isNotEmpty(),
                        onClick = {
                            if (selected.isNotEmpty()) {
                                if (c.url in selected) selected.remove(c.url) else selected += c.url
                            } else open(c)
                        },
                        onLongClick = { if (c.url in selected) selected.remove(c.url) else selected += c.url },
                        onDownload = { download(listOf(c)) },
                        onCancel = { scope.launch { app.downloads.cancel(storyId, c.url) } },
                        onRetry = { app.downloads.retryFailed(storyId) },
                        onDelete = { scope.launch { app.repo.deleteDownloads(storyId, listOf(c.url)) } },
                    )
                }
            }
        }
    }

    when (dialog) {
        "range" -> RangeDialog(active.size, onDismiss = { dialog = null }) { from, to ->
            dialog = null
            download(active.filter { it.position + 1 in from..to })
        }
        "filter" -> FilterSheet(filter, descending, onDismiss = { dialog = null }, onFilter = { filter = it }, onSort = { descending = it })
        "export" -> if (s != null) ExportDialog(s, all, onDismiss = { dialog = null })
        "settings" -> if (s != null) StoryDownloadSettings(s, onDismiss = { dialog = null })
        "deleteDownloads" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete all downloads?") },
            text = { Text("Downloaded chapters of this story will be removed from the device. Reading progress is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch { app.repo.deleteDownloads(storyId, all.filter { it.downloaded }.map { it.url }) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        "remove" -> if (s != null) AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Remove from library?") },
            text = { Text("“${s.title}”, its downloaded chapters and reading progress will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch { app.repo.removeFromLibrary(s); onBack() }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SelectionAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Header(story: Story, total: Int, downloaded: Int, topPadding: Dp) {
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize()) {
            val backdrop = Modifier.matchParentSize().alpha(0.35f)
            StoryCover(story, if (Build.VERSION.SDK_INT >= 31) backdrop.blur(18.dp) else backdrop.alpha(0.2f), RoundedCornerShape(0.dp))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = topPadding + 8.dp, bottom = 12.dp)) {
            StoryCover(story, Modifier.width(100.dp).height(150.dp), RoundedCornerShape(8.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.align(Alignment.Bottom)) {
                Text(story.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                if (story.author.isNotBlank()) MetaLine(Icons.Outlined.Person, story.author)
                MetaLine(Icons.Outlined.Public, story.site.ifBlank { "Unknown site" })
                MetaLine(Icons.Outlined.ArrowCircleDown, "$downloaded of $total downloaded")
            }
        }
    }
}

@Composable
internal fun MetaLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun Description(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 8.dp).animateContentSize()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
        Text(
            if (expanded) "Show less" else "Show more",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Tags(tags: String) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(16).forEach { t ->
            SuggestionChip(onClick = {}, label = { Text(t, style = MaterialTheme.typography.labelSmall) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    chapter: Chapter,
    state: DownloadState?,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val dim = chapter.read && !selected
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(if (dim) 0.45f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!chapter.read) {
                    Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(6.dp))
                }
                Text(chapter.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                buildString {
                    append("Chapter ${chapter.position + 1}")
                    if (chapter.removed) append(" • removed from site")
                    if (!chapter.read && chapter.progress in 0.01f..0.99f) append(" • ${(chapter.progress * 100).toInt()}% read")
                    if (state?.status == DownloadState.Status.Error) append(" • download failed")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state?.status == DownloadState.Status.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (!selecting) Box {
            when {
                chapter.downloaded -> IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.CheckCircle, "Downloaded", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state?.status == DownloadState.Status.Downloading -> IconButton(onClick = { menu = true }) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                }
                state?.status == DownloadState.Status.Queued -> IconButton(onClick = { menu = true }) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.outline)
                        Icon(Icons.Filled.ArrowDownward, "Queued", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                }
                state?.status == DownloadState.Status.Error -> IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.Error, "Download failed", tint = MaterialTheme.colorScheme.error)
                }
                else -> IconButton(onClick = onDownload, modifier = Modifier.semantics { contentDescription = "Download chapter" }) {
                    Icon(Icons.Outlined.ArrowCircleDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                when {
                    chapter.downloaded -> DropdownMenuItem(text = { Text("Delete download") }, onClick = { menu = false; onDelete() })
                    state?.status == DownloadState.Status.Error -> {
                        DropdownMenuItem(text = { Text("Retry") }, onClick = { menu = false; onRetry() })
                        DropdownMenuItem(text = { Text("Cancel") }, onClick = { menu = false; onCancel() })
                    }
                    else -> DropdownMenuItem(text = { Text("Cancel download") }, onClick = { menu = false; onCancel() })
                }
            }
        }
    }
}

@Composable
private fun RangeDialog(total: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf(total.toString()) }
    val a = from.toIntOrNull()
    val b = to.toIntOrNull()
    val valid = a != null && b != null && a in 1..total && b in a..total
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download a range") },
        text = {
            Column {
                Text("Chapters 1 to $total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        from, { from = it.filter(Char::isDigit) }, label = { Text("From") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        to, { to = it.filter(Char::isDigit) }, label = { Text("To") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(a!!, b!!) }, enabled = valid) { Text(if (valid) "Download ${b!! - a!! + 1}" else "Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    filter: ChapterFilter,
    descending: Boolean,
    onDismiss: () -> Unit,
    onFilter: (ChapterFilter) -> Unit,
    onSort: (Boolean) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Show", style = MaterialTheme.typography.titleSmall)
            listOf(ChapterFilter.All to "All chapters", ChapterFilter.Unread to "Unread", ChapterFilter.Downloaded to "Downloaded").forEach { (f, label) ->
                Row(Modifier.fillMaxWidth().clickable { onFilter(f) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = filter == f, onClick = { onFilter(f) })
                    Text(label)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Sort", style = MaterialTheme.typography.titleSmall)
            listOf(false to "First chapter first", true to "Newest chapter first").forEach { (d, label) ->
                Row(Modifier.fillMaxWidth().clickable { onSort(d) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = descending == d, onClick = { onSort(d) })
                    Icon(if (d) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label)
                }
            }
        }
    }
}

/** Export downloaded chapters as an EPUB, then save or share it. */
@Composable
private fun ExportDialog(story: Story, chapters: List<Chapter>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val downloaded = chapters.filter { it.downloaded }
    var allChapters by remember { mutableStateOf(true) }
    var from by remember { mutableStateOf(((downloaded.firstOrNull()?.position ?: 0) + 1).toString()) }
    var to by remember { mutableStateOf(((downloaded.lastOrNull()?.position ?: 0) + 1).toString()) }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val name = BookActions.safeFileName(story.title) + ".epub"

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { uri ->
        val f = result ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                    true
                } catch (e: Exception) { false }
            }
            Toast.makeText(context, if (ok) "EPUB saved" else "Could not save EPUB", Toast.LENGTH_SHORT).show()
            if (ok) onDismiss()
        }
    }

    val inRange = downloaded.filter { allChapters || (it.position + 1) in ((from.toIntOrNull() ?: 1)..(to.toIntOrNull() ?: Int.MAX_VALUE)) }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Export EPUB") },
        text = {
            Column {
                val r = result
                when {
                    downloaded.isEmpty() -> Text("Download some chapters first. Only downloaded chapters go into the EPUB.")
                    r != null -> Text("Created $name (${Fmt.size(r.length())}).")
                    working -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Building EPUB…")
                    }
                    else -> {
                        Row(Modifier.fillMaxWidth().clickable { allChapters = true }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = allChapters, onClick = { allChapters = true })
                            Text("All downloaded chapters (${downloaded.size})")
                        }
                        Row(Modifier.fillMaxWidth().clickable { allChapters = false }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = !allChapters, onClick = { allChapters = false })
                            Text("A range")
                        }
                        if (!allChapters) Row {
                            OutlinedTextField(
                                from, { from = it.filter(Char::isDigit) }, label = { Text("From") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                to, { to = it.filter(Char::isDigit) }, label = { Text("To") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("${inRange.size} chapters will be included.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            val r = result
            if (r != null) {
                Row {
                    TextButton(onClick = { BookActions.shareFile(context, r, "application/epub+zip", "Share EPUB") }) { Text("Share") }
                    TextButton(onClick = { saveLauncher.launch(name) }) { Text("Save") }
                }
            } else if (downloaded.isNotEmpty()) {
                TextButton(enabled = !working && inRange.isNotEmpty(), onClick = {
                    working = true
                    error = null
                    scope.launch {
                        try {
                            val dir = File(context.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                            val out = File(dir, name)
                            app.repo.exportEpub(story, inRange.map { it.url }.toSet(), out)
                            result = out
                        } catch (e: Exception) {
                            error = e.message ?: "Export failed"
                        } finally {
                            working = false
                        }
                    }
                }) { Text("Export") }
            }
        },
        dismissButton = { if (!working) TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryDownloadSettings(story: Story, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val siteDefault = app.settings.current.let { if (story.site.contains("royalroad")) it.royalRoadDelayMs else it.defaultDelayMs }
    var s by remember { mutableStateOf(story) }
    fun save(n: Story) { s = n; scope.launch { app.repo.updateStoryOptions(n) } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding()) {
            Text("Download settings", style = MaterialTheme.typography.titleLarge)
            Text("For “${story.title}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SettingSwitch("Download images", null, s.downloadImages) { save(s.copy(downloadImages = it)) }
            SettingSwitch("Include author's notes", "Notes and comments the writer adds to chapters", s.includeAuthorNotes) { save(s.copy(includeAuthorNotes = it)) }
            SettingSwitch("Use the site's default delay", "%.2f s between requests".format(siteDefault / 1000.0), s.delayMs < 0) {
                save(s.copy(delayMs = if (it) -1 else siteDefault))
            }
            if (s.delayMs >= 0) {
                var v by remember(s.delayMs) { mutableFloatStateOf(s.delayMs.toFloat()) }
                Row(Modifier.padding(top = 8.dp)) {
                    Text("Delay between requests", Modifier.weight(1f))
                    Text("%.2f s".format(v / 1000f), color = MaterialTheme.colorScheme.primary)
                }
                Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { save(s.copy(delayMs = (v / 250).toInt() * 250)) }, valueRange = 0f..10_000f)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Changes apply to chapters downloaded from now on. Delete and re-download a chapter to apply them to it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
