package com.videograbber.app

import android.app.Application
import com.videograbber.app.data.Prefs
import com.videograbber.app.download.Engine
import com.videograbber.app.download.History
import com.videograbber.app.download.Notifier

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        History.init(this)
        Notifier.createChannels(this)
        Engine.init(this) // unpacks Python + yt-dlp + ffmpeg in the background (first launch takes a few seconds)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
