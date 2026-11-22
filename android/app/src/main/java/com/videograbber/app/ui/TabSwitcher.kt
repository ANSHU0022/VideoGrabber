package com.videograbber.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videograbber.app.browser.BrowserController
import com.videograbber.app.browser.Tab

@Composable
fun TabSwitcher(c: BrowserController, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${c.tabs.size} ${if (c.tabs.size == 1) "tab" else "tabs"}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { c.closeAll(); onClose() }) { Text("Close all", color = cs.onSurfaceVariant) }
                    TextButton(onClick = onClose) { Text("Done", fontWeight = FontWeight.Bold) }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(c.tabs, key = { it.id }) { t -> TabCard(t, t.id == c.activeId, onOpen = { c.select(t); onClose() }, onCloseTab = { c.close(t) }) }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { c.newTab(); onClose() }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New tab") },
                shape = RoundedCornerShape(18.dp), containerColor = cs.primary, contentColor = cs.onPrimary,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun TabCard(t: Tab, active: Boolean, onOpen: () -> Unit, onCloseTab: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        onClick = onOpen, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        border = if (active) BorderStroke(2.dp, cs.primary) else BorderStroke(1.dp, cs.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = if (active) 4.dp else 0.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val fav = t.favicon
            if (fav != null) Image(fav.asImageBitmap(), null, Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)))
            else Icon(if (t.isHome) Icons.Rounded.Search else Icons.Rounded.Public, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(t.title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = onCloseTab, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Close, "Close tab", Modifier.size(17.dp), tint = cs.onSurfaceVariant) }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(0.78f).padding(start = 6.dp, end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh)) {
            val p = t.preview
            when {
                t.isHome -> Icon(Icons.Rounded.Download, null, tint = cs.primary.copy(alpha = 0.7f), modifier = Modifier.size(40.dp).align(Alignment.Center))
                p != null -> Image(p.asImageBitmap(), null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                else -> Text(Tab.hostOf(t.url), color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center).padding(8.dp), maxLines = 2)
            }
            if (t.info != null) {
                Row(
                    Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(cs.primary).padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Download, null, tint = cs.onPrimary, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(t.info?.qualities?.firstOrNull()?.height?.let { qShort(it) } ?: "Video", color = cs.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
