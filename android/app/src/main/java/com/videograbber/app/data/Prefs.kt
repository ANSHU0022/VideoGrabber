package com.videograbber.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray

/** Small persisted settings store exposed as StateFlows so Compose recomposes on change. */
object Prefs {
    private lateinit var sp: SharedPreferences

    val theme = MutableStateFlow("system")          // system | light | dark
    val compatible = MutableStateFlow(false)         // true = H.264 MP4 (plays everywhere, usually ≤1080p)
    val audioFormat = MutableStateFlow("mp3")
    val fragments = MutableStateFlow(8)
    val historyView = MutableStateFlow("rows")       // list | rows | grid
    val searchEngine = MutableStateFlow("youtube")
    val askedNotifications = MutableStateFlow(false)
    var lastEngineUpdate: Long
        get() = sp.getLong("lastEngineUpdate", 0L)
        set(v) { sp.edit().putLong("lastEngineUpdate", v).apply() }

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        theme.value = sp.getString("theme", "system")!!
        compatible.value = sp.getBoolean("compatible", false)
        audioFormat.value = sp.getString("audioFormat", "mp3")!!
        fragments.value = sp.getInt("fragments", 8)
        historyView.value = sp.getString("historyView", "rows")!!
        searchEngine.value = sp.getString("searchEngine", "youtube")!!
        askedNotifications.value = sp.getBoolean("askedNotifications", false)
    }

    fun setTheme(v: String) { theme.value = v; sp.edit().putString("theme", v).apply() }
    fun setCompatible(v: Boolean) { compatible.value = v; sp.edit().putBoolean("compatible", v).apply() }
    fun setAudioFormat(v: String) { audioFormat.value = v; sp.edit().putString("audioFormat", v).apply() }
    fun setFragments(v: Int) { fragments.value = v; sp.edit().putInt("fragments", v).apply() }
    fun setHistoryView(v: String) { historyView.value = v; sp.edit().putString("historyView", v).apply() }
    fun setSearchEngine(v: String) { searchEngine.value = v; sp.edit().putString("searchEngine", v).apply() }
    fun setAskedNotifications() { askedNotifications.value = true; sp.edit().putBoolean("askedNotifications", true).apply() }

    var openTabs: List<String>
        get() = runCatching {
            val a = JSONArray(sp.getString("openTabs", "[]"))
            List(a.length()) { a.getString(it) }
        }.getOrDefault(emptyList())
        set(v) { sp.edit().putString("openTabs", JSONArray(v).toString()).apply() }

    var activeTabIndex: Int
        get() = sp.getInt("activeTab", 0)
        set(v) { sp.edit().putInt("activeTab", v).apply() }
}
