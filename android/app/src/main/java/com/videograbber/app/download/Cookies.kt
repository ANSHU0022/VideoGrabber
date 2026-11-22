package com.videograbber.app.download

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import java.io.File

/** Hands the in-app browser's login cookies to yt-dlp (Netscape cookie file). */
object Cookies {
    fun fileFor(ctx: Context, vararg urls: String?): File? {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return null
        val lines = StringBuilder("# Netscape HTTP Cookie File\n")
        var any = false
        for (url in urls.filterNotNull().distinct()) {
            val host = runCatching { Uri.parse(url).host }.getOrNull() ?: continue
            val raw = cm.getCookie(url) ?: continue
            val domain = "." + host.removePrefix("www.").removePrefix("m.")
            for (pair in raw.split(";")) {
                val i = pair.indexOf('=')
                if (i <= 0) continue
                val name = pair.substring(0, i).trim()
                val value = pair.substring(i + 1).trim()
                lines.append(domain).append("\tTRUE\t/\tFALSE\t0\t").append(name).append('\t').append(value).append('\n')
                any = true
            }
        }
        if (!any) return null
        return File(ctx.cacheDir, "cookies-${System.nanoTime()}.txt").apply { writeText(lines.toString()) }
    }
}
