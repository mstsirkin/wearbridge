package io.vibe.wearbridge.watch.core

import android.content.Context
import com.google.android.gms.wearable.Wearable
import io.vibe.wearbridge.watch.protocol.WearProtocol
import kotlinx.coroutines.tasks.await

object PhoneNodeMessenger {
    suspend fun sendMessageToNode(
        context: Context,
        nodeId: String,
        path: String,
        payload: ByteArray
    ) {
        Wearable.getMessageClient(context).sendMessage(nodeId, path, payload).await()
    }

    suspend fun sendMessageToAll(
        context: Context,
        path: String,
        payload: ByteArray
    ): Int {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) return 0

        val messageClient = Wearable.getMessageClient(context)
        nodes.forEach { node ->
            messageClient.sendMessage(node.id, path, payload).await()
        }
        return nodes.size
    }

    suspend fun safeLogToPhone(context: Context, message: String) {
        val clean = message.replace('\n', ' ').replace('\r', ' ').trim()
        runCatching {
            val sent = sendMessageToAll(
                context = context,
                path = WearProtocol.LOG_MESSAGE_PATH,
                payload = clean.toByteArray(Charsets.UTF_8)
            )
            if (sent > 0) {
                WatchBridgeState.log("Sent log to phone nodes=$sent")
            } else {
                WatchBridgeState.log("No phone nodes connected for log delivery")
            }
        }.onFailure { error ->
            WatchBridgeState.log("Failed to send log to phone: ${error.message}")
        }
    }
}
