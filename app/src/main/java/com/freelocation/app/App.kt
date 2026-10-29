package com.freelocation.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceHolder.app = applicationContext
        CrashLog.install(this)
        MockLocationService.attachLogFile(this)
        MockLocationService.markProcessStart()
    }
}
