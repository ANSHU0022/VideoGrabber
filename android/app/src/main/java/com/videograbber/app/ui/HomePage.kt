package com.videograbber.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videograbber.app.R
import com.videograbber.app.data.Prefs

private data class Site(val name: String, val url: String, @DrawableRes val logo: Int, val color: Color?)

private val SITES = listOf(
    Site("YouTube", "https://m.youtube.com", R.drawable.logo_youtube, Color(0xFFFF0000)),
    Site("Instagram", "https://www.instagram.com", R.drawable.logo_instagram, Color(0xFFE4405F)),
    Site("Facebook", "https://m.facebook.com/watch", R.drawable.logo_facebook, Color(0xFF0866FF)),
    Site("TikTok", "https://www.tiktok.com", R.drawable.logo_tiktok, null),
    Site("X", "https://x.com", R.drawable.logo_x, null),
    Site("Vimeo", "https://vimeo.com", R.drawable.logo_vimeo, Color(0xFF1AB7EA)),
    Site("Reddit", "https://www.reddit.com", R.drawable.logo_reddit, Color(0xFFFF4500)),
    Site("Dailymotion", "https://www.dailymotion.com", R.drawable.logo_dailymotion, null),
)

@Composable
fun HomePage(onSubmit: (String) -> Unit, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }
    val engine by Prefs.searchEngine.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF4C8DFF), Color(0xFF1F4FD6)))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Download, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(14.dp))
        Text("VideoGrabber", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
        Text("Download any video in any quality", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))

        // search
        Row(
            Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) onSubmit(q) }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (q.isEmpty()) Text("Search videos or paste a link", color = cs.onSurfaceVariant, fontSize = 16.sp)
                        inner()
                    }
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("youtube" to "YouTube", "google" to "Google", "vimeo" to "Vimeo").forEach { (id, name) ->
                FilterChip(
                    selected = engine == id, onClick = { Prefs.setSearchEngine(id) }, label = { Text(name) },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = cs.primary.copy(alpha = 0.13f), selectedLabelColor = cs.primary),
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = engine == id, borderColor = cs.outlineVariant, selectedBorderColor = Color.Transparent),
                )
            }
        }
        Spacer(Modifier.height(32.dp))

        // quick sites, 4 per row
        SITES.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { s ->
                    Column(
                        Modifier.width(78.dp).clip(RoundedCornerShape(14.dp)).clickable { onOpen(s.url) }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            Icon(painterResource(s.logo), s.name, tint = s.color ?: cs.onSurface, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(s.name, fontSize = 12.sp, color = cs.onSurface, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Open any video and tap the floating ⬇ button to pick a quality — up to 4K.",
            color = cs.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(32.dp))
    }
}
