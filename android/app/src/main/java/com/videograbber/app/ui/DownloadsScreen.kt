package com.videograbber.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videograbber.app.data.DownloadTask
import com.videograbber.app.data.HistoryItem
import com.videograbber.app.data.Mode
import com.videograbber.app.data.Prefs
import com.videograbber.app.data.TaskStatus
import com.videograbber.app.download.Downloads
import com.videograbber.app.download.History
import com.videograbber.app.download.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun openFile(ctx: Context, uri: String, mime: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: Exception) { Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show() }
}

fun shareFile(ctx: Context, uri: String, mime: String) {
    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, null))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit, onOpenPage: (String) -> Unit, onPlay: (PlayItem) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val tasks by Downloads.tasks.collectAsStateWithLifecycle()
    val history by History.items.collectAsStateWithLifecycle()
    val view by Prefs.historyView.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("all") }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(emptySet<String>()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(history) {
        missing = withContext(Dispatchers.IO) { history.filterNot { MediaSaver.exists(ctx, it.uri) }.map { it.id }.toSet() }
    }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }

    val active = tasks.filter { !it.status.isFinal || it.status == TaskStatus.ERROR || it.status == TaskStatus.CANCELLED }
    val shown = history.filter {
        (filter == "all" || (filter == "video") == (it.mode == Mode.VIDEO)) &&
            (query.isBlank() || "${it.title} ${it.site} ${it.label} ${it.ext}".contains(query.trim(), ignoreCase = true))
    }
    val total = history.sumOf { it.size }

    Scaffold(
        containerColor = cs.surface,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { if (searching) { searching = false; query = "" } else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = {
                    if (searching) {
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface, fontSize = 17.sp),
                            cursorBrush = SolidColor(cs.primary), modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search downloads", color = cs.onSurfaceVariant, fontSize = 17.sp); inner() } },
                        )
                    } else Column {
                        Text("Downloads", style = MaterialTheme.typography.titleLarge)
                        if (history.isNotEmpty()) Text("${history.size} ${if (history.size == 1) "file" else "files"} · ${fmtSize(total)}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                },
                actions = {
                    if (!searching) IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search") }
                    IconButton(onClick = {
                        Prefs.setHistoryView(when (view) { "list" -> "rows"; "rows" -> "grid"; else -> "list" })
                    }) {
                        Icon(when (view) { "list" -> Icons.AutoMirrored.Rounded.ViewList; "grid" -> Icons.Rounded.GridView; else -> Icons.Rounded.ViewAgenda }, "Change view")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(14.dp)) {
                            listOf("list" to "List", "rows" to "Details", "grid" to "Cards").forEach { (v, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    leadingIcon = { Icon(when (v) { "list" -> Icons.AutoMirrored.Rounded.ViewList; "grid" -> Icons.Rounded.GridView; else -> Icons.Rounded.ViewAgenda }, null) },
                                    trailingIcon = { if (view == v) Icon(Icons.Rounded.Check, null, tint = cs.primary) },
                                    onClick = { Prefs.setHistoryView(v); menu = false },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Clear history") }, leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) }, enabled = history.isNotEmpty(),
                                onClick = { menu = false; confirmClear = true })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surface),
            )
        },
    ) { pad ->
        val cols = if (view == "grid") 2 else 1
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (view == "list") 0.dp else 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (active.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("In progress", trailing = if (tasks.any { it.status.isFinal }) "Clear" else null) { Downloads.clearFinished() } }
                items(active, key = { "t-" + it.id }, span = { GridItemSpan(maxLineSpan) }) { ActiveCard(it) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(top = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "All", "video" to "Video", "audio" to "Audio").forEach { (id, name) ->
                        FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(name) }, shape = RoundedCornerShape(10.dp))
                    }
                }
            }
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyHistory(history.isEmpty()) }
            }
            var lastDay = ""
            shown.forEach { h ->
                if (view == "rows") {
                    val d = dayLabel(h.date)
                    if (d != lastDay) {
                        lastDay = d
                        item(key = "d-$d-${h.id}", span = { GridItemSpan(maxLineSpan) }) { Text(d, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
                    }
                }
                item(key = h.id) {
                    val isMissing = h.id in missing
                    HistoryEntry(h, view, isMissing, onOpenPage, onPlay)
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear download history?") },
            text = { Text("The list is cleared. Your downloaded files stay on your phone.") },
            confirmButton = { TextButton(onClick = { History.clear(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String, trailing: String? = null, onTrailing: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (trailing != null) TextButton(onClick = onTrailing) { Text(trailing) }
    }
}

@Composable
private fun ActiveCard(t: DownloadTask) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(t.req.thumb, t.req.mode == Mode.AUDIO, Modifier.width(72.dp).aspectRatio(16f / 9f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.req.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(t.req.label)
                    Spacer(Modifier.width(6.dp))
                    val status = when (t.status) {
                        TaskStatus.QUEUED -> "Waiting…"
                        TaskStatus.DOWNLOADING -> listOf("${(t.progress * 100).toInt()}%", if (t.stage > 1 && t.req.mode == Mode.VIDEO) "audio" else "", t.speed, fmtEta(t.etaSeconds)).filter { it.isNotBlank() }.joinToString(" · ")
                        TaskStatus.PROCESSING -> if (t.req.mode == Mode.AUDIO) "Converting…" else "Finishing…"
                        TaskStatus.RETRYING -> "Reconnecting (attempt ${t.attempt})…"
                        TaskStatus.ERROR -> t.error ?: "Failed"
                        TaskStatus.CANCELLED -> "Cancelled"
                        TaskStatus.DONE -> "Done"
                    }
                    Text(status, fontSize = 12.sp, color = if (t.status == TaskStatus.ERROR) cs.error else cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!t.status.isFinal) {
                    Spacer(Modifier.height(8.dp))
                    if (t.status == TaskStatus.DOWNLOADING && t.progress > 0f)
                        LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), drawStopIndicator = {})
                    else LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
                }
            }
            Spacer(Modifier.width(4.dp))
            when {
                !t.status.isFinal -> IconButton(onClick = { Downloads.cancel(t.id) }) { Icon(Icons.Rounded.Close, "Cancel") }
                else -> Row {
                    IconButton(onClick = { Downloads.retry(ctx, t.id) }) { Icon(Icons.Rounded.Refresh, "Retry") }
                    IconButton(onClick = { Downloads.dismiss(t.id) }) { Icon(Icons.Rounded.Close, "Remove") }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory(nothing: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(if (nothing) Icons.Rounded.Download else Icons.Rounded.SearchOff, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(if (nothing) "No downloads yet" else "No matching downloads", style = MaterialTheme.typography.titleMedium)
        Text(if (nothing) "Everything you download will be listed here." else "Try another search or filter.", color = cs.onSurfaceVariant, fontSize = 13.5.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryEntry(h: HistoryItem, view: String, missing: Boolean, onOpenPage: (String) -> Unit, onPlay: (PlayItem) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val open = { if (!missing) onPlay(PlayItem(h.uri, h.mime, h.title)) else Toast.makeText(ctx, "This file was moved or deleted", Toast.LENGTH_SHORT).show() }
    val clickMod = Modifier.clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = open, onLongClick = { menu = true }).alpha(if (missing) 0.5f else 1f)
    val audio = h.mode == Mode.AUDIO
    val ext = h.ext.uppercase()
    val sizeFmt = listOf(fmtSize(h.size), ext.takeIf { it != h.label.uppercase() }).filter { !it.isNullOrBlank() }.joinToString(" · ")

    Box {
        when (view) {
            "list" -> Row(clickMod.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(h.thumb, audio, Modifier.width(64.dp).aspectRatio(16f / 9f), corner = 6, iconSize = 18, localUri = h.uri.takeIf { !missing })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(listOf(h.label, sizeFmt, h.site).filter { it.isNotBlank() }.joinToString(" · ") + if (missing) " · missing" else "",
                        fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(timeOrDate(h.date), fontSize = 12.sp, color = cs.onSurfaceVariant)
                IconButton(onClick = { menu = true }, Modifier.size(36.dp)) { Icon(Icons.Rounded.MoreVert, "More", Modifier.size(18.dp), tint = cs.onSurfaceVariant) }
            }
            "grid" -> Column(clickMod.fillMaxWidth().background(cs.surfaceContainerLow)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    Thumb(h.thumb, audio, Modifier.fillMaxSize(), corner = 0, iconSize = 28, localUri = h.uri.takeIf { !missing })
                    if (h.label.isNotBlank()) DurationBadge(h.label, Modifier.align(Alignment.TopStart).padding(6.dp))
                    if (h.duration > 0) DurationBadge(fmtDur(h.duration), Modifier.align(Alignment.BottomEnd).padding(6.dp))
                }
                Column(Modifier.padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 4.dp)) {
                    Text(h.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp, modifier = Modifier.heightIn(min = 34.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(listOf(sizeFmt, dayLabel(h.date)).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 11.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { menu = true }, Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreVert, "More", Modifier.size(17.dp), tint = cs.onSurfaceVariant) }
                    }
                    if (missing) Tag("File missing", danger = true)
                }
            }
            else -> Row(clickMod.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(140.dp).aspectRatio(16f / 9f)) {
                    Thumb(h.thumb, audio, Modifier.fillMaxSize(), corner = 10, localUri = h.uri.takeIf { !missing })
                    if (h.duration > 0) DurationBadge(fmtDur(h.duration), Modifier.align(Alignment.BottomEnd).padding(5.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (h.label.isNotBlank()) Tag(h.label, accent = h.label == "4K" || h.label == "8K")
                        if (ext.isNotBlank() && ext != h.label.uppercase()) Tag(ext)
                        if (missing) Tag("Missing", danger = true)
                        Text(fmtSize(h.size), fontSize = 12.sp, color = cs.onSurfaceVariant, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(listOf(h.site, timeLabel(h.date)).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = cs.onSurfaceVariant) }
            }
        }
        DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(14.dp)) {
            DropdownMenuItem(text = { Text("Play") }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) }, enabled = !missing, onClick = { menu = false; open() })
            DropdownMenuItem(text = { Text("Open with…") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) }, enabled = !missing, onClick = { menu = false; openFile(ctx, h.uri, h.mime) })
            DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, enabled = !missing, onClick = { menu = false; shareFile(ctx, h.uri, h.mime) })
            DropdownMenuItem(text = { Text("Open source page") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) }, enabled = h.pageUrl.startsWith("http"), onClick = { menu = false; onOpenPage(h.pageUrl) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Remove from list") }, leadingIcon = { Icon(Icons.Rounded.PlaylistRemove, null) }, onClick = { menu = false; History.remove(setOf(h.id)) })
            DropdownMenuItem(text = { Text("Delete file", color = cs.error) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = cs.error) }, onClick = { menu = false; confirmDelete = true })
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this file?") },
            text = { Text("“${h.title}” will be deleted from your phone.") },
            confirmButton = { TextButton(onClick = { MediaSaver.delete(ctx, h.uri); History.remove(setOf(h.id)); confirmDelete = false }) { Text("Delete", color = cs.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun timeOrDate(ts: Long) = dayLabel(ts).let { if (it == "Today") timeLabel(ts) else if (it == "Yesterday") it else java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(ts)) }
