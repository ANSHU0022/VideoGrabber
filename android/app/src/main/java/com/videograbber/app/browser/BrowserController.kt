package com.videograbber.app.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.scale
import androidx.core.view.drawToBitmap
import androidx.lifecycle.lifecycleScope
import com.videograbber.app.data.DownloadRequest
import com.videograbber.app.data.Mode
import com.videograbber.app.data.Prefs
import com.videograbber.app.data.Stream
import com.videograbber.app.download.Cookies
import com.videograbber.app.download.Downloads
import com.videograbber.app.download.Engine
import com.videograbber.app.data.VideoInfo
import com.videograbber.app.download.ProbeException
import com.videograbber.app.download.ProbeTimeout
import com.videograbber.app.ui.qShort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONTokener
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import com.videograbber.app.data.Quality
import android.util.Log

/** Owns the tabs and their WebViews (kept alive across recompositions and config changes). */
class BrowserController(private val activity: ComponentActivity) {
    val tabs = mutableStateListOf<Tab>()
    var activeId by mutableLongStateOf(0L)
        private set
    val active: Tab get() = tabs.firstOrNull { it.id == activeId } ?: tabs.first()

    var fullscreenView by mutableStateOf<View?>(null)
        private set
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    /** Set when the sheet should open by itself (e.g. a link shared from another app). */
    var autoOpenSheetFor by mutableStateOf<Long?>(null)

    private var seq = 0L
    var userAgent: String = ""
        private set

    init {
        val saved = Prefs.openTabs.filter { it.startsWith("http") }
        if (saved.isEmpty()) newTab() else {
            saved.forEach { tabs += Tab(++seq, it) }
            setActive(tabs[Prefs.activeTabIndex.coerceIn(0, tabs.lastIndex)].id)
        }
    }

    // ---------------- tabs ----------------
    fun newTab(url: String = "", background: Boolean = false): Tab {
        val t = Tab(++seq)
        val at = tabs.indexOfFirst { it.id == activeId }
        if (at >= 0) tabs.add(at + 1, t) else tabs += t
        if (!background || tabs.size == 1) setActive(t.id)
        if (url.isNotEmpty()) load(t, url)
        persist()
        return t
    }

    fun select(t: Tab) { setActive(t.id); persist() }

    private fun setActive(id: Long) { activeId = id; syncPlayback() }

    /** Only the visible tab plays; hidden tabs are paused (saves CPU, battery and memory). */
    fun syncPlayback(appVisible: Boolean = true) {
        tabs.forEach { t -> t.webView?.let { if (appVisible && t.id == activeId) it.onResume() else it.onPause() } }
    }

    fun close(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        t.probeJob?.cancel()
        Engine.cancel("probe-${t.id}")
        tabs.removeAt(i)
        t.webView?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        t.webView = null
        if (tabs.isEmpty()) newTab()
        else if (t.id == activeId) setActive(tabs[minOf(i, tabs.lastIndex)].id)
        persist()
    }

    fun closeAll() { tabs.toList().forEach { close(it) } }

    fun persist() {
        Prefs.openTabs = tabs.map { it.url }.filter { it.startsWith("http") }
        Prefs.activeTabIndex = tabs.filter { it.url.startsWith("http") }.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
    }

    // ---------------- navigation ----------------
    fun load(t: Tab, input: String) {
        val url = toUrl(input) ?: return
        ensureWebView(t).loadUrl(url)
        t.url = url
        onUrl(t, url, newPage = true)
        persist()
    }

    /** A restored tab only creates its WebView when first shown. */
    fun ensureLoaded(t: Tab) { if (t.webView == null && t.url.isNotEmpty()) load(t, t.url) }

    fun goHome(t: Tab) {
        t.probeJob?.cancel()
        t.webView?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        t.webView = null
        t.url = ""; t.title = "New tab"; t.favicon = null; t.info = null; t.probe = ProbeState.IDLE
        t.streams.clear(); t.canGoBack = false; t.canGoForward = false; t.loading = false; t.progress = 100
        persist()
    }

    fun back(t: Tab): Boolean {
        val wv = t.webView ?: return false
        if (wv.canGoBack()) { wv.goBack(); return true }
        return false
    }

