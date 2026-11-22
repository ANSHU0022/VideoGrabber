package com.videograbber.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videograbber.app.data.Prefs
import com.videograbber.app.download.Engine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by Prefs.theme.collectAsStateWithLifecycle()
    val compatible by Prefs.compatible.collectAsStateWithLifecycle()
    val audio by Prefs.audioFormat.collectAsStateWithLifecycle()
    val fragments by Prefs.fragments.collectAsStateWithLifecycle()
    val version by Engine.version.collectAsStateWithLifecycle()
    val engine by Engine.state.collectAsStateWithLifecycle()
    var updating by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf("") }

    Scaffold(
        containerColor = cs.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerLow),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Group("Appearance") {
                Item("Theme") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEachIndexed { i, (v, n) ->
                            SegmentedButton(selected = theme == v, onClick = { Prefs.setTheme(v) }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(n) }
                        }
                    }
                }
            }
            Group("Downloads") {
                Choice("Best quality", "Keeps 4K/8K and HDR. Some files are MKV.", !compatible) { Prefs.setCompatible(false) }
                HorizontalDivider(color = cs.outlineVariant)
                Choice("Most compatible", "H.264 MP4 that plays everywhere. Usually up to 1080p.", compatible) { Prefs.setCompatible(true) }
                HorizontalDivider(color = cs.outlineVariant)
                Item("Default audio format") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("mp3", "m4a", "opus", "flac").forEachIndexed { i, f ->
                            SegmentedButton(selected = audio == f, onClick = { Prefs.setAudioFormat(f) }, shape = SegmentedButtonDefaults.itemShape(i, 4)) { Text(f.uppercase(), fontSize = 13.sp) }
                        }
                    }
                }
                HorizontalDivider(color = cs.outlineVariant)
                Item("Parallel connections", "Faster downloads on streaming sites") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(4, 8, 16).forEachIndexed { i, n ->
                            SegmentedButton(selected = fragments == n, onClick = { Prefs.setFragments(n) }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text("$n") }
                        }
                    }
                }
            }
            Group("Storage") {
                Info("Videos", "Movies/VideoGrabber — visible in your Gallery")
                HorizontalDivider(color = cs.outlineVariant)
                Info("Audio", "Music/VideoGrabber")
            }
            Group("Download engine") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("yt-dlp", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when (engine) {
                                is Engine.State.Preparing -> "Preparing…"
                                is Engine.State.Failed -> "Failed: ${(engine as Engine.State.Failed).message}"
                                else -> listOf(version, updateMsg).filter { it.isNotBlank() }.joinToString(" · ")
                            },
                            fontSize = 13.sp, color = cs.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(enabled = !updating && engine is Engine.State.Ready, onClick = {
                        updating = true; updateMsg = ""
                        scope.launch {
                            updateMsg = runCatching { Engine.update(ctx) }.getOrElse { "Update failed" }
                            updating = false
                        }
                    }) {
                        if (updating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Update")
                    }
                }
            }
            Group("About") {
                Info("VideoGrabber", "Version 1.0.0 · open source (MIT)")
                HorizontalDivider(color = cs.outlineVariant)
                Info("Note", "Videos protected by DRM (Netflix, Prime Video, Disney+, Spotify…) can't be downloaded.")
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp))
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) { Column(content = content) }
}

@Composable
private fun Item(title: String, subtitle: String? = null, control: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        control()
    }
}

@Composable
private fun Choice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun Info(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
