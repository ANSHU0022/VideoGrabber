package com.videograbber.app.download

import android.content.Context
import android.os.SystemClock
import com.videograbber.app.data.DownloadRequest
import com.videograbber.app.data.DownloadTask
import com.videograbber.app.data.HistoryItem
import com.videograbber.app.data.Mode
import com.videograbber.app.data.Prefs
import com.videograbber.app.data.TaskStatus
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Download queue: runs yt-dlp, saves the result to shared storage and records history. */
object Downloads {
    val tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val slots = Semaphore(3)
    private val SPEED = Regex("""at\s+~?\s*([\d.]+\s*[KMG]?i?B/s)""")
    private val TRANSIENT = Regex("""403|Forbidden|timed out|Connection reset|HTTP Error 5\d\d|Remote end closed|IncompleteRead|fragment .* not found|part-Frag|Unable to download""", RegexOption.IGNORE_CASE)

    fun active() = tasks.value.filter { !it.status.isFinal }

    fun start(ctx: Context, req: DownloadRequest): String {
        val app = ctx.applicationContext
        val id = UUID.randomUUID().toString().take(12)
        tasks.update { listOf(DownloadTask(id, req)) + it }
        DownloadService.start(app)
        jobs[id] = scope.launch { run(app, id, req) }
        return id
    }

    fun cancel(id: String) {
        cancelled += id
        Engine.cancel(id)
        jobs.remove(id)?.cancel()
        update(id) { if (it.status.isFinal) it else it.copy(status = TaskStatus.CANCELLED) }
    }

    fun retry(ctx: Context, id: String) {
        val t = tasks.value.firstOrNull { it.id == id } ?: return
        dismiss(id)
        start(ctx, t.req)
    }

    fun dismiss(id: String) = tasks.update { l -> l.filterNot { it.id == id && it.status.isFinal } }
    fun clearFinished() = tasks.update { l -> l.filterNot { it.status.isFinal } }

    private inline fun update(id: String, crossinline f: (DownloadTask) -> DownloadTask) =
        tasks.update { l -> l.map { if (it.id == id) f(it) else it } }

    private fun sanitize(s: String) = s.replace(Regex("""[/\\:*?"<>|\x00-\x1f]+"""), " ").replace(Regex("""\s+"""), " ").trim().take(120).ifEmpty { "video" }

    private fun buildRequest(ctx: Context, req: DownloadRequest, dir: File, fragments: Int, useInfo: Boolean): YoutubeDLRequest {
        val info = req.infoJson?.let { File(it) }?.takeIf { useInfo && it.exists() }
        val r = if (info != null) YoutubeDLRequest(emptyList<String>()).apply { addOption("--load-info-json", info.absolutePath) }
        else YoutubeDLRequest(req.url)
        Engine.baseOptions(r, Cookies.fileFor(ctx, req.pageUrl, req.url), req.userAgent)
        val template = if (req.isStream) sanitize(req.title).replace("%", "%%") + ".%(ext)s" else "%(title).120B [%(id)s].%(ext)s"
        r.addOption("-o", File(dir, template).absolutePath)
        r.addOption("--no-mtime")
        r.addOption("--concurrent-fragments", fragments)
        r.addOption("--retries", 10)
        r.addOption("--fragment-retries", 10)
        if (!req.referer.isNullOrBlank()) r.addOption("--referer", req.referer)
        if (req.mode == Mode.AUDIO) {
            r.addOption("-f", "ba/b")
            r.addOption("-x")
            r.addOption("--audio-format", req.audioFormat)
            r.addOption("--audio-quality", "0")
        } else {
            val h = req.height
            r.addOption("-f", if (h != null) "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b" else "bv*+ba/b")
            if (Prefs.compatible.value) {
                r.addOption("-S", "vcodec:h264,res,proto,acodec:m4a")
                r.addOption("--merge-output-format", "mp4")
            } else {
                r.addOption("-S", "res,fps,hdr:12,proto,vbr")
                r.addOption("--merge-output-format", "mp4/mkv")
            }
        }
        return r
    }

