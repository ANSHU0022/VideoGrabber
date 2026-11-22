package com.videograbber.app.ui

import android.app.Activity
import android.net.Uri
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

data class PlayItem(val uri: String, val mime: String, val title: String)

/** Full-screen player for downloaded files (MP4, MKV, WebM, MP3, M4A, Opus, FLAC). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(item: PlayItem, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember(item.uri) { mutableStateOf(false) }
    var barVisible by remember { mutableStateOf(true) }
    val player = remember(item.uri) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(item.uri)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = true }
        }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        val ctl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> barVisible = v == View.VISIBLE })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        AnimatedVisibility(barVisible || error, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close", tint = Color.White) }
                Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { player.pause(); openFile(ctx, item.uri, item.mime) }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open with another app", tint = Color.White)
                }
            }
        }
        if (error) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp).background(Color(0xE6202024), RoundedCornerShape(20.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = Color.White, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(10.dp))
                Text("Can't play this file here", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("Your phone can't decode this video format in the app.", color = Color(0xFFB3B3BB), fontSize = 13.5.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { openFile(ctx, item.uri, item.mime) }) { Text("Open with another app") }
            }
        }
    }
}
