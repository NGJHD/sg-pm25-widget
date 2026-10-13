package com.ngjhd.sgpm25widget

import android.app.Application
import android.content.Intent
import android.content.IntentFilter

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // ACTION_SCREEN_ON reaches runtime-registered receivers only.
        registerReceiver(ScreenOnReceiver(), IntentFilter(Intent.ACTION_SCREEN_ON))
    }
}
