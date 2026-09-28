package com.nextbench.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NextBenchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
    }
}
