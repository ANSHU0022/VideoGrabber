package com.videograbber.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videograbber.app.browser.BrowserController
import com.videograbber.app.browser.ProbeState
import com.videograbber.app.browser.Tab
import com.videograbber.app.data.Mode
import com.videograbber.app.data.Prefs
import com.videograbber.app.data.Stream
import com.videograbber.app.download.Engine

private val AUDIO = listOf("mp3" to "Plays everywhere", "m4a" to "AAC · best for Apple", "opus" to "Smallest file", "flac" to "Lossless container")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(
    c: BrowserController, tab: Tab, onDismiss: () -> Unit,
    onDownloadPage: (String) -> Unit, onDownloadStream: (Stream, String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val engine by Engine.state.collectAsStateWithLifecycle()
    val defaultAudio by Prefs.audioFormat.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Pages we don't auto-check get checked the moment the sheet opens.
    LaunchedEffect(tab.id) {
        if (tab.probe == ProbeState.IDLE) c.probe(tab)
        if (tab.info == null) c.readPagePreview(tab)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState, containerColor = cs.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            val info = tab.info
            when {
                engine is Engine.State.Failed -> Message(Icons.Rounded.ErrorOutline, "Downloader couldn't start", (engine as Engine.State.Failed).message)
                engine is Engine.State.Preparing && tab.probe != ProbeState.FOUND -> PreparingRow()
                tab.probe == ProbeState.LOADING -> Skeleton(tab.title, tab.pageImage)
                info != null -> {
                    // header
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(132.dp).aspectRatio(16f / 9f)) {
                            Thumb(info.thumbnail, audio = false, modifier = Modifier.fillMaxSize(), corner = 10)
                            if (info.duration > 0) DurationBadge(fmtDur(info.duration), Modifier.align(Alignment.BottomEnd).padding(5.dp))
                            if (info.isLive) DurationBadge("LIVE", Modifier.align(Alignment.TopStart).padding(5.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(info.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(listOf(info.uploader, info.site).filter { it.isNotBlank() }.joinToString(" · "), color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(18.dp))

                    // Video / Audio
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = tab.mode == Mode.VIDEO, onClick = { tab.mode = Mode.VIDEO }, shape = SegmentedButtonDefaults.itemShape(0, 2),
                            icon = { SegmentedButtonDefaults.Icon(tab.mode == Mode.VIDEO) { Icon(Icons.Rounded.Videocam, null, Modifier.size(18.dp)) } }) { Text("Video") }
                        SegmentedButton(selected = tab.mode == Mode.AUDIO, onClick = { tab.mode = Mode.AUDIO }, shape = SegmentedButtonDefaults.itemShape(1, 2),
                            icon = { SegmentedButtonDefaults.Icon(tab.mode == Mode.AUDIO) { Icon(Icons.Rounded.MusicNote, null, Modifier.size(18.dp)) } }) { Text("Audio only") }
                    }
                    Spacer(Modifier.height(14.dp))

                    var selectedSize = 0L
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp))) {
                        if (tab.mode == Mode.VIDEO) {
                            if (info.qualities.isEmpty()) OptionRow(true, "Best", null, emptyList(), "") {}
                            info.qualities.forEachIndexed { i, q ->
                                val on = q.height == tab.height
                                if (on) selectedSize = q.size
                                if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                                val tags = buildList {
                                    qTag(q.height)?.let { add(it to (q.height >= 2160)) }
                                    if (q.fps > 30) add("${q.fps} fps" to false)
                                    if (q.hdr) add("HDR" to true)
                                }
                                OptionRow(on, "${q.height}p", null, tags, fmtSize(q.size)) { tab.height = q.height }
                            }
                        } else {
                            val cur = tab.audioFormat ?: defaultAudio
                            selectedSize = info.audioSize
                            AUDIO.forEachIndexed { i, (f, note) ->
                                if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                                OptionRow(f == cur, f.uppercase(), note, emptyList(), "") { tab.audioFormat = f }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    val label = if (tab.mode == Mode.AUDIO) (tab.audioFormat ?: defaultAudio).uppercase() else tab.height?.let { qShort(it) } ?: "Best"
                    Button(
                        onClick = { onDownloadPage(label) }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download $label" + if (selectedSize > 0) " · ${fmtSize(selectedSize)}" else "", fontSize = 16.sp)
                    }
                }
                tab.probe == ProbeState.NONE -> {
                    Message(
                        if (tab.probeMsg.isNotEmpty()) Icons.Rounded.ErrorOutline else Icons.Rounded.VideocamOff,
                        if (tab.probeMsg.isNotEmpty()) "Couldn't read this page" else "No video found on this page",
                        tab.probeMsg.ifEmpty { if (tab.streams.isEmpty()) "Open a video and play it — then tap ⬇ again." else "Pick one of the streams this page played below." },
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { c.probe(tab) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Try again")
                    }
                }
                else -> Skeleton(tab.title, tab.pageImage)
            }

            // streams that played on the page (works on sites without a dedicated extractor)
            if (tab.streams.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Text("Streams on this page · ${tab.streams.size}", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                tab.streams.asReversed().take(8).forEach { s ->
                    val name = runCatching { android.net.Uri.parse(s.url).lastPathSegment }.getOrNull() ?: s.url
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onDownloadStream(s, s.kind) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tag(s.kind)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            Text(Tab.hostOf(s.url), maxLines = 1, color = cs.onSurfaceVariant, fontSize = 11.5.sp)
                        }
                        Icon(Icons.Rounded.Download, "Download stream", tint = cs.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(on: Boolean, title: String, note: String?, tags: List<Pair<String, Boolean>>, trailing: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).background(if (on) cs.primary.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent)
            .heightIn(min = 52.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = on, onClick = onClick, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.widthIn(min = 56.dp))
        if (note != null) Text(note, color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
        Row(Modifier.weight(1f).padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            tags.forEach { (t, accent) -> Tag(t, accent = accent) }
        }
        if (trailing.isNotEmpty()) Text(trailing, color = cs.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun Message(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, color = cs.onSurfaceVariant, fontSize = 13.5.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun PreparingRow() {
    Message(Icons.Rounded.HourglassTop, "Getting the downloader ready", "Setting up and updating the download engine. This only happens on first launch.")
    LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
}

@Composable
private fun Skeleton(title: String, image: String?) {
    val cs = MaterialTheme.colorScheme
    val a by rememberInfiniteTransition(label = "sk").animateFloat(0.45f, 1f, infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "a")
    val block = Modifier.clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerHigh).alpha(a)
    // Real title + page image right away; only the quality list is a placeholder.
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (image != null) Thumb(image, audio = false, modifier = Modifier.width(132.dp).aspectRatio(16f / 9f), corner = 10)
        else Box(block.width(132.dp).aspectRatio(16f / 9f))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
        }
    }
    Column(Modifier.alpha(a)) {
        Spacer(Modifier.height(18.dp))
        Box(block.fillMaxWidth().height(40.dp))
        Spacer(Modifier.height(14.dp))
        Box(block.fillMaxWidth().height(200.dp))
        Spacer(Modifier.height(16.dp))
        Box(block.fillMaxWidth().height(54.dp))
    }
    Spacer(Modifier.height(8.dp))
    Text("Finding available qualities…", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}