    private suspend fun run(ctx: Context, id: String, req: DownloadRequest) = slots.withPermit {
        val dir = File(ctx.cacheDir, "dl/$id").apply { deleteRecursively(); mkdirs() }
        try {
            Engine.awaitReady()
            var attempt = 1
            var fragments = Prefs.fragments.value
            var useInfo = req.infoJson != null
            while (true) {
                if (id in cancelled) return@withPermit
                update(id) { it.copy(status = if (attempt > 1) TaskStatus.RETRYING else TaskStatus.DOWNLOADING, attempt = attempt, error = null) }
                val request = buildRequest(ctx, req, dir, fragments, useInfo)
                try {
                    var last = 0L
                    var lastPct = -1f
                    var stage = 1
                    val t0 = SystemClock.elapsedRealtime()
                    var firstByteLogged = false
                    YoutubeDL.getInstance().execute(request, id) { progress, eta, line ->
                        if (!firstByteLogged && progress > 0) { firstByteLogged = true; android.util.Log.i("VideoGrabber", "download started after ${SystemClock.elapsedRealtime() - t0} ms (saved info: $useInfo)") }
                        if (line.contains("[Merger]") || line.contains("[ExtractAudio]") || line.contains("[FixupM3u8]") || line.contains("[VideoConvertor]")) {
                            update(id) { it.copy(status = TaskStatus.PROCESSING) }
                            return@execute
                        }
                        if (progress < 0) return@execute
                        if (progress + 5 < lastPct) stage++ // second stream (audio) started
                        lastPct = progress
                        val now = SystemClock.uptimeMillis()
                        if (now - last < 300) return@execute
                        last = now
                        val speed = SPEED.find(line)?.groupValues?.get(1)?.replace("iB", "B").orEmpty()
                        val s = stage
                        update(id) { it.copy(status = TaskStatus.DOWNLOADING, progress = progress / 100f, etaSeconds = eta, speed = speed.ifEmpty { it.speed }, stage = s) }
                    }
                    val out = dir.walkTopDown().filter {
                        it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.contains(".part-Frag") &&
                            !it.name.endsWith(".temp") && !it.name.endsWith(".json") && !it.name.startsWith("cookies-")
                    }.maxByOrNull { it.length() } ?: error("Download finished but no file was produced")
                    update(id) { it.copy(status = TaskStatus.PROCESSING, progress = 1f) }
                    val saved = MediaSaver.save(ctx, out, req.mode)
                    val site = runCatching { URI(req.pageUrl).host.removePrefix("www.").removePrefix("m.") }.getOrDefault("")
                    History.add(
                        HistoryItem(
                            id = id, title = req.title.ifBlank { out.nameWithoutExtension }, uri = saved.uri.toString(), mime = saved.mime,
                            ext = out.extension.lowercase(), size = saved.size, label = req.label, mode = req.mode, thumb = req.thumb,
                            duration = req.duration, site = site, pageUrl = req.pageUrl, date = System.currentTimeMillis(),
                        )
                    )
                    update(id) { it.copy(status = TaskStatus.DONE, progress = 1f, savedUri = saved.uri.toString(), mime = saved.mime, size = saved.size) }
                    tasks.value.firstOrNull { it.id == id }?.let { Notifier.finished(ctx, it) }
                    return@withPermit
                } catch (e: YoutubeDL.CanceledException) {
                    update(id) { it.copy(status = TaskStatus.CANCELLED) }
                    return@withPermit
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (id in cancelled) { update(id) { it.copy(status = TaskStatus.CANCELLED) }; return@withPermit }
                    if (useInfo) { useInfo = false; continue } // saved links expired → extract fresh, no penalty
                    val msg = Engine.errorLine(e)
                    if (attempt < 4 && TRANSIENT.containsMatchIn(msg + " " + e.message.orEmpty())) {
                        if (msg.contains("Frag", true)) fragments = 1 // parallel fragments tripped up: go sequential
                        attempt++
                        update(id) { it.copy(status = TaskStatus.RETRYING, attempt = attempt) }
                        delay(1500L * attempt)
                        continue
                    }
                    update(id) { it.copy(status = TaskStatus.ERROR, error = msg) }
                    tasks.value.firstOrNull { it.id == id }?.let { Notifier.failed(ctx, it) }
                    return@withPermit
                }
            }
        } catch (e: CancellationException) {
            update(id) { if (it.status.isFinal) it else it.copy(status = TaskStatus.CANCELLED) }
        } catch (e: Exception) {
            update(id) { it.copy(status = TaskStatus.ERROR, error = Engine.errorLine(e)) }
        } finally {
            dir.deleteRecursively()
            jobs.remove(id)
            cancelled.remove(id)
        }
    }
}
