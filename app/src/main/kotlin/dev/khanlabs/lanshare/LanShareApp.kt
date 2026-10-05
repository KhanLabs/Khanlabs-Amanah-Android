package dev.khanlabs.lanshare

import android.app.Application
import dev.khanlabs.lanshare.transfer.Engine
import dev.khanlabs.lanshare.transfer.Notifications

class LanShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        Engine.init(this, BuildConfig.VERSION_NAME, BuildConfig.DEBUG)
    }
}
