package io.vibe.wearbridge

import android.app.Application
import io.vibe.wearbridge.core.BridgeState

class WearBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BridgeState.log("WearBridge app started")
    }
}
