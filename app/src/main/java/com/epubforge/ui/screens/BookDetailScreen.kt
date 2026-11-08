package com.epubforge.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.epubforge.data.BookFormats
import com.epubforge.data.LocalBooks
import com.epubforge.data.Story
import com.epubforge.reader.ReaderActivity
import com.epubforge.ui.BookActions
import com.epubforge.ui.Fmt
import com.epubforge.ui.StoryCover
import com.epubforge.ui.app
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Details page for an imported ebook file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(story: Story, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val chapter by remember(story.id) { app.db.chapters().observe(story.id) }.collectAsState(initial = emptyList())
    val history by remember(story.id) { app.db.history().observeFor(story.id) }.collectAsState(initial = null)
    val book = chapter.firstOrNull { it.url == LocalBooks.BOOK_CHAPTER }
    val file = app.storage.bookFile(story)
    val present = file?.isFile == true
    var overflow by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            error = try { app.books.import(uri, intoStory = story); null } catch (e: Exception) { e.message }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(overflow, { overflow = false }) {
                            if (present) DropdownMenuItem(text = { Text("Share file") }, onClick = {
                                overflow = false
                                BookActions.shareFile(context, file!!, BookFormats.mime(file.name), story.title)
                            })
                            DropdownMenuItem(text = { Text(if (present) "Replace file" else "Choose file") }, onClick = {
                                overflow = false; attach.launch(BookFormats.mimeTypes)
                            })
                            DropdownMenuItem(text = { Text("Remove from library") }, onClick = { overflow = false; confirmRemove = true })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        floatingActionButton = {
            if (present) ExtendedFloatingActionButton(
                onClick = { context.startActivity(ReaderActivity.intent(context, story.id)) },
                icon = { Icon(Icons.Filled.PlayArrow, null) },
                text = { Text(if ((book?.progress ?: 0f) > 0.001f && book?.read != true) "Resume" else if (book?.read == true) "Read again" else "Start") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = padding.calculateBottomPadding() + 88.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.matchParentSize()) {
                    val backdrop = Modifier.matchParentSize().alpha(0.35f)
                    StoryCover(story, if (Build.VERSION.SDK_INT >= 31) backdrop.blur(18.dp) else backdrop.alpha(0.2f), RoundedCornerShape(0.dp))
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
                }
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 12.dp)) {
                    StoryCover(story, Modifier.width(100.dp).height(150.dp), RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.align(Alignment.Bottom)) {
                        Text(story.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        if (story.author.isNotBlank()) MetaLine(Icons.Outlined.Person, story.author)
                        MetaLine(Icons.Outlined.Description, story.site.ifBlank { "Ebook" } + if (present) " • " + Fmt.size(file!!.length()) else "")
                        MetaLine(Icons.Outlined.Schedule, "Added ${Fmt.date(story.addedAt)}")
                    }
                }
            }

            if (!present) {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("The book's file isn't on this device", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "It was imported on another device; your reading progress came across with sync. " +
                                "Choose the same file here to read it.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                        Button(onClick = { attach.launch(BookFormats.mimeTypes) }) { Text("Choose file") }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }

            val progress = book?.progress ?: 0f
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            book?.read == true -> "Finished"
                            progress > 0.001f -> "${(progress * 100).roundToInt()}% read"
                            else -> "Not started"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { if (book?.read == true) 1f else progress }, modifier = Modifier.fillMaxWidth())
                history?.let { h ->
                    Text(
                        "Last read ${Fmt.relativeDay(h.readAt).lowercase()}" + if (h.chapterTitle.isNotBlank() && h.chapterTitle != story.title) " • ${h.chapterTitle}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (story.description.isNotBlank()) Description(story.description)
            if (story.tags.isNotBlank()) Tags(story.tags)
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove from library?") },
            text = { Text("The book's copy inside EpubForge is deleted. The original file you imported isn't touched.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch { app.repo.removeFromLibrary(story); onBack() }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
