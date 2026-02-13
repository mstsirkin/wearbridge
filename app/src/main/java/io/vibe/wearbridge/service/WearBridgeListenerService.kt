package io.vibe.wearbridge.service

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import io.vibe.wearbridge.core.BridgeState
import io.vibe.wearbridge.protocol.CompanionInfo
import io.vibe.wearbridge.protocol.RemoteAppInfo
import io.vibe.wearbridge.protocol.WearProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.util.Locale

class WearBridgeListenerService : WearableListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        serviceScope.launch {
            handleMessage(messageEvent)
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val frozenEvents = dataEvents.map { it.freeze() }
        dataEvents.release()

        frozenEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val path = event.dataItem.uri.path ?: return@forEach
            if (path == WearProtocol.EXPORTED_APK_PATH) {
                serviceScope.launch {
                    saveExportedArchive(event.dataItem)
                }
            }
        }
    }

    private suspend fun handleMessage(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearProtocol.LOG_MESSAGE_PATH -> {
                BridgeState.log("Watch: ${String(messageEvent.data, Charsets.UTF_8)}")
            }

            WearProtocol.APP_LIST_START_PATH -> {
                val expectedChunks = String(messageEvent.data, Charsets.UTF_8).toIntOrNull()
                BridgeState.startChunkTransfer(expectedChunks)
            }

            WearProtocol.APP_LIST_CHUNK_PATH -> {
                runCatching {
                    val payload = String(messageEvent.data, Charsets.UTF_8)
                    json.decodeFromString<List<RemoteAppInfo>>(payload)
                }.onSuccess { chunk ->
                    BridgeState.appendChunk(chunk)
                }.onFailure { error ->
                    BridgeState.log("Failed to decode app chunk: ${error.message}")
                }
            }

            WearProtocol.APP_LIST_END_PATH -> {
                BridgeState.finishChunkTransfer()
            }

            WearProtocol.CHECK_COMPANION_RESPONSE_PATH -> {
                runCatching {
                    val payload = String(messageEvent.data, Charsets.UTF_8)
                    json.decodeFromString<CompanionInfo>(payload)
                }.onSuccess { info ->
                    BridgeState.setCompanionInfo(info)
                    BridgeState.log("Watch companion ${info.versionName} (${info.versionCode})")
                }.onFailure { error ->
                    BridgeState.log("Failed to decode companion response: ${error.message}")
                }
            }

            else -> {
                BridgeState.log("Unhandled message path: ${messageEvent.path}")
            }
        }
    }

    private suspend fun saveExportedArchive(dataItem: com.google.android.gms.wearable.DataItem) {
        val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
        val asset = dataMap.getAsset(WearProtocol.KEY_APK_FILE_ASSET)
        val packageName = dataMap.getString(WearProtocol.KEY_PACKAGE_NAME)
        val appLabel = dataMap.getString(WearProtocol.KEY_APP_LABEL)

        if (asset == null) {
            BridgeState.log("Export payload missing apk_file asset")
            return
        }

        val dataClient = Wearable.getDataClient(this)

        runCatching {
            val input = dataClient.getFdForAsset(asset).await().inputStream

            val base = sanitizeFileComponent(if (appLabel.isNullOrBlank()) packageName ?: "wear-export" else appLabel)
            val fileName = "$base-${System.currentTimeMillis()}.zip"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/WearBridge"
                    )
                }
            }

            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: error("Unable to create MediaStore entry")

            contentResolver.openOutputStream(uri)?.use { output ->
                input.use { source ->
                    source.copyTo(output)
                }
            } ?: error("Unable to open output stream")

            dataClient.deleteDataItems(dataItem.uri).await()

            BridgeState.log("Saved exported archive: $fileName")
        }.onFailure { error ->
            BridgeState.log("Failed to save exported archive: ${error.message}")
        }
    }

    private fun sanitizeFileComponent(raw: String): String {
        return raw
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "wear-export" }
            .lowercase(Locale.US)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
