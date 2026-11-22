package com.videograbber.app.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.videograbber.app.ui.theme.LocalExtra

@Composable
fun Tag(text: String, accent: Boolean = false, danger: Boolean = false, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when {
        danger -> cs.error.copy(alpha = 0.12f) to cs.error
        accent -> cs.primary.copy(alpha = 0.13f) to cs.primary
        else -> LocalExtra.current.tagBg to cs.onSurfaceVariant
    }
    Text(
        text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private val frameCache = android.util.LruCache<String, Bitmap>(80)

/**
 * Thumbnail with fallbacks: the site's image → a frame from the downloaded file itself (localUri) → an icon.
 */
@Composable
fun Thumb(url: String?, audio: Boolean, modifier: Modifier = Modifier, corner: Int = 8, iconSize: Int = 22, localUri: String? = null) {
    var failed by remember(url) { mutableStateOf(false) }
    val ctx = LocalContext.current
    var frame by remember(localUri) { mutableStateOf(localUri?.let { frameCache.get(it) }) }
    val needFrame = (url.isNullOrBlank() || failed) && localUri != null && frame == null
    LaunchedEffect(needFrame, localUri) {
        if (!needFrame || localUri == null) return@LaunchedEffect
        frame = withContext(Dispatchers.IO) {
            // A frame from 20% into the video (skips intros); Android's own thumbnail as fallback.
            runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, Uri.parse(localUri))
                    val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    r.getScaledFrameAtTime(ms * 200, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                } finally { r.release() }
            }.getOrNull() ?: runCatching { ctx.contentResolver.loadThumbnail(Uri.parse(localUri), Size(480, 270), null) }.getOrNull()
        }?.also { frameCache.put(localUri, it) }
    }
    Box(modifier.clip(RoundedCornerShape(corner.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val f = frame
        if ((url.isNullOrBlank() || failed) && f != null) {
            Image(f.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (url.isNullOrBlank() || failed) {
            Icon(if (audio) Icons.Rounded.MusicNote else Icons.Rounded.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(iconSize.dp))
        } else {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, onError = { failed = true }, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun DurationBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xB8000000)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
