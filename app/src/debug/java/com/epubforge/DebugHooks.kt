package com.epubforge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only: lets the emulator test drive backups without the system file picker.
 *   adb shell am broadcast -n com.epubforge/.DebugHooks -a com.epubforge.debug.BACKUP --ez chapters true
 *   adb shell am broadcast -n com.epubforge/.DebugHooks -a com.epubforge.debug.RESTORE
 *   adb shell am broadcast -n com.epubforge/.DebugHooks -a com.epubforge.debug.IMPORT --es file book.epub
 * Uses files/e2e-backup.zip (and files/<file> for imports). Results are logged with tag "E2E".
 */
class DebugHooks : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        val file = File(app.filesDir, "e2e-backup.zip")
        val pending = goAsync()
        app.scope.launch {
            try {
                when (intent.action) {
                    "com.epubforge.debug.BACKUP" -> {
                        val n = file.outputStream().use { app.backups.create(it, intent.getBooleanExtra("chapters", true)) }
                        Log.i("E2E", "BACKUP OK files=$n size=${file.length()}")
                    }
                    "com.epubforge.debug.RESTORE" -> {
                        val r = file.inputStream().use { app.backups.restore(it) }
                        Log.i("E2E", "RESTORE OK stories=${r.stories} chapters=${r.chapterFiles}")
                    }
                    "com.epubforge.debug.IMPORT" -> {
                        val f = File(app.filesDir, intent.getStringExtra("file") ?: "import.epub")
                        val story = app.books.import(android.net.Uri.fromFile(f))
                        Log.i("E2E", "IMPORT OK id=${story.id} title=${story.title} author=${story.author} site=${story.site}")
                    }
                    "com.epubforge.debug.SYNC" -> {
                        Log.i("E2E", "SYNC " + app.sync.syncNow().fold({ "OK" }, { "FAILED " + it.message }))
                    }
                }
            } catch (e: Exception) {
                Log.e("E2E", "${intent.action} FAILED", e)
            } finally {
                pending.finish()
            }
        }
    }
}
