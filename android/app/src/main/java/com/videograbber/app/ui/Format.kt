package com.videograbber.app.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

fun fmtSize(b: Long): String = when {
    b <= 0 -> ""
    b >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", b / 1e9)
    b >= 1_000_000 -> "${(b / 1e6).roundToInt()} MB"
    else -> "${(b / 1e3).roundToInt()} KB"
}

fun fmtDur(sec: Int): String {
    if (sec <= 0) return ""
    val h = sec / 3600; val m = sec / 60 % 60; val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun fmtEta(sec: Long): String {
    if (sec < 0) return ""
    val m = sec / 60; val s = sec % 60
    return if (m >= 60) "${m / 60}h ${m % 60}m left" else if (m > 0) "${m}m ${s}s left" else "${s}s left"
}

/** 2160 → "4K", 1440 → "1440p", 1080 → "1080p" */
fun qShort(h: Int) = when { h >= 4320 -> "8K"; h >= 2160 -> "4K"; h >= 1440 -> "1440p"; else -> "${h}p" }

/** Marketing tag shown next to the resolution. */
fun qTag(h: Int): String? = when { h >= 4320 -> "8K"; h >= 2160 -> "4K"; h >= 1440 -> "2K"; h >= 720 -> "HD"; else -> null }

fun dayLabel(ts: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    val today = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(c, today)) return "Today"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (same(c, today)) return "Yesterday"
    val pattern = if (c.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)) "EEEE, d MMMM" else "d MMMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
}

fun timeLabel(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
