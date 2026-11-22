package com.videograbber.app.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.videograbber.app.MainActivity
import com.videograbber.app.R
import com.videograbber.app.data.DownloadTask

object Notifier {
    const val CH_PROGRESS = "progress"
    const val CH_DONE = "done"
    const val ONGOING_ID = 1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun ongoing(ctx: Context, active: List<DownloadTask>) = NotificationCompat.Builder(ctx, CH_PROGRESS)
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(openApp(ctx))
        .apply {
            if (active.size == 1) {
                val t = active[0]
                setContentTitle(t.req.title)
                val pct = (t.progress * 100).toInt()
                setContentText(listOf("${t.req.label} · $pct%", t.speed).filter { it.isNotBlank() }.joinToString(" · "))
                setProgress(100, pct, t.progress <= 0f)
            } else {
                setContentTitle("Downloading ${active.size} videos")
                val avg = if (active.isEmpty()) 0f else active.map { it.progress }.average().toFloat()
                setContentText(active.joinToString(", ") { it.req.title.take(24) })
                setProgress(100, (avg * 100).toInt(), false)
            }
        }
        .build()

    fun finished(ctx: Context, t: DownloadTask) {
        if (!canPost(ctx) || t.savedUri == null) return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(t.savedUri), t.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, t.id.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(t.req.title)
            .setContentText("Downloaded · ${t.req.label} · tap to play")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(t.id.hashCode(), n) }
    }

    fun failed(ctx: Context, t: DownloadTask) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Download failed")
            .setContentText(t.req.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${t.req.title}\n${t.error.orEmpty()}"))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(t.id.hashCode(), n) }
    }
}
