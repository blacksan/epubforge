package com.epubforge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.epubforge.App
import com.epubforge.data.Story
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

val Context.app: App get() = applicationContext as App

@Composable
fun StoryCover(story: Story, modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(6.dp)) {
    val context = LocalContext.current
    val file = context.app.storage.coverFile(story.id)
    Box(modifier.clip(shape).background(placeholderColor(story.title))) {
        Text(
            text = initials(story.title),
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center),
        )
        if (file.exists()) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(file)
                    .memoryCacheKey("${story.id}-${file.lastModified()}")
                    .diskCacheKey("${story.id}-${file.lastModified()}")
                    .crossfade(true).build(),
                contentDescription = story.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun initials(title: String): String =
    title.split(' ', '-', ':').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { "?" }

private val placeholderColors = listOf(
    0xFF3949AB, 0xFF00897B, 0xFF6D4C41, 0xFF8E24AA, 0xFFC62828, 0xFF2E7D32, 0xFF283593, 0xFFEF6C00,
)

fun placeholderColor(seed: String) = Color(placeholderColors[abs(seed.hashCode()) % placeholderColors.size])

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

object Fmt {
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1000} KB"
        else -> "$bytes B"
    }

    fun date(ts: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ts))
    fun time(ts: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))
    fun dateTime(ts: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))

    fun relativeDay(ts: Long): String {
        val day = 86_400_000L
        val tz = java.util.TimeZone.getDefault()
        val today = (System.currentTimeMillis() + tz.getOffset(System.currentTimeMillis())) / day
        val that = (ts + tz.getOffset(ts)) / day
        return when (today - that) {
            0L -> "Today"
            1L -> "Yesterday"
            in 2..6 -> "${today - that} days ago"
            else -> date(ts)
        }
    }
}

object BookActions {
    fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    fun shareFile(context: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun safeFileName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120).ifBlank { "story" }
}
