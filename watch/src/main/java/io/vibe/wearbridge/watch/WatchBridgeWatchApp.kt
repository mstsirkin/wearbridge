package io.vibe.wearbridge.watch

import android.app.Application
import io.vibe.wearbridge.watch.core.WatchBridgeState

class WatchBridgeWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WatchBridgeState.log("Watch companion started")
    }
}