    /** Phone/toolbar Back: page history → tab that opened this one → start page. */
    fun backAction(t: Tab) {
        if (back(t)) return
        val opener = t.openerId?.let { id -> tabs.firstOrNull { it.id == id } }
        if (opener != null) { close(t); select(opener) } else goHome(t)
    }

    /** Title + preview image from the page itself (og:image), so the sheet has something to show immediately. */
    fun readPagePreview(t: Tab) {
        val wv = t.webView ?: return
        wv.evaluateJavascript(
            "(function(){var m=document.querySelector('meta[property=\"og:image\"],meta[name=\"twitter:image\"]');" +
                "var v=document.querySelector('video');return (m&&m.content)||(v&&v.poster)||'';})()",
        ) { r ->
            t.pageImage = r?.trim('"')?.replace("\\/", "/")?.takeIf { it.startsWith("http") } ?: youtubeThumb(t.url)
        }
    }

    private fun youtubeThumb(url: String): String? {
        val id = Regex("""(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|live/)|youtu\.be/)([\w-]{11})""").find(url)?.groupValues?.get(1) ?: return null
        return "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    }

    fun reloadOrStop(t: Tab) { t.webView?.let { if (t.loading) it.stopLoading() else it.reload() } }

    fun capturePreview(t: Tab) {
        val wv = t.webView ?: return
        if (wv.width <= 0 || wv.height <= 0) return
        runCatching {
            val bmp = wv.drawToBitmap(Bitmap.Config.RGB_565)
            val scale = 360f / bmp.width
            t.preview = bmp.scale(360, (bmp.height * scale).toInt())
            bmp.recycle()
        }
    }

    fun exitFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        fullscreenView = null
    }

    // ---------------- video detection ----------------
    private val NOISE = setOf("t", "pp", "si", "feature", "ab_channel", "start", "time_continue", "utm_source", "utm_medium", "utm_campaign", "fbclid", "igsh", "igshid")

    private val probeCache = HashMap<String, VideoInfo>() // reopening a video is instant
    private val PROBE_CACHE_MS = 30 * 60_000L

    private fun videoKey(url: String): String = runCatching {
        val u = Uri.parse(url)
        val q = u.queryParameterNames.filter { it !in NOISE }.sorted().joinToString("&") { "$it=${u.getQueryParameter(it)}" }
        // www.youtube.com and m.youtube.com are the same page (sites redirect between them)
        val host = u.host.orEmpty().removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
        // A pasted youtu.be link redirects to youtube.com/watch: same video, so don't check it twice.
        if (host == "youtu.be") return@runCatching "youtube.com/watch?v=${u.path.orEmpty().trim('/')}"
        "$host${u.path}?$q"
    }.getOrDefault(url)

