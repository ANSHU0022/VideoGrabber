package com.videograbber.app.data

data class Quality(val height: Int, val fps: Int, val size: Long, val hdr: Boolean)

data class VideoInfo(
    val title: String,
    val thumbnail: String?,
    val duration: Int,
    val uploader: String,
    val site: String,
    val url: String,
    val isLive: Boolean,
    val qualities: List<Quality>,
    val audioSize: Long,
    /** yt-dlp's full JSON for this video, reused by the download (skips a second extraction). */
    val infoJson: String? = null,
    val fetchedAt: Long = 0,
)

/** A media request seen while the page was loading (HLS/DASH manifest or direct video file). */
data class Stream(val url: String, val kind: String, val at: Long = System.currentTimeMillis())

enum class Mode { VIDEO, AUDIO }

data class DownloadRequest(
    val url: String,
    val mode: Mode,
    val height: Int?,
    val audioFormat: String,
    val title: String,
    val thumb: String?,
    val label: String,
    val duration: Int,
    val pageUrl: String,
    val referer: String?,
    val isStream: Boolean,
    val userAgent: String?,
    val infoJson: String? = null,
)

enum class TaskStatus {
    QUEUED, DOWNLOADING, PROCESSING, RETRYING, DONE, ERROR, CANCELLED;
    val isFinal get() = this == DONE || this == ERROR || this == CANCELLED
}

data class DownloadTask(
    val id: String,
    val req: DownloadRequest,
    val status: TaskStatus = TaskStatus.QUEUED,
    val progress: Float = 0f,
    val speed: String = "",
    val etaSeconds: Long = -1,
    val stage: Int = 1,
    val error: String? = null,
    val attempt: Int = 1,
    val savedUri: String? = null,
    val mime: String? = null,
    val size: Long = 0,
)

data class HistoryItem(
    val id: String,
    val title: String,
    val uri: String,
    val mime: String,
    val ext: String,
    val size: Long,
    val label: String,
    val mode: Mode,
    val thumb: String?,
    val duration: Int,
    val site: String,
    val pageUrl: String,
    val date: Long,
)
