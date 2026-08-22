package com.example.torrplayer

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import com.example.torrplayer.prefs.AppPrefs

class TorrPlayerApplication : Application() {

    companion object {
        lateinit var instance: TorrPlayerApplication
            private set
        lateinit var prefs: AppPrefs
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = AppPrefs(this)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }
}
