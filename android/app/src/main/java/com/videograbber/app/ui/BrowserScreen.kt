package com.videograbber.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videograbber.app.browser.BrowserController
import com.videograbber.app.browser.ProbeState
import com.videograbber.app.browser.Tab
import com.videograbber.app.data.Prefs
import com.videograbber.app.download.Downloads
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(c: BrowserController, covered: Boolean, onOpenDownloads: () -> Unit, onOpenSettings: () -> Unit) {
    val tab = c.active
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showTabs by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val tasks by Downloads.tasks.collectAsStateWithLifecycle()
    val activeDownloads = tasks.count { !it.status.isFinal }
    val imeVisible = WindowInsets.isImeVisible

    // Ask once for notification permission, right before the first download.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pending?.invoke(); pending = null }
    fun withNotifications(action: () -> Unit) {
        val needs = Build.VERSION.SDK_INT >= 33 && !Prefs.askedNotifications.value &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) { Prefs.setAskedNotifications(); pending = action; notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } else action()
    }
    fun started(label: String) = scope.launch {
        val r = snackbar.showSnackbar("Downloading $label", actionLabel = "View", duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) onOpenDownloads()
    }

    LaunchedEffect(c.autoOpenSheetFor) {
        if (c.autoOpenSheetFor != null && c.autoOpenSheetFor == c.activeId) { showSheet = true; c.autoOpenSheetFor = null }
    }

    // Back: fullscreen → editing → page history → start page → leave app
    BackHandler(enabled = !covered && editing) { editing = false }
    BackHandler(enabled = !covered && !editing && !showTabs && (tab.canGoBack || !tab.isHome)) {
        c.backAction(tab)
    }
    BackHandler(enabled = !covered && showTabs) { showTabs = false }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            AddressBar(
                c = c, tab = tab, editing = editing, onEditingChange = { editing = it },
                onOpenSettings = onOpenSettings, onOpenDownloads = onOpenDownloads,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab.isHome) HomePage(onSubmit = { c.load(tab, it) }, onOpen = { c.load(tab, it) })
                else WebViewHost(c, tab)

                // thin load progress under the address bar
                if (tab.loading && !tab.isHome) {
                    LinearProgressIndicator(
                        progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                        color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent, drawStopIndicator = {},
                    )
                }

                if (!tab.isHome && !imeVisible && !editing) {
                    DraggableFab(tab = tab, label = c.topQualityLabel(tab)) { showSheet = true }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
            }
            if (!imeVisible) {
                BottomBar(
                    tab = tab, tabCount = c.tabs.size, activeDownloads = activeDownloads,
                    onBack = { c.backAction(tab) },
                    onForward = { tab.webView?.goForward() },
                    onHome = { c.goHome(tab) },
                    onTabs = { c.capturePreview(tab); showTabs = true },
                    onDownloads = onOpenDownloads,
                )
            }
        }

        AnimatedVisibility(showTabs, enter = fadeIn() + scaleIn(initialScale = 0.96f), exit = fadeOut() + scaleOut(targetScale = 0.96f)) {
            TabSwitcher(c, onClose = { showTabs = false })
        }

        c.fullscreenView?.let { v -> FullscreenVideo(v) { c.exitFullscreen() } }
    }

    if (showSheet) {
        DownloadSheet(
            c = c, tab = tab, onDismiss = { showSheet = false },
            onDownloadPage = { label -> withNotifications { c.downloadPage(tab); started(label) }; showSheet = false },
            onDownloadStream = { s, label -> withNotifications { c.downloadStream(tab, s); started(label) }; showSheet = false },
        )
    }
}

@Composable
private fun WebViewHost(c: BrowserController, tab: Tab) {
    val wv = tab.webView
    if (wv == null) {
        LaunchedEffect(tab.id) { c.ensureLoaded(tab) }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return
    }
    key(tab.id, wv) {
        AndroidView(factory = { (wv.parent as? ViewGroup)?.removeView(wv); wv }, modifier = Modifier.fillMaxSize())
    }
}

// ---------------------------------------------------------------- address bar
@Composable
private fun AddressBar(
    c: BrowserController, tab: Tab, editing: Boolean, onEditingChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit, onOpenDownloads: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var menu by remember { mutableStateOf(false) }
    // The text field reports "not focused" the moment it appears; only a *real* focus loss should close editing.
    var hadFocus by remember { mutableStateOf(false) }

    LaunchedEffect(editing) {
        if (editing) {
            val u = if (tab.isHome) "" else tab.url
            field = TextFieldValue(u, TextRange(0, u.length))
            hadFocus = false
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else { hadFocus = false; focusManager.clearFocus() }
    }

    Surface(color = cs.surface) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (editing) cs.surface else cs.surfaceContainerHigh)
                    .then(if (editing) Modifier.border(1.5.dp, cs.primary, RoundedCornerShape(14.dp)) else Modifier)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val secure = tab.url.startsWith("https://")
                Icon(
                    when { editing || tab.isHome -> Icons.Rounded.Search; secure -> Icons.Rounded.Lock; else -> Icons.Rounded.Public },
                    null, tint = cs.onSurfaceVariant, modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                if (editing) {
                    BasicTextField(
                        value = field, onValueChange = { field = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface, fontSize = 15.sp),
                        cursorBrush = SolidColor(cs.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = {
                            if (field.text.isNotBlank()) c.load(tab, field.text)
                            onEditingChange(false)
                        }),
                        modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged {
                            if (it.isFocused) hadFocus = true
                            else if (hadFocus && editing) onEditingChange(false)
                        },
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (field.text.isEmpty()) Text("Search or type a link", color = cs.onSurfaceVariant, fontSize = 15.sp)
                                inner()
                            }
                        },
                    )
                    if (field.text.isNotEmpty()) {
                        IconButton(onClick = { field = TextFieldValue("") }, Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.Cancel, "Clear", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                } else {
                    Text(
                        if (tab.isHome) "Search or type a link" else Tab.hostOf(tab.url),
                        color = if (tab.isHome) cs.onSurfaceVariant else cs.onSurface, fontSize = 15.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).fillMaxHeight().wrapContentHeight(Alignment.CenterVertically)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onEditingChange(true) },
                    )
                    if (!tab.isHome) {
                        IconButton(onClick = { c.reloadOrStop(tab) }, Modifier.size(36.dp)) {
                            Icon(if (tab.loading) Icons.Rounded.Close else Icons.Rounded.Refresh, if (tab.loading) "Stop" else "Reload",
                                tint = cs.onSurfaceVariant, modifier = Modifier.size(19.dp))
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Menu", tint = cs.onSurface) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(14.dp)) {
                    DropdownMenuItem(text = { Text("New tab") }, leadingIcon = { Icon(Icons.Rounded.Add, null) }, onClick = { menu = false; c.newTab() })
                    if (!tab.isHome) {
                        DropdownMenuItem(text = { Text("Find video on this page") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, onClick = { menu = false; c.probe(tab) })
                        DropdownMenuItem(text = { Text("Share page") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { menu = false; c.shareActive() })
                    }
                    DropdownMenuItem(text = { Text("Downloads") }, leadingIcon = { Icon(Icons.Rounded.Download, null) }, onClick = { menu = false; onOpenDownloads() })
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Rounded.Settings, null) }, onClick = { menu = false; onOpenSettings() })
                }
            }
        }
    }
}


