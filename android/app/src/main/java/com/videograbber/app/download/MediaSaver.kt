package com.videograbber.app.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.videograbber.app.data.Mode
import java.io.File

data class SavedFile(val uri: Uri, val mime: String, val size: Long, val name: String)

/** Moves a finished download into shared storage: videos → Movies/VideoGrabber, audio → Music/VideoGrabber. */
object MediaSaver {
    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    fun save(ctx: Context, file: File, mode: Mode): SavedFile {
        val ext = file.extension
        val mime = mimeFor(ext)
        val isAudio = mode == Mode.AUDIO || mime.startsWith("audio/")
        val collection = if (isAudio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (isAudio) "Music/VideoGrabber" else "Movies/VideoGrabber")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values) ?: error("Couldn't create the file in shared storage")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
                ?: error("Couldn't write the file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return SavedFile(uri, mime, file.length(), file.name)
    }

    fun exists(ctx: Context, uri: String): Boolean = runCatching {
        ctx.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    fun delete(ctx: Context, uri: String): Boolean = runCatching {
        ctx.contentResolver.delete(Uri.parse(uri), null, null) > 0
    }.getOrDefault(false)
}
