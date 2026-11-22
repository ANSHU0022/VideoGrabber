package com.videograbber.app.download

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.videograbber.app.data.Prefs
import com.videograbber.app.data.Quality
import com.videograbber.app.data.VideoInfo
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.URI

class ProbeException(message: String) : Exception(message)

/** Thin wrapper over yt-dlp (Python) running on the device. */
object Engine {
    sealed interface State {
        data object Preparing : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Preparing)
    val version = MutableStateFlow("")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()
    private lateinit var cacheDir: File
    private lateinit var infoDir: File

    fun init(ctx: Context) {
        // yt-dlp's own cache (YouTube player code + solved signatures). The Android wrapper adds
        // --no-cache-dir unless we pass --cache-dir, which made every check redo that work.
        cacheDir = File(ctx.filesDir, "ytdlp-cache").apply { mkdirs() }
        infoDir = File(ctx.cacheDir, "info").apply { mkdirs() }
        scope.launch {
            infoDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 6 * 3600_000 }?.forEach { it.delete() }
            try {
                YoutubeDL.getInstance().init(ctx)
                FFmpeg.getInstance().init(ctx)
                // The yt-dlp bundled in the APK ages quickly (YouTube changes often → HTTP 403).
                // First launch: update before we report ready. Afterwards: refresh in the background daily.
                val first = Prefs.lastEngineUpdate == 0L
                if (first) withTimeoutOrNull(90_000) { selfUpdate(ctx) }
                version.value = YoutubeDL.getInstance().version(ctx).orEmpty()
                state.value = State.Ready
                ready.complete(Unit)
                if (!first && System.currentTimeMillis() - Prefs.lastEngineUpdate > 24 * 3600_000L) selfUpdate(ctx)
            } catch (e: Throwable) {
                state.value = State.Failed(e.message ?: e.javaClass.simpleName)
                ready.completeExceptionally(e)
            }
        }
    }

    suspend fun awaitReady() = ready.await()

    fun baseOptions(r: YoutubeDLRequest, cookies: File?, userAgent: String?) {
        r.addOption("--no-playlist")
        r.addOption("--no-warnings")
        r.addOption("--cache-dir", cacheDir.absolutePath)
        if (cookies != null) r.addOption("--cookies", cookies.absolutePath)
        if (!userAgent.isNullOrBlank()) r.addOption("--user-agent", userAgent)
    }

    suspend fun probe(url: String, key: String, cookies: File?, userAgent: String?, fast: Boolean = true): VideoInfo = withContext(Dispatchers.IO) {
        awaitReady()
        YoutubeDL.getInstance().destroyProcessById(key)
        val r = YoutubeDLRequest(url)
        baseOptions(r, cookies, userAgent)
        r.addOption("-J")
        // YouTube: the direct (non-HLS/DASH) formats already include every quality up to 8K.
        // Skipping the manifests + subtitle translations makes the check ~25% faster.
        if (fast) r.addOption("--extractor-args", "youtube:skip=hls,dash,translated_subs")
        val t0 = SystemClock.elapsedRealtime()
        try {
            val resp = YoutubeDL.getInstance().execute(r, key)
            Log.i("VideoGrabber", "probe ${SystemClock.elapsedRealtime() - t0} ms  $url")
            // Keep the full result so the download can start without extracting everything again.
            val json = File(infoDir, "${System.nanoTime()}.json").apply { writeText(resp.out) }
            val info = parseInfo(JSONObject(resp.out), url).copy(infoJson = json.absolutePath, fetchedAt = System.currentTimeMillis())
            // Live streams only exist as HLS — if the fast check found nothing, look again without skipping.
            if (fast && info.qualities.isEmpty() && url.contains("youtu")) probe(url, key, null, userAgent, fast = false) else info
        } finally {
            cookies?.delete()
        }
    }

    private fun selfUpdate(ctx: Context) {
        runCatching {
            if (Downloads.active().isNotEmpty()) return
            YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
            Prefs.lastEngineUpdate = System.currentTimeMillis()
            version.value = YoutubeDL.getInstance().version(ctx).orEmpty()
            Log.i("VideoGrabber", "yt-dlp updated to ${version.value}")
        }.onFailure { Log.w("VideoGrabber", "yt-dlp update failed: ${it.message}") }
    }

    fun cancel(key: String) { runCatching { YoutubeDL.getInstance().destroyProcessById(key) } }

    suspend fun update(ctx: Context): String = withContext(Dispatchers.IO) {
        awaitReady()
        val status = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
        Prefs.lastEngineUpdate = System.currentTimeMillis()
        version.value = YoutubeDL.getInstance().version(ctx).orEmpty()
        if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "Already up to date" else "Updated"
    }

    /** The last "ERROR: ..." line yt-dlp printed, without the prefix. */
    fun errorLine(e: Throwable): String {
        val text = e.message.orEmpty()
        val line = text.lines().map { it.trim() }.lastOrNull { it.startsWith("ERROR") }
            ?: text.lines().lastOrNull { it.isNotBlank() } ?: e.javaClass.simpleName
        return line.replace(Regex("^ERROR:\\s*(\\[[^\\]]+\\]\\s*)?"), "").take(300)
    }

    private fun JSONObject.num(k: String): Double = if (isNull(k)) 0.0 else optDouble(k, 0.0).let { if (it.isNaN()) 0.0 else it }
    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

    private fun parseInfo(j: JSONObject, requested: String): VideoInfo {
        if (j.str("_type") == "playlist") {
            val n = j.optJSONArray("entries")?.length() ?: 0
            throw ProbeException("This is a playlist or channel ($n items). Open a single video to download it.")
        }
        val duration = j.num("duration")
        val formats = j.optJSONArray("formats")
        var audioSize = 0L
        var bestAbr = -1.0
        val byHeight = HashMap<Int, Pair<Quality, Double>>()
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val v = f.str("vcodec"); val a = f.str("acodec")
                if (v == "none" && a.isNotEmpty() && a != "none") {
                    val abr = maxOf(f.num("abr"), f.num("tbr"))
                    if (abr > bestAbr) { bestAbr = abr; audioSize = (f.num("filesize").takeIf { it > 0 } ?: f.num("filesize_approx")).toLong() }
                }
            }
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val h = f.num("height").toInt()
                val v = f.str("vcodec")
                if (h <= 0 || v.isEmpty() || v == "none") continue
                val tbr = f.num("tbr")
                var vSize = f.num("filesize").takeIf { it > 0 } ?: f.num("filesize_approx")
                if (vSize <= 0 && tbr > 0 && duration > 0) vSize = tbr * 1000 / 8 * duration
                val size = if (vSize > 0) vSize.toLong() + (if (f.str("acodec") == "none") audioSize else 0L) else 0L
                val dr = f.str("dynamic_range")
                val q = Quality(h, f.num("fps").toInt(), size, dr.isNotEmpty() && dr != "SDR")
                val prev = byHeight[h]
                if (prev == null || tbr > prev.second) byHeight[h] = q to tbr
            }
        }
        val pageUrl = j.str("webpage_url").ifEmpty { requested }
        val site = runCatching { URI(pageUrl).host.removePrefix("www.").removePrefix("m.") }.getOrDefault("")
        return VideoInfo(
            title = j.str("title").ifEmpty { j.str("id") },
            thumbnail = j.str("thumbnail").ifEmpty { null },
            duration = duration.toInt(),
            uploader = j.str("uploader").ifEmpty { j.str("channel") },
            site = site,
            url = pageUrl,
            isLive = j.optBoolean("is_live", false),
            qualities = byHeight.values.map { it.first }.sortedByDescending { it.height },
            audioSize = audioSize,
        )
    }
}
