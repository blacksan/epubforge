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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.epubforge.data.LibraryItem
import com.epubforge.sync.SyncState
import com.epubforge.ui.EmptyState
import com.epubforge.ui.Fmt
import com.epubforge.ui.StoryCover
import com.epubforge.ui.app
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenStory: (String) -> Unit, onAdd: () -> Unit, onImport: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val items by remember { app.db.stories().observeLibrary() }.collectAsStateWithLifecycle(initialValue = null)
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val syncState by app.sync.state.collectAsStateWithLifecycle()
    val updateState by app.updates.progress.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    val shown = remember(items, query, settings.librarySort) {
        val list = (items ?: emptyList()).filter {
            query.isBlank() || it.story.title.contains(query, true) || it.story.author.contains(query, true)
        }
        when (settings.librarySort) {
            1 -> list.sortedBy { it.story.title.lowercase() }
            2 -> list.sortedByDescending { it.story.addedAt }
            3 -> list.sortedByDescending { it.unread }
            else -> list.sortedByDescending { maxOf(it.lastReadAt, it.story.addedAt) }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (searching) IconButton(onClick = { searching = false; query = "" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search")
                    }
                },
                title = {
                    if (searching) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        TextField(
                            value = query, onValueChange = { query = it }, placeholder = { Text("Search…") }, singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    } else {
                        Column {
                            Text("Library")
                            val n = items?.size ?: 0
                            if (updateState.running) Text(
                                "Checking ${updateState.current} (${updateState.done + 1}/${updateState.total})",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            ) else if (n > 0) Text(
                                "$n ${if (n == 1) "story" else "stories"}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (searching && query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                    if (!searching) {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
                        IconButton(onClick = onImport) { Icon(Icons.Filled.FileOpen, "Import ebook") }
                        IconButton(onClick = { app.scope.launch { app.updates.check() } }, enabled = !updateState.running) {
                            Icon(Icons.Filled.Refresh, "Update library")
                        }
                    }
                    IconButton(onClick = { scope.launch { app.settings.update { it.copy(libraryList = !it.libraryList) } } }) {
                        Icon(if (settings.libraryList) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList, "Display mode")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                        DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf("Last read", "Title", "Date added", "Unread count").forEachIndexed { i, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    trailingIcon = { if (settings.librarySort == i) Icon(Icons.Filled.Check, null) },
                                    onClick = { sortMenu = false; scope.launch { app.settings.update { it.copy(librarySort = i) } } },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add story") })
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = syncState is SyncState.Running || updateState.running,
            onRefresh = {
                // Like Mihon: pull down to check every story for new chapters (and sync).
                app.scope.launch {
                    if (app.sync.enabled) app.sync.syncNow()
                    app.updates.check()
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val list = items
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(
                    Icons.Outlined.CollectionsBookmark,
                    "Your library is empty",
                    "Tap Add story and paste a link from Royal Road or any site WebToEpub supports, then download chapters " +
                        "and read them offline. Or import your own EPUB, MOBI, AZW3, FB2, CBZ or PDF files.",
                )
                shown.isEmpty() -> EmptyState(Icons.Filled.Search, "No matches", "Nothing in your library matches “$query”.")
                settings.libraryList -> LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(shown, key = { it.story.id }) { item ->
                        ListItem(
                            leadingContent = { StoryCover(item.story, Modifier.width(44.dp).aspectRatio(2f / 3f)) },
                            headlineContent = { Text(item.story.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    listOf(
                                        item.story.author,
                                        if (item.unread > 0) "${item.unread} unread" else "${item.total} chapters",
                                        if (item.lastReadAt > 0) "read ${Fmt.relativeDay(item.lastReadAt).lowercase()}" else "",
                                    ).filter { it.isNotBlank() }.joinToString(" • "),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = { Badges(item) },
                            modifier = Modifier.clickable { onOpenStory(item.story.id) },
                        )
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.story.id }) { item -> CompactGridItem(item) { onOpenStory(item.story.id) } }
                }
            }
        }
    }
}

/** Mihon's "compact grid": cover with the title over a bottom gradient and count badges. */
@Composable
private fun CompactGridItem(item: LibraryItem, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clickable(onClick = onClick)) {
        StoryCover(item.story, Modifier.fillMaxSize(), shape)
        Box(
            Modifier.fillMaxWidth().height(80.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))), shape)
        )
        Text(
            item.story.title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
        Box(Modifier.align(Alignment.TopStart).padding(4.dp)) { Badges(item) }
    }
}

/** Unread (primary) and downloaded (tertiary) counts, as on Mihon's covers. */
@Composable
private fun Badges(item: LibraryItem) {
    Row(Modifier.background(Color.Transparent, RoundedCornerShape(4.dp))) {
        if (item.downloaded > 0) Badge("${item.downloaded}", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = if (item.unread > 0) 0.dp else 4.dp, bottomEnd = if (item.unread > 0) 0.dp else 4.dp))
        if (item.unread > 0) Badge("${item.unread}", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp, topStart = if (item.downloaded > 0) 0.dp else 4.dp, bottomStart = if (item.downloaded > 0) 0.dp else 4.dp))
    }
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color, shape: RoundedCornerShape) {
    Text(
        text, color = fg, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.background(bg, shape).padding(horizontal = 5.dp, vertical = 2.dp),
    )
}
