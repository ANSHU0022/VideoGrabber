package com.videograbber.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videograbber.app.browser.BrowserController
import com.videograbber.app.data.Prefs
import com.videograbber.app.ui.BrowserScreen
import com.videograbber.app.ui.DownloadsScreen
import com.videograbber.app.ui.PlayItem
import com.videograbber.app.ui.PlayerScreen
import com.videograbber.app.ui.SettingsScreen
import com.videograbber.app.ui.theme.VideoGrabberTheme
import com.videograbber.app.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    private lateinit var controller: BrowserController
    private val screen = mutableStateOf("browser")
    private val playing = mutableStateOf<PlayItem?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        controller = BrowserController(this)
        handleIntent(intent)

        setContent {
            val themePref by Prefs.theme.collectAsStateWithLifecycle()
            val dark = isDarkTheme(themePref)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            VideoGrabberTheme(dark) {
                val current by screen
                Box(Modifier.fillMaxSize()) {
                    BrowserScreen(
                        c = controller,
                        covered = current != "browser",
                        onOpenDownloads = { screen.value = "downloads" },
                        onOpenSettings = { screen.value = "settings" },
                    )
                    AnimatedVisibility(
                        current == "downloads",
                        enter = slideInHorizontally { it / 3 } + fadeIn(), exit = slideOutHorizontally { it / 3 } + fadeOut(),
                    ) {
                        DownloadsScreen(
                            onBack = { screen.value = "browser" },
                            onOpenPage = { url -> controller.newTab(url); screen.value = "browser" },
                            onPlay = { playing.value = it },
                        )
                    }
                    AnimatedVisibility(
                        current == "settings",
                        enter = slideInHorizontally { it / 3 } + fadeIn(), exit = slideOutHorizontally { it / 3 } + fadeOut(),
                    ) {
                        SettingsScreen(onBack = { screen.value = "browser" })
                    }
                }
                // Registered after the browser so it wins while Downloads/Settings is on top.
                BackHandler(enabled = current != "browser") { screen.value = "browser" }
                // The player sits on top of everything (and its Back handler is registered last, so it wins).
                playing.value?.let { item -> PlayerScreen(item) { playing.value = null } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> BrowserController.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { url ->
                val t = controller.newTab(url)
                controller.autoOpenSheetFor = t.id
                screen.value = "browser"
            }
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { it.startsWith("http") }?.let { controller.newTab(it); screen.value = "browser" }
            ACTION_SHOW_DOWNLOADS -> screen.value = "downloads"
        }
    }

    override fun onResume() {
        super.onResume()
        controller.syncPlayback(appVisible = true)
    }

    override fun onPause() {
        super.onPause()
        controller.syncPlayback(appVisible = false)
        controller.persist()
        CookieManager.getInstance().flush()
    }

    companion object {
        const val ACTION_SHOW_DOWNLOADS = "com.videograbber.app.SHOW_DOWNLOADS"
    }
}
