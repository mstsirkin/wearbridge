package io.vibe.wearbridge

import android.app.Application
import io.vibe.wearbridge.core.BridgeState
import io.vibe.wearbridge.core.ProgressLogFile

class WearBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProgressLogFile.init(this)
        BridgeState.log("WearBridge app started")
        ProgressLogFile.pathOrNull()?.let { path ->
            BridgeState.log("Progress log file: $path")
        }
    }
}
