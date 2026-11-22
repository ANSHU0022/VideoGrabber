package com.videograbber.app.download

import android.content.Context
import com.videograbber.app.data.HistoryItem
import com.videograbber.app.data.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistent list of finished downloads (filesDir/history.json). */
object History {
    val items = MutableStateFlow<List<HistoryItem>>(emptyList())
    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "history.json")
        scope.launch {
            items.value = runCatching {
                val a = JSONArray(file.readText())
                List(a.length()) { fromJson(a.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }

    fun add(item: HistoryItem) { items.update { (listOf(item) + it).take(5000) }; save() }
    fun remove(ids: Set<String>) { items.update { l -> l.filterNot { it.id in ids } }; save() }
    fun clear() { items.value = emptyList(); save() }

    private fun save() {
        val snapshot = items.value
        scope.launch {
            val a = JSONArray()
            snapshot.forEach { a.put(toJson(it)) }
            runCatching { File(file.path + ".tmp").apply { writeText(a.toString()) }.renameTo(file) }
        }
    }

    private fun toJson(h: HistoryItem) = JSONObject().apply {
        put("id", h.id); put("title", h.title); put("uri", h.uri); put("mime", h.mime); put("ext", h.ext)
        put("size", h.size); put("label", h.label); put("mode", h.mode.name); put("thumb", h.thumb ?: "")
        put("duration", h.duration); put("site", h.site); put("pageUrl", h.pageUrl); put("date", h.date)
    }

    private fun fromJson(j: JSONObject) = HistoryItem(
        id = j.getString("id"), title = j.optString("title"), uri = j.optString("uri"), mime = j.optString("mime"),
        ext = j.optString("ext"), size = j.optLong("size"), label = j.optString("label"),
        mode = runCatching { Mode.valueOf(j.optString("mode")) }.getOrDefault(Mode.VIDEO),
        thumb = j.optString("thumb").ifEmpty { null }, duration = j.optInt("duration"), site = j.optString("site"),
        pageUrl = j.optString("pageUrl"), date = j.optLong("date"),
    )
}
