package io.vibe.wearbridge.core

import android.content.Context
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import io.vibe.wearbridge.protocol.CapabilityCheckRequest
import io.vibe.wearbridge.files.SelectedFile
import io.vibe.wearbridge.protocol.ScreenshotRequest
import io.vibe.wearbridge.protocol.WearProtocol
import io.vibe.wearbridge.protocol.indexedApkAssetKey
import io.vibe.wearbridge.protocol.indexedApkNameKey
import io.vibe.wearbridge.protocol.indexedApkSizeKey
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class UploadProgress(
    val percent: Int,
    val stage: String,
    val details: String? = null
)

class WearBridgeClient(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { encodeDefaults = false }

    private val messageClient: MessageClient = Wearable.getMessageClient(appContext)
    private val nodeClient: NodeClient = Wearable.getNodeClient(appContext)
    private val dataClient: DataClient = Wearable.getDataClient(appContext)

    suspend fun connectedNodes(): List<Node> = nodeClient.connectedNodes.await()

    suspend fun requestAppSync(): Int {
        return sendMessageToAllNodes(WearProtocol.SYNC_REQUEST_PATH, ByteArray(0))
    }

    suspend fun requestCompanionInfo(): Int {
        return sendMessageToAllNodes(WearProtocol.CHECK_COMPANION_PATH, ByteArray(0))
    }

    suspend fun requestApkExport(packageName: String): Int {
        return sendMessageToAllNodes(WearProtocol.REQUEST_APK_PATH, packageName.toByteArray(Charsets.UTF_8))
    }

    suspend fun requestDelete(packageName: String): Int {
        return sendMessageToAllNodes(WearProtocol.DELETE_APP_PATH, packageName.toByteArray(Charsets.UTF_8))
    }

    suspend fun requestScreenshot(request: ScreenshotRequest? = null): Int {
        val payload = if (request == null) {
            ByteArray(0)
        } else {
            json.encodeToString(request).toByteArray(Charsets.UTF_8)
        }
        return sendMessageToAllNodes(WearProtocol.REQUEST_SCREENSHOT_PATH, payload)
    }

    suspend fun requestCapabilities(request: CapabilityCheckRequest? = null): Int {
        val payload = if (request == null) {
            ByteArray(0)
        } else {
            json.encodeToString(request).toByteArray(Charsets.UTF_8)
        }
        return sendMessageToAllNodes(WearProtocol.CHECK_CAPABILITIES_PATH, payload)
    }

    suspend fun sendInstallData(
        packageName: String,
        selectedFiles: List<SelectedFile>,
        onProgress: ((UploadProgress) -> Unit)? = null
    ) {
        require(selectedFiles.isNotEmpty()) { "selectedFiles must not be empty" }
        onProgress?.invoke(
            UploadProgress(
                percent = 5,
                stage = "validate_inputs",
                details = "files=${selectedFiles.size}"
            )
        )

        val nodes = connectedNodes()
        require(nodes.isNotEmpty()) { "No watch nodes connected" }
        onProgress?.invoke(
            UploadProgress(
                percent = 10,
                stage = "connected_nodes",
                details = "count=${nodes.size}"
            )
        )

        val path = WearProtocol.INSTALL_DATA_PATH_PREFIX + System.currentTimeMillis()
        val putDataMapRequest = PutDataMapRequest.create(path)

        putDataMapRequest.dataMap.putString(WearProtocol.KEY_PACKAGE_NAME, packageName)
        putDataMapRequest.dataMap.putInt(WearProtocol.KEY_APK_COUNT, selectedFiles.size)

        selectedFiles.forEachIndexed { index, file ->
            putDataMapRequest.dataMap.putAsset(indexedApkAssetKey(index), Asset.createFromUri(file.uri))
            putDataMapRequest.dataMap.putString(indexedApkNameKey(index), file.name)
            putDataMapRequest.dataMap.putLong(indexedApkSizeKey(index), file.sizeBytes)
            val progress = 15 + (((index + 1) * 60) / selectedFiles.size)
            onProgress?.invoke(
                UploadProgress(
                    percent = progress.coerceIn(15, 75),
                    stage = "prepare_asset",
                    details = "index=${index + 1}/${selectedFiles.size} file=${file.name}"
                )
            )
        }

        onProgress?.invoke(UploadProgress(percent = 80, stage = "send_data_item"))
        dataClient.putDataItem(putDataMapRequest.asPutDataRequest().setUrgent()).await()
        onProgress?.invoke(UploadProgress(percent = 100, stage = "queued_to_datalayer"))
    }

    private suspend fun sendMessageToAllNodes(path: String, payload: ByteArray): Int {
        val nodes = connectedNodes()
        if (nodes.isEmpty()) return 0

        nodes.forEach { node ->
            messageClient.sendMessage(node.id, path, payload).await()
        }
        return nodes.size
    }
}
