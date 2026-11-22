package com.videograbber.app.browser

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.videograbber.app.data.Mode
import com.videograbber.app.data.Stream
import com.videograbber.app.data.VideoInfo
import kotlinx.coroutines.Job

enum class ProbeState { IDLE, LOADING, FOUND, NONE }

/** One browser tab. All fields are Compose state so the UI follows along. */
class Tab(val id: Long, restoredUrl: String = "") {
    var url by mutableStateOf(restoredUrl)
    var title by mutableStateOf(if (restoredUrl.isEmpty()) "New tab" else hostOf(restoredUrl))
    var progress by mutableIntStateOf(100)
    var loading by mutableStateOf(false)
    var favicon by mutableStateOf<Bitmap?>(null)
    var preview by mutableStateOf<Bitmap?>(null)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    var probe by mutableStateOf(ProbeState.IDLE)
    var probeMsg by mutableStateOf("")
    var info by mutableStateOf<VideoInfo?>(null)
    var mode by mutableStateOf(Mode.VIDEO)
    var height by mutableStateOf<Int?>(null)
    var audioFormat by mutableStateOf<String?>(null)
    val streams = mutableStateListOf<Stream>()

    /** Compose state so the screen swaps from the start page to the WebView when it's created. */
    var webView by mutableStateOf<WebView?>(null)
    /** Tab that opened this one (target=_blank / window.open). Back closes this tab and returns there. */
    var openerId: Long? = null
    /** Shown instantly in the sheet while yt-dlp is still checking qualities. */
    var pageImage by mutableStateOf<String?>(null)
    var probeJob: Job? = null
    var lastKey = ""

    /** Home = our native start page (no WebView, no URL). */
    val isHome get() = webView == null && url.isEmpty()

    companion object {
        fun hostOf(url: String) = runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: url
    }
}
