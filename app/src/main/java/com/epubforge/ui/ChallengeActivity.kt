package com.epubforge.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epubforge.engine.Notifications
import com.epubforge.ui.theme.EpubForgeTheme
import kotlinx.coroutines.delay

/** Shows a site's "are you human?" page in a real browser view so the user can pass it. */
class ChallengeActivity : ComponentActivity() {
    companion object {
        fun intent(context: Context) = Intent(context, ChallengeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private val verificationTitles = listOf(
            "just a moment", "making sure you're not a bot", "attention required", "ddos-guard",
            "verifying you are human", "security check", "checking your browser",
        )

        fun looksLikeChallenge(title: String?) =
            title.isNullOrBlank() || verificationTitles.any { title.lowercase().contains(it) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Notifications.cancelChallenge(this)
        val coordinator = app.challenges
        setContent {
            val settings by app.settings.flow.collectAsStateWithLifecycle()
            val challenge by coordinator.pending.collectAsStateWithLifecycle()
            var progress by remember { mutableStateOf(0f) }
            var pageTitle by remember { mutableStateOf("") }
            var webView by remember { mutableStateOf<WebView?>(null) }

            LaunchedEffect(challenge) { if (challenge == null) finish() }

            // Once the page is no longer a verification page, the cookies are set: resume.
            LaunchedEffect(webView) {
                val wv = webView ?: return@LaunchedEffect
                var stableChecks = 0
                while (true) {
                    delay(1500)
                    val title = wv.title
                    val loaded = progress >= 1f
                    if (loaded && !looksLikeChallenge(title)) {
                        stableChecks++
                        if (stableChecks >= 2) {
                            CookieManager.getInstance().flush()
                            coordinator.resolve(true)
                            finish()
                            break
                        }
                    } else {
                        stableChecks = 0
                    }
                }
            }

            BackHandler {
                coordinator.resolve(false)
                finish()
            }

            EpubForgeTheme(settings.theme, settings.dynamicColor, settings.pureBlack) {
                Scaffold(topBar = {
                    Column {
                        TopAppBar(
                            navigationIcon = {
                                IconButton(onClick = { coordinator.resolve(false); finish() }) { Icon(Icons.Filled.Close, "Cancel") }
                            },
                            title = {
                                Column {
                                    Text("Verify you're human", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        challenge?.host ?: "", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            actions = {
                                TextButton(onClick = {
                                    CookieManager.getInstance().flush()
                                    coordinator.resolve(true)
                                    finish()
                                }) { Text("Done") }
                            },
                        )
                        if (progress < 1f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        Text(
                            "Complete the check below. Downloading resumes automatically once the site lets you through.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        val url = challenge?.url
                        if (url != null) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        this.settings.javaScriptEnabled = true
                                        this.settings.domStorageEnabled = true
                                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { progress = 0.1f }
                                            override fun onPageFinished(view: WebView, url: String?) {
                                                progress = 1f
                                                pageTitle = view.title ?: ""
                                            }
                                        }
                                        loadUrl(url)
                                        webView = this
                                    }
                                },
                                onRelease = { it.destroy() },
                            )
                        }
                    }
                }
            }
        }
    }
}
