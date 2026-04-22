package com.becash.becashplayer

import android.app.Application
import timber.log.Timber

class BecashPlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }
}