// ---------------------------------------------------------------- bottom bar
@Composable
private fun BottomBar(
    tab: Tab, tabCount: Int, activeDownloads: Int,
    onBack: () -> Unit, onForward: () -> Unit, onHome: () -> Unit, onTabs: () -> Unit, onDownloads: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surface) {
        Column {
            HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically,
            ) {
                BarButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", enabled = tab.canGoBack || !tab.isHome, onClick = onBack)
                BarButton(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", enabled = tab.canGoForward, onClick = onForward)
                BarButton(Icons.Rounded.Home, "Home", enabled = !tab.isHome, onClick = onHome)
                // Tab counter (Chrome-style rounded square)
                IconButton(onClick = onTabs) {
                    Box(
                        Modifier.size(22.dp).border(1.8.dp, cs.onSurface, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (tabCount > 99) ":D" else "$tabCount", fontSize = if (tabCount > 9) 10.sp else 12.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                    }
                }
                Box {
                    BarButton(Icons.Rounded.Download, "Downloads", enabled = true, onClick = onDownloads)
                    if (activeDownloads > 0) {
                        Badge(Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 6.dp), containerColor = cs.primary) { Text("$activeDownloads") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, desc, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
    }
}

// ---------------------------------------------------------------- floating download button
@Composable
private fun BoxScope.DraggableFab(tab: Tab, label: String?, onClick: () -> Unit) {
    val density = LocalDensity.current
    val margin = with(density) { 16.dp.toPx() }
    var parent by remember { mutableStateOf(IntSize.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val x = remember { Animatable(Float.NaN) }
    val y = remember { Animatable(Float.NaN) }
    val scope = rememberCoroutineScope()

    Box(Modifier.matchParentSize().onSizeChanged { parent = it })

    // initial spot: bottom-right; keep it on screen when the area shrinks or the pill grows
    LaunchedEffect(parent, size) {
        if (parent.width == 0 || size.width == 0) return@LaunchedEffect
        val maxX = parent.width - size.width - margin
        val maxY = parent.height - size.height - margin
        if (x.value.isNaN()) { x.snapTo(maxX); y.snapTo(maxY - margin) }
        else {
            x.snapTo(if (x.value + size.width / 2f > parent.width / 2f) maxX else margin)
            y.snapTo(y.value.coerceIn(margin, maxY))
        }
    }

    val found = tab.probe == ProbeState.FOUND
    val loading = tab.probe == ProbeState.LOADING
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = cs.primary,
        contentColor = cs.onPrimary,
        shadowElevation = 10.dp,
        modifier = Modifier
            .offset { IntOffset(if (x.value.isNaN()) -9999 else x.value.roundToInt(), if (y.value.isNaN()) -9999 else y.value.roundToInt()) }
            .onSizeChanged { size = it }
            .pointerInput(parent) {
                detectDragGestures(
                    onDragEnd = {
                        scope.launch {
                            val toX = if (x.value + size.width / 2f > parent.width / 2f) parent.width - size.width - margin else margin
                            x.animateTo(toX, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
                        }
                    },
                ) { change, drag ->
                    change.consume()
                    scope.launch {
                        x.snapTo((x.value + drag.x).coerceIn(0f, (parent.width - size.width).toFloat()))
                        y.snapTo((y.value + drag.y).coerceIn(margin, parent.height - size.height - margin))
                    }
                }
            },
    ) {
        Row(Modifier.height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = cs.onPrimary, strokeWidth = 2.4.dp)
            else Icon(Icons.Rounded.Download, "Download video", Modifier.size(24.dp))
            if (found && label != null) {
                Spacer(Modifier.width(8.dp))
                Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- fullscreen video
@Composable
private fun FullscreenVideo(view: android.view.View, onExit: () -> Unit) {
    val activity = LocalContext.current as Activity
    BackHandler { onExit() }
    DisposableEffect(view) {
        val ctl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { ctl.show(WindowInsetsCompat.Type.systemBars()) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { (view.parent as? ViewGroup)?.removeView(view); view }, modifier = Modifier.fillMaxSize())
    }
}
