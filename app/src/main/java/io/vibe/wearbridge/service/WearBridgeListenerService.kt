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
import io.vibe.wearbridge.protocol.CapabilityReport
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
            when (path) {
                WearProtocol.EXPORTED_APK_PATH -> {
                    serviceScope.launch {
                        saveExportedArchive(event.dataItem)
                    }
                }

                WearProtocol.SCREENSHOT_EXPORT_PATH -> {
                    serviceScope.launch {
                        saveWatchScreenshot(event.dataItem)
                    }
                }
            }
        }
    }

    private suspend fun handleMessage(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearProtocol.LOG_MESSAGE_PATH -> {
                BridgeState.logWatchMessage(String(messageEvent.data, Charsets.UTF_8))
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

            WearProtocol.CHECK_CAPABILITIES_RESPONSE_PATH -> {
                runCatching {
                    val payload = String(messageEvent.data, Charsets.UTF_8)
                    json.decodeFromString<CapabilityReport>(payload)
                }.onSuccess { report ->
                    BridgeState.setCapabilityReport(report)
                    val screenshot = report.capabilities?.get("screenshot")
                    if (screenshot != null) {
                        BridgeState.log(
                            "Watch caps screenshot: supported=${screenshot.supported} ready=${screenshot.ready} method=${screenshot.method ?: "unknown"}"
                        )
                    } else {
                        BridgeState.log("Watch capability report received")
                    }
                }.onFailure { error ->
                    BridgeState.log("Failed to decode capability response: ${error.message}")
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

    private suspend fun saveWatchScreenshot(dataItem: com.google.android.gms.wearable.DataItem) {
        val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
        val asset = dataMap.getAsset(WearProtocol.KEY_SCREENSHOT_FILE_ASSET)
        val requestId = dataMap.getString(WearProtocol.KEY_REQUEST_ID)
        val mimeType = dataMap.getString(WearProtocol.KEY_MIME_TYPE).orEmpty().ifBlank { "image/png" }
        val captureTimestamp = dataMap.getLong(WearProtocol.KEY_CAPTURE_TIMESTAMP)

        if (asset == null) {
            BridgeState.log("Screenshot payload missing screenshot_file asset")
            BridgeState.onScreenshotSaveFailed(
                requestId = requestId,
                details = "error=missing_screenshot_file_asset"
            )
            return
        }

        val dataClient = Wearable.getDataClient(this)
        BridgeState.onScreenshotReceived(
            requestId = requestId,
            details = "mime=${sanitizeLogToken(mimeType)}"
        )

        runCatching {
            val input = dataClient.getFdForAsset(asset).await().inputStream
            val extension = when (mimeType.lowercase(Locale.US)) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/webp" -> "webp"
                else -> "png"
            }
            val fileName = "watch-screenshot-${if (captureTimestamp > 0L) captureTimestamp else System.currentTimeMillis()}.$extension"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/WearBridge"
                    )
                }
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: error("Unable to create MediaStore entry")

            contentResolver.openOutputStream(uri)?.use { output ->
                input.use { source ->
                    source.copyTo(output)
                }
            } ?: error("Unable to open output stream")

            dataClient.deleteDataItems(dataItem.uri).await()

            BridgeState.log("Saved watch screenshot: $fileName")
            BridgeState.onScreenshotSaved(
                requestId = requestId,
                fileName = fileName,
                uriText = uri.toString()
            )
        }.onFailure { error ->
            BridgeState.log("Failed to save watch screenshot: ${error.message}")
            BridgeState.onScreenshotSaveFailed(
                requestId = requestId,
                details = "error=${sanitizeLogToken(error.message ?: "save_failed")}"
            )
        }
    }

    private fun sanitizeFileComponent(raw: String): String {
        return raw
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "wear-export" }
            .lowercase(Locale.US)
    }

    private fun sanitizeLogToken(raw: String): String {
        return raw
            .replace(Regex("\\s+"), "_")
            .trim()
            .ifBlank { "unknown" }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