    private val VIDEO_PAGES = listOf(
        "youtube\\.com/(watch|shorts/|live/)", "youtu\\.be/", "instagram\\.com/(p|reel|reels|tv)/", "tiktok\\.com/.+/video/",
        "(x|twitter)\\.com/[^/]+/status/", "facebook\\.com/(watch|reel|.+/videos/)", "fb\\.watch/", "vimeo\\.com/\\d+",
        "dailymotion\\.com/video/", "reddit\\.com/r/[^/]+/comments/", "twitch\\.tv/(videos/|.+/clip/)", "bilibili\\.com/video/",
        "soundcloud\\.com/[^/]+/[^/?]+", "streamable\\.com/", "rumble\\.com/v", "pinterest\\.[a-z.]+/pin/", "linkedin\\.com/(posts|feed/update)/",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private fun onUrl(t: Tab, url: String, newPage: Boolean) {
        if (!url.startsWith("http")) return
        val key = videoKey(url)
        val same = key == t.lastKey
        t.url = url
        if (newPage && !same) { t.streams.clear(); t.favicon = null }
        if (same) return
        t.lastKey = key
        t.probeJob?.cancel()
        Engine.cancel("probe-${t.id}")
        t.info = null; t.probeMsg = ""; t.pageImage = null
        t.probe = ProbeState.IDLE
        if (VIDEO_PAGES.any { it.containsMatchIn(url) }) {
            val hit = probeCache[key]
            if (hit != null && System.currentTimeMillis() - hit.fetchedAt < PROBE_CACHE_MS) {
                t.info = hit
                if (hit.qualities.none { it.height == t.height }) t.height = hit.qualities.firstOrNull()?.height
                t.probe = ProbeState.FOUND
            } else probe(t)
        }
    }

    /** Ask yt-dlp which qualities this page offers. */
    fun probe(t: Tab) {
        val url = t.url
        if (!url.startsWith("http")) return
        t.probeJob?.cancel()
        t.probe = ProbeState.LOADING
        t.probeMsg = ""
        t.probeJob = activity.lifecycleScope.launch {
            // The open YouTube page already knows its formats: show them at once while
            // yt-dlp does the slower full check in the background.
            val instant = launch { readYouTubePage(t, url) }
            try {
                val info = try {
                    Engine.probe(url, "probe-${t.id}", Cookies.fileFor(activity, url), userAgent)
                } catch (e: ProbeTimeout) { // stalled connection: one automatic retry
                    Engine.probe(url, "probe-${t.id}", Cookies.fileFor(activity, url), userAgent)
                }
                instant.cancel()
                probeCache[videoKey(url)] = info
                if (videoKey(t.url) != videoKey(url)) return@launch
                t.info = info
                if (info.qualities.none { it.height == t.height }) t.height = info.qualities.firstOrNull()?.height
                t.probe = ProbeState.FOUND
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProbeTimeout) {
                instant.cancel()
                if (videoKey(t.url) == videoKey(url) && t.info == null) { t.probe = ProbeState.NONE; t.probeMsg = e.message.orEmpty() }
            } catch (e: ProbeException) {
                instant.cancel()
                if (videoKey(t.url) == videoKey(url) && t.info == null) { t.probe = ProbeState.NONE; t.probeMsg = e.message.orEmpty() }
            } catch (e: Exception) {
                instant.cancel()
                if (videoKey(t.url) != videoKey(url) || t.info != null) return@launch
                val msg = Engine.errorLine(e)
                t.probe = ProbeState.NONE
                t.probeMsg = if (Regex("Unsupported URL|no video|not a valid URL|Unable to extract|No video formats|Canceled", RegexOption.IGNORE_CASE).containsMatchIn(msg)) "" else msg
            }
        }
    }

    private val YT_ID = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/live/)([\w-]{11})""")

    private suspend fun readYouTubePage(t: Tab, url: String) {
        val id = YT_ID.find(url)?.groupValues?.get(1) ?: return
        repeat(40) {
            val wv = t.webView
            if (t.info != null || videoKey(t.url) != videoKey(url)) return
            if (wv != null) {
                val raw = CompletableDeferred<String?>()
                wv.evaluateJavascript(YT_PAGE_JS) { raw.complete(it) }
                val info = runCatching { parseYouTubePage(raw.await(), id, url) }.getOrNull()
                if (info != null) {
                    t.info = info
                    if (info.qualities.none { it.height == t.height }) t.height = info.qualities.first().height
                    t.probe = ProbeState.FOUND
                    Log.i("VideoGrabber", "instant ${info.qualities.map { it.height }} $url")
                    return
                }
            }
            delay(250)
        }
    }

    private fun parseYouTubePage(raw: String?, id: String, url: String): VideoInfo? {
        val text = raw?.takeIf { it != "null" }?.let { JSONTokener(it).nextValue() as? String } ?: return null
        val j = JSONObject(text)
        if (j.optString("id") != id) return null
        val f = j.getJSONArray("f")
        val rows = (0 until f.length()).map { f.getJSONObject(it) }
        val audio = rows.filter { it.optBoolean("audio") }.maxByOrNull { it.optLong("br") }?.optLong("size") ?: 0L
        val qualities = rows.filter { !it.optBoolean("audio") && it.optInt("h") > 0 }
            .groupBy { it.optInt("h") }.values
            .map { same -> same.maxBy { it.optLong("br") } }
            .map { Quality(it.optInt("h"), it.optInt("fps"), it.optLong("size").let { s -> if (s > 0) s + audio else 0L }, it.optBoolean("hdr")) }
            .sortedByDescending { it.height }
        if (qualities.isEmpty()) return null
        return VideoInfo(
            title = j.optString("title"), thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            duration = j.optInt("len"), uploader = j.optString("author"), site = "youtube.com",
            url = "https://www.youtube.com/watch?v=$id", isLive = j.optBoolean("live"),
            qualities = qualities, audioSize = audio,
        )
    }

    private val YT_PAGE_JS = """(function(){try{
        var p=document.getElementById('movie_player'),r=p&&p.getPlayerResponse&&p.getPlayerResponse();
        if(!r||!r.streamingData)r=window.ytInitialPlayerResponse;
        if(!r||!r.videoDetails||!r.streamingData)return null;
        var d=r.videoDetails;
        return JSON.stringify({id:d.videoId,title:d.title,author:d.author,len:+d.lengthSeconds||0,live:!!d.isLive,
          f:(r.streamingData.adaptiveFormats||[]).map(function(x){return{h:x.height||0,fps:x.fps||0,size:+x.contentLength||0,br:x.bitrate||0,
            audio:/^audio/.test(x.mimeType||''),hdr:!!(x.colorInfo&&/2084|HLG|ARIB/i.test(x.colorInfo.transferCharacteristics||''))}})});
      }catch(e){return null}})()"""

    private fun classify(url: String, mime: String? = null): String? {
        val u = url.lowercase()
        if (u.contains("googlevideo.com") || Regex("""\.(ts|m4s|aac)(\?|$)""").containsMatchIn(u)) return null
        if (Regex("""\.m3u8(\?|$)""").containsMatchIn(u) || mime?.contains("mpegurl") == true) return "HLS"
        if (Regex("""\.mpd(\?|$)""").containsMatchIn(u) || mime?.contains("dash+xml") == true) return "DASH"
        if ((Regex("""\.(mp4|webm|mov|mkv)(\?|$)""").containsMatchIn(u) || mime?.startsWith("video/") == true) &&
            !Regex("""segment|[-_/]chunk|[-_/]frag|init\.(mp4|webm)|[?&](range|bytes|bytestart)=""").containsMatchIn(u)) return "MP4"
        return null
    }

    private fun addStream(t: Tab, url: String, kind: String) {
        val key = runCatching { Uri.parse(url).let { "${it.host}${it.path}" } }.getOrDefault(url)
        if (t.streams.any { runCatching { Uri.parse(it.url).let { u -> "${u.host}${u.path}" } }.getOrDefault(it.url) == key }) return
        t.streams += Stream(url, kind)
        if (t.streams.size > 30) t.streams.removeAt(0)
    }

    // ---------------- downloads ----------------
    fun downloadPage(t: Tab) {
        val i = t.info ?: return
        val fmt = t.audioFormat ?: Prefs.audioFormat.value
        val label = if (t.mode == Mode.AUDIO) fmt.uppercase() else t.height?.let { qShort(it) } ?: "Best"
        Downloads.start(activity, DownloadRequest(
            url = i.url, mode = t.mode, height = if (t.mode == Mode.VIDEO) t.height else null, audioFormat = fmt,
            title = i.title, thumb = i.thumbnail, label = label, duration = i.duration, pageUrl = i.url,
            referer = null, isStream = false, userAgent = userAgent,
            infoJson = i.infoJson?.takeIf { System.currentTimeMillis() - i.fetchedAt < 60 * 60_000 },
        ))
    }

    fun downloadStream(t: Tab, s: Stream) {
        val q = Regex("""(\d{3,4})p?[^\d]""").find(s.url)?.groupValues?.get(1)?.toIntOrNull()
        val label = if (q != null && q in 240..4320) "${q}p" else s.kind
        Downloads.start(activity, DownloadRequest(
            url = s.url, mode = Mode.VIDEO, height = null, audioFormat = Prefs.audioFormat.value,
            title = t.title.ifBlank { Tab.hostOf(t.url) }, thumb = t.info?.thumbnail, label = label, duration = t.info?.duration ?: 0,
            pageUrl = t.url, referer = t.url, isStream = true, userAgent = userAgent,
        ))
    }

    // ---------------- WebView ----------------
    fun ensureWebView(t: Tab): WebView = t.webView ?: createWebView(t).also { t.webView = it; if (t.id != activeId) it.onPause() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(t: Tab): WebView = WebView(activity).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            // Look like regular Chrome: some sites refuse "wv" (WebView) user agents.
            userAgentString = userAgentString.replace("; wv)", ")").replace(Regex(""" Version/[\d.]+"""), "")
        }
        userAgent = settings.userAgentString
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        setBackgroundColor(android.graphics.Color.WHITE)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob") return false
                try {
                    if (scheme == "intent") {
                        val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        try { activity.startActivity(intent) } catch (_: ActivityNotFoundException) {
                            intent.getStringExtra("browser_fallback_url")?.let { view.loadUrl(it) }
                        }
                    } else activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) { }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                t.loading = true
                if (favicon != null) t.favicon = favicon
                onUrl(t, url, newPage = true)
                t.canGoBack = view.canGoBack(); t.canGoForward = view.canGoForward()
            }

            override fun onPageFinished(view: WebView, url: String) {
                t.loading = false
                t.canGoBack = view.canGoBack(); t.canGoForward = view.canGoForward()
                if (!view.title.isNullOrBlank()) t.title = view.title!!
                persist()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                onUrl(t, url, newPage = false)
                t.canGoBack = view.canGoBack(); t.canGoForward = view.canGoForward()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                classify(u)?.let { kind -> activity.runOnUiThread { addStream(t, u, kind) } }
                return null
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { t.progress = newProgress }
            override fun onReceivedTitle(view: WebView, title: String?) { if (!title.isNullOrBlank() && !title.startsWith("http")) t.title = title }
            override fun onReceivedIcon(view: WebView, icon: Bitmap?) { if (icon != null) t.favicon = icon }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return }
                fullscreenView = view
                fullscreenCallback = callback
            }

            override fun onHideCustomView() { fullscreenView = null; fullscreenCallback = null }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val nt = newTab()
                nt.openerId = t.id
                val wv = ensureWebView(nt)
                nt.title = "Loading…"
                (resultMsg.obj as WebView.WebViewTransport).webView = wv
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) { tabs.firstOrNull { it.webView == window }?.let { close(it) } }
        }

        setDownloadListener { url, ua, contentDisposition, mimetype, _ ->
            val kind = classify(url, mimetype)
            if (kind != null || mimetype?.startsWith("video/") == true || mimetype?.startsWith("audio/") == true) {
                downloadStream(t, Stream(url, kind ?: "File"))
                Toast.makeText(activity, "Downloading…", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
            // Anything else (PDF, ZIP, …): hand to Android's download manager.
            runCatching {
                val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
                val req = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimetype)
                    .addRequestHeader("User-Agent", ua)
                    .apply { CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) } }
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                activity.getSystemService(DownloadManager::class.java).enqueue(req)
                Toast.makeText(activity, "Downloading $name", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun shareActive() {
        val t = active
        if (!t.url.startsWith("http")) return
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.url), null))
    }

    fun topQualityLabel(t: Tab): String? = t.info?.qualities?.firstOrNull()?.height?.let { qShort(it) }

    companion object {
        fun toUrl(input: String): String? {
            val s = input.trim()
            if (s.isEmpty()) return null
            if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(s)) return s
            if (!s.contains(' ') && Regex("""^[^\s]+\.[a-z]{2,}(/|:|\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(s)) return "https://$s"
            if (!s.contains(' ') && Regex("""^(localhost|\d{1,3}(\.\d{1,3}){3})(:\d+)?(/|$)""").containsMatchIn(s)) return "http://$s"
            val engine = when (Prefs.searchEngine.value) {
                "google" -> "https://www.google.com/search?tbm=vid&q="
                "vimeo" -> "https://vimeo.com/search?q="
                else -> "https://m.youtube.com/results?search_query="
            }
            return engine + Uri.encode(s)
        }

        fun extractUrl(text: String?): String? = text?.let { Regex("""https?://\S+""").find(it)?.value?.trimEnd('.', ',', ')', '"') }
    }
}
