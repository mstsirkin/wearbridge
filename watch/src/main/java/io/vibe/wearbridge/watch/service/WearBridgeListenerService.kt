package io.vibe.wearbridge.watch.service

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import io.vibe.wearbridge.watch.core.PhoneNodeMessenger
import io.vibe.wearbridge.watch.core.WatchBridgeState
import io.vibe.wearbridge.watch.core.MessagePasswordStore
import io.vibe.wearbridge.watch.install.InstallResultReceiver
import io.vibe.wearbridge.watch.protocol.AuthOnlyRequest
import io.vibe.wearbridge.watch.protocol.CapabilityCheckRequest
import io.vibe.wearbridge.watch.protocol.CapabilityReport
import io.vibe.wearbridge.watch.protocol.CapabilityStatus
import io.vibe.wearbridge.watch.protocol.CompanionInfo
import io.vibe.wearbridge.watch.protocol.PackageActionRequest
import io.vibe.wearbridge.watch.protocol.RemoteAppInfo
import io.vibe.wearbridge.watch.protocol.ScreenshotRequest
import io.vibe.wearbridge.watch.protocol.WearProtocol
import io.vibe.wearbridge.watch.protocol.indexedApkAssetKey
import io.vibe.wearbridge.watch.protocol.indexedApkNameKey
import io.vibe.wearbridge.watch.protocol.indexedApkSizeKey
import io.vibe.wearbridge.watch.screenshot.ScreenshotCaptureResult
import io.vibe.wearbridge.watch.screenshot.WatchScreenshotController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WearBridgeListenerService : WearableListenerService() {
    companion object {
        private const val APP_LIST_CHUNK_SIZE = 20
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val passwordStore by lazy(LazyThreadSafetyMode.NONE) { MessagePasswordStore(this) }

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
            if (path.startsWith(WearProtocol.INSTALL_DATA_PATH_PREFIX)) {
                serviceScope.launch {
                    handleInstallPayload(event.dataItem)
                }
            }
        }
    }

    private suspend fun handleMessage(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearProtocol.SYNC_REQUEST_PATH -> {
                val request = parseAuthOnlyRequest(messageEvent.data, "sync")
                if (!authorizeControlRequest(
                        action = "sync",
                        providedPassword = request?.password,
                        onFailure = { reason ->
                            PhoneNodeMessenger.safeLogToPhone(
                                context = this,
                                message = "SYNC_STATUS failure message=${sanitizeLogValue("auth_$reason")}"
                            )
                        }
                    )
                ) {
                    return
                }
                handleSyncRequest(messageEvent.sourceNodeId)
            }

            WearProtocol.CHECK_COMPANION_PATH -> {
                val request = parseAuthOnlyRequest(messageEvent.data, "companion")
                if (!authorizeControlRequest(
                        action = "check_companion",
                        providedPassword = request?.password,
                        onFailure = { reason ->
                            PhoneNodeMessenger.safeLogToPhone(
                                context = this,
                                message = "COMPANION_STATUS failure message=${sanitizeLogValue("auth_$reason")}"
                            )
                        }
                    )
                ) {
                    return
                }
                handleCompanionCheck(messageEvent.sourceNodeId)
            }

            WearProtocol.CHECK_CAPABILITIES_PATH -> {
                val request = parseCapabilityCheckRequest(messageEvent.data)
                if (!authorizeControlRequest(
                        action = "check_capabilities",
                        providedPassword = request?.password,
                        onFailure = { reason ->
                            PhoneNodeMessenger.safeLogToPhone(
                                context = this,
                                message = "CAPABILITIES_STATUS failure message=${sanitizeLogValue("auth_$reason")}"
                            )
                        }
                    )
                ) {
                    return
                }
                handleCapabilitiesCheck(
                    sourceNodeId = messageEvent.sourceNodeId,
                    payloadBytes = messageEvent.data,
                    parsedRequest = request
                )
            }

            WearProtocol.REQUEST_SCREENSHOT_PATH -> {
                val request = parseScreenshotRequest(messageEvent.data)
                if (!authorizeControlRequest(
                        action = "request_screenshot",
                        providedPassword = request?.password,
                        onFailure = { reason ->
                            val requestId = request?.requestId?.trim()?.takeIf { it.isNotEmpty() }
                            val requestToken = requestId ?: "none"
                            PhoneNodeMessenger.safeLogToPhone(
                                context = this,
                                message = "SCREENSHOT_STATUS status_failure request_id=$requestToken code=auth_failed message=${sanitizeLogValue(reason)}"
                            )
                        }
                    )
                ) {
                    return
                }
                handleScreenshotRequest(request)
            }

            WearProtocol.REQUEST_APK_PATH -> {
                val request = parsePackageActionRequest(messageEvent.data, "export")
                val packageName = request?.packageName.orEmpty().trim()
                if (!authorizeControlRequest(
                        action = "request_apk",
                        providedPassword = request?.password,
                        onFailure = { reason ->
                            val targetPackage = if (packageName.isBlank()) "unknown" else packageName
                            PhoneNodeMessenger.safeLogToPhone(
                                context = this,
                                message = "EXPORT_STATUS status_failure package=$targetPackage code=-1 message=${sanitizeLogValue("auth_$reason")}"
                            )
                        }
                    )
                ) {
                    return
                }
                handleExportRequest(packageName)
            }

            WearProtocol.DELETE_APP_PATH -> {
                val request = parsePackageActionRequest(messageEvent.data, "delete")
                val packageName = request?.packageName.orEmpty().trim()
                if (!authorizeControlRequest(
                        action = "delete_app",
                        providedPassword = request?.password,
                        onFailure = { reason ->
                            val targetPackage = if (packageName.isBlank()) "unknown" else packageName
                            PhoneNodeMessenger.safeLogToPhone(
                                context = this,
                                message = "DELETE_STATUS failure package=$targetPackage message=${sanitizeLogValue("auth_$reason")}"
                            )
                        }
                    )
                ) {
                    return
                }
                handleDeleteRequest(packageName)
            }

            else -> {
                WatchBridgeState.log("Unhandled path from phone: ${messageEvent.path}")
            }
        }
    }

    private suspend fun handleSyncRequest(sourceNodeId: String) {
        runCatching {
            val apps = queryLaunchableApps()
            val chunks = apps.chunked(APP_LIST_CHUNK_SIZE)

            PhoneNodeMessenger.sendMessageToNode(
                context = this,
                nodeId = sourceNodeId,
                path = WearProtocol.APP_LIST_START_PATH,
                payload = chunks.size.toString().toByteArray(Charsets.UTF_8)
            )

            chunks.forEach { chunk ->
                val payload = json.encodeToString(chunk).toByteArray(Charsets.UTF_8)
                PhoneNodeMessenger.sendMessageToNode(
                    context = this,
                    nodeId = sourceNodeId,
                    path = WearProtocol.APP_LIST_CHUNK_PATH,
                    payload = payload
                )
            }

            PhoneNodeMessenger.sendMessageToNode(
                context = this,
                nodeId = sourceNodeId,
                path = WearProtocol.APP_LIST_END_PATH,
                payload = ByteArray(0)
            )

            PhoneNodeMessenger.safeLogToPhone(
                context = this,
                message = "SYNC_STATUS apps=${apps.size} chunks=${chunks.size}"
            )
            WatchBridgeState.log("Sync request handled apps=${apps.size}")
        }.onFailure { error ->
            PhoneNodeMessenger.safeLogToPhone(
                context = this,
                message = "SYNC_STATUS failure message=${sanitizeLogValue(error.message ?: "sync_failed")}"
            )
            WatchBridgeState.log("Sync request failed: ${error.message}")
        }
    }

    private suspend fun handleCompanionCheck(sourceNodeId: String) {
        runCatching {
            val info = readCompanionInfo()
            val payload = json.encodeToString(info).toByteArray(Charsets.UTF_8)
            PhoneNodeMessenger.sendMessageToNode(
                context = this,
                nodeId = sourceNodeId,
                path = WearProtocol.CHECK_COMPANION_RESPONSE_PATH,
                payload = payload
            )
            WatchBridgeState.log("Companion info sent")
        }.onFailure { error ->
            WatchBridgeState.log("Companion check failed: ${error.message}")
        }
    }

    private suspend fun handleCapabilitiesCheck(
        sourceNodeId: String,
        payloadBytes: ByteArray,
        parsedRequest: CapabilityCheckRequest? = null
    ) {
        val request = parsedRequest ?: parseCapabilityCheckRequest(payloadBytes)
        runCatching {
            val report = buildCapabilityReport(request)
            val payload = json.encodeToString(report).toByteArray(Charsets.UTF_8)
            PhoneNodeMessenger.sendMessageToNode(
                context = this,
                nodeId = sourceNodeId,
                path = WearProtocol.CHECK_CAPABILITIES_RESPONSE_PATH,
                payload = payload
            )
            WatchBridgeState.log("Capability report sent requestId=${request?.requestId ?: "none"}")
        }.onFailure { error ->
            WatchBridgeState.log("Capability check failed: ${error.message}")
        }
    }

    private suspend fun handleScreenshotRequest(request: ScreenshotRequest?) {
        val requestId = request?.requestId?.trim()?.takeIf { it.isNotEmpty() }
        val requestToken = requestId ?: "none"

        PhoneNodeMessenger.safeLogToPhone(
            context = this,
            message = "SCREENSHOT_PROGRESS phase=request_received request_id=$requestToken"
        )

        when (val result = WatchScreenshotController.capture()) {
            is ScreenshotCaptureResult.Failure -> {
                PhoneNodeMessenger.safeLogToPhone(
                    context = this,
                    message = "SCREENSHOT_STATUS status_failure request_id=$requestToken code=${sanitizeLogValue(result.code)} message=${sanitizeLogValue(result.message)}"
                )
                WatchBridgeState.log("Screenshot request failed requestId=$requestToken code=${result.code}")
            }

            is ScreenshotCaptureResult.Success -> {
                runCatching {
                    val payload = result.payload
                    val putDataMapRequest = PutDataMapRequest.create(WearProtocol.SCREENSHOT_EXPORT_PATH)
                    putDataMapRequest.dataMap.putAsset(
                        WearProtocol.KEY_SCREENSHOT_FILE_ASSET,
                        Asset.createFromBytes(payload.pngBytes)
                    )
                    requestId?.let {
                        putDataMapRequest.dataMap.putString(WearProtocol.KEY_REQUEST_ID, it)
                    }
                    putDataMapRequest.dataMap.putString(WearProtocol.KEY_MIME_TYPE, "image/png")
                    putDataMapRequest.dataMap.putLong(
                        WearProtocol.KEY_CAPTURE_TIMESTAMP,
                        payload.captureTimestampMillis
                    )
                    putDataMapRequest.dataMap.putInt(WearProtocol.KEY_IMAGE_WIDTH, payload.width)
                    putDataMapRequest.dataMap.putInt(WearProtocol.KEY_IMAGE_HEIGHT, payload.height)
                    // Force DataItem change even if two screenshots are identical.
                    putDataMapRequest.dataMap.putLong("screenshot_nonce", System.currentTimeMillis())

                    Wearable.getDataClient(this)
                        .putDataItem(putDataMapRequest.asPutDataRequest().setUrgent())
                        .await()

                    PhoneNodeMessenger.safeLogToPhone(
                        context = this,
                        message = "SCREENSHOT_STATUS status_success request_id=$requestToken mime=image/png message=ready"
                    )
                    WatchBridgeState.log(
                        "Screenshot export queued requestId=$requestToken size=${payload.pngBytes.size}"
                    )
                }.onFailure { error ->
                    PhoneNodeMessenger.safeLogToPhone(
                        context = this,
                        message = "SCREENSHOT_STATUS status_failure request_id=$requestToken code=capture_failed message=${sanitizeLogValue(error.message ?: "screenshot_export_failed")}"
                    )
                    WatchBridgeState.log("Screenshot export failed requestId=$requestToken: ${error.message}")
                }
            }
        }
    }

    private suspend fun handleDeleteRequest(packageName: String) {
        if (packageName.isBlank()) {
            PhoneNodeMessenger.safeLogToPhone(
                context = this,
                message = "DELETE_STATUS failure package=unknown message=missing_package_name"
            )
            return
        }

        runCatching {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            PhoneNodeMessenger.safeLogToPhone(
                context = this,
                message = "DELETE_STATUS requested package=$packageName"
            )
            WatchBridgeState.log("Delete requested for $packageName")
        }.onFailure { error ->
            PhoneNodeMessenger.safeLogToPhone(
                context = this,
                message = "DELETE_STATUS failure package=$packageName message=${sanitizeLogValue(error.message ?: "delete_failed")}"
            )
            WatchBridgeState.log("Delete request failed: ${error.message}")
        }
    }

    private suspend fun handleExportRequest(packageName: String) {
        if (packageName.isBlank()) {
            PhoneNodeMessenger.safeLogToPhone(
                context = this,
                message = "EXPORT_STATUS status_failure package=unknown code=-1 message=missing_package_name"
            )
            return
        }

        runCatching {
            val payload = buildExportPayload(packageName)
            val request = PutDataMapRequest.create(WearProtocol.EXPORTED_APK_PATH)
            request.dataMap.putAsset(WearProtocol.KEY_APK_FILE_ASSET, Asset.createFromBytes(payload.archiveBytes))
            request.dataMap.putString(WearProtocol.KEY_PACKAGE_NAME, packageName)
            payload.appLabel?.let {
                request.dataMap.putString(WearProtocol.KEY_APP_LABEL, it)
            }
            request.dataMap.putLong("export_timestamp", System.currentTimeMillis())

            Wearable.getDataClient(this)
                .putDataItem(request.asPutDataRequest().setUrgent())
                .await()

            PhoneNodeMessenger.safeLogToPhone(
                context = this,
                message = "EXPORT_STATUS status_success package=$packageName code=0 message=ready"
            )
            WatchBridgeState.log("Export queued for $packageName size=${payload.archiveBytes.size}")
        }.onFailure { error ->
            PhoneNodeMessenger.safeLogToPhone(
                context = this,
                message = "EXPORT_STATUS status_failure package=$packageName code=-1 message=${sanitizeLogValue(error.message ?: "export_failed")}"
            )
            WatchBridgeState.log("Export failed for $packageName: ${error.message}")
        }
    }

    private fun parseAuthOnlyRequest(payloadBytes: ByteArray, label: String): AuthOnlyRequest? {
        if (payloadBytes.isEmpty()) return null
        val payload = payloadBytes.decodeUtf8().trim()
        if (payload.isEmpty()) return null
        return runCatching {
            json.decodeFromString<AuthOnlyRequest>(payload)
        }.getOrElse {
            WatchBridgeState.log("$label request payload decode failed; using defaults")
            null
        }
    }

    private fun parsePackageActionRequest(payloadBytes: ByteArray, label: String): PackageActionRequest? {
        if (payloadBytes.isEmpty()) return null
        val payload = payloadBytes.decodeUtf8().trim()
        if (payload.isEmpty()) return null
        return runCatching {
            json.decodeFromString<PackageActionRequest>(payload)
        }.getOrElse {
            // Backward compatibility: legacy phone sent just the package name string.
            WatchBridgeState.log("$label request payload uses legacy package format")
            PackageActionRequest(packageName = payload)
        }
    }

    private suspend fun authorizeControlRequest(
        action: String,
        providedPassword: String?,
        onFailure: suspend (reason: String) -> Unit
    ): Boolean {
        val expectedPassword = passwordStore.getPassword()
        if (expectedPassword == null) {
            return true
        }

        if (providedPassword == expectedPassword) {
            return true
        }

        val reason = if (providedPassword.isNullOrBlank()) {
            "missing_password"
        } else {
            "invalid_password"
        }
        WatchBridgeState.log("Rejected $action: $reason")
        onFailure(reason)
        return false
    }

    private fun parseCapabilityCheckRequest(payloadBytes: ByteArray): CapabilityCheckRequest? {
        if (payloadBytes.isEmpty()) return null
        val payload = payloadBytes.decodeUtf8().trim()
        if (payload.isEmpty()) return null
        return runCatching {
            json.decodeFromString<CapabilityCheckRequest>(payload)
        }.getOrElse {
            WatchBridgeState.log("Capability request payload decode failed; using defaults")
            null
        }
    }

    private fun parseScreenshotRequest(payloadBytes: ByteArray): ScreenshotRequest? {
        if (payloadBytes.isEmpty()) return null
        val payload = payloadBytes.decodeUtf8().trim()
        if (payload.isEmpty()) return null
        return runCatching {
            json.decodeFromString<ScreenshotRequest>(payload)
        }.getOrElse {
            WatchBridgeState.log("Screenshot request payload decode failed; using defaults")
            null
        }
    }

    private fun buildCapabilityReport(request: CapabilityCheckRequest?): CapabilityReport {
        val requested = request?.features?.toSet().orEmpty()
        val includeScreenshot = requested.isEmpty() || "screenshot" in requested

        val screenshotStatus = if (includeScreenshot) {
            val supported = WatchScreenshotController.supportsScreenshot()
            val ready = WatchScreenshotController.isReady()
            val missingRequirements = buildList {
                if (!supported) {
                    add("api_unsupported")
                } else if (!ready) {
                    add("accessibility_service_disabled")
                }
            }
            val requiredActions = buildList {
                if (supported && !ready) {
                    add("enable_accessibility_service")
                }
            }
            CapabilityStatus(
                supported = supported,
                ready = ready,
                method = WatchScreenshotController.method(),
                missingPermissions = emptyList(),
                missingRequirements = missingRequirements,
                requiredUserActions = requiredActions,
                details = buildJsonObject {
                    put("api_level", Build.VERSION.SDK_INT)
                    put("watch_sdk_int", Build.VERSION.SDK_INT)
                    put("accessibility_service_ready", ready)
                }
            )
        } else {
            null
        }

        val capabilityMap = buildMap<String, CapabilityStatus> {
            if (screenshotStatus != null) {
                put("screenshot", screenshotStatus)
            }
        }

        return CapabilityReport(
            requestId = request?.requestId,
            protocolVersion = 1,
            capabilities = capabilityMap
        )
    }

    private suspend fun handleInstallPayload(dataItem: DataItem) {
        val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
        val packageName = dataMap.getString(WearProtocol.KEY_PACKAGE_NAME).orEmpty()
        val providedPassword = dataMap.getString(WearProtocol.KEY_PASSWORD)
        val apkCount = dataMap.getInt(WearProtocol.KEY_APK_COUNT)
        val installDir = File(cacheDir, "install-${System.currentTimeMillis()}").apply { mkdirs() }

        try {
            if (!authorizeControlRequest(
                    action = "install_payload",
                    providedPassword = providedPassword,
                    onFailure = { reason ->
                        val targetPackage = if (packageName.isBlank()) "unknown" else packageName
                        PhoneNodeMessenger.safeLogToPhone(
                            context = this,
                            message = "INSTALL_STATUS status_failure package=$targetPackage code=-1 message=${sanitizeLogValue("auth_$reason")}"
                        )
                    }
                )
            ) {
                return
            }

            if (apkCount <= 0) {
                error("apk_count must be > 0")
            }

            PhoneNodeMessenger.safeLogToPhone(
                context = this,
                message = "INSTALL_PROGRESS phase=receive file=payload index=0/$apkCount"
            )

            val apkFiles = extractAssetsToFiles(dataMap, apkCount, installDir)
            enqueueInstall(packageName, apkFiles)

            PhoneNodeMessenger.safeLogToPhone(
                context = this,
                message = "INSTALL_PROGRESS phase=commit file=session index=$apkCount/$apkCount"
            )
        } catch (error: Throwable) {
            val targetPackage = if (packageName.isBlank()) "unknown" else packageName
            PhoneNodeMessenger.safeLogToPhone(
                context = this,
                message = "INSTALL_STATUS status_failure package=$targetPackage code=-1 message=${
                    sanitizeLogValue(error.message ?: "install_failed")
                }"
            )
            WatchBridgeState.log("Install payload failed: ${error.message}")
        } finally {
            runCatching {
                Wearable.getDataClient(this).deleteDataItems(dataItem.uri).await()
            }
            installDir.deleteRecursively()
        }
    }

    private suspend fun extractAssetsToFiles(
        dataMap: DataMap,
        apkCount: Int,
        outputDir: File
    ): List<File> {
        val dataClient = Wearable.getDataClient(this)
        val files = mutableListOf<File>()

        for (index in 0 until apkCount) {
            val asset = dataMap.getAsset(indexedApkAssetKey(index))
                ?: error("Missing asset for index=$index")
            val incomingName = dataMap.getString(indexedApkNameKey(index))
                ?: "split-$index.apk"
            val safeName = sanitizeApkName(incomingName, index)
            val outFile = File(outputDir, "${index.toString().padStart(2, '0')}-$safeName")

            dataClient.getFdForAsset(asset).await().inputStream.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val expectedSize = dataMap.getLong(indexedApkSizeKey(index))
            if (expectedSize > 0L && outFile.length() <= 0L) {
                error("Received empty APK for $safeName")
            }

            files += outFile
            PhoneNodeMessenger.safeLogToPhone(
                context = this,
                message = "INSTALL_PROGRESS phase=extract file=$safeName index=${index + 1}/$apkCount"
            )
        }

        return files
    }

    private suspend fun enqueueInstall(packageName: String, apkFiles: List<File>) {
        require(apkFiles.isNotEmpty()) { "No APK files to install" }

        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (packageName.isNotBlank()) {
                setAppPackageName(packageName)
            }
        }

        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)

        try {
            apkFiles.forEachIndexed { index, file ->
                PhoneNodeMessenger.safeLogToPhone(
                    context = this,
                    message = "INSTALL_PROGRESS phase=write file=${file.name} index=${index + 1}/${apkFiles.size}"
                )

                file.inputStream().use { input ->
                    session.openWrite(file.name, 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
            }

            val callbackIntent = Intent(this, InstallResultReceiver::class.java).apply {
                action = InstallResultReceiver.ACTION_INSTALL_RESULT
                putExtra(InstallResultReceiver.EXTRA_PACKAGE_NAME, packageName)
            }
            val callback = PendingIntent.getBroadcast(
                this,
                sessionId,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(callback.intentSender)
            WatchBridgeState.log("Install committed session=$sessionId package=$packageName")
        } catch (error: Throwable) {
            runCatching { session.abandon() }
            throw error
        } finally {
            session.close()
        }
    }

    private fun readCompanionInfo(): CompanionInfo {
        val info = packageInfo(packageName)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
        return CompanionInfo(
            versionName = info.versionName ?: "unknown",
            versionCode = versionCode
        )
    }

    private fun queryLaunchableApps(): List<RemoteAppInfo> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val packageNames = queryLauncherActivities(launcherIntent)
            .mapNotNull { resolve -> resolve.activityInfo?.packageName }
            .distinct()

        return packageNames.mapNotNull { packageName ->
            runCatching {
                val info = packageInfo(packageName)
                val appInfo = info.applicationInfo ?: applicationInfo(packageName)
                val label = appInfo.loadLabel(packageManager).toString().ifBlank { packageName }
                val versionName = info.versionName ?: readableVersionCode(info)
                RemoteAppInfo(
                    packageName = packageName,
                    label = label,
                    versionName = versionName,
                    icon = null,
                    size = estimateApkSize(appInfo),
                    installTime = info.firstInstallTime
                )
            }.getOrNull()
        }.sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    private data class ExportPayload(
        val appLabel: String?,
        val archiveBytes: ByteArray
    )

    private fun buildExportPayload(packageName: String): ExportPayload {
        val appInfo = applicationInfo(packageName)
        val apkFiles = collectApkFiles(appInfo)
        if (apkFiles.isEmpty()) {
            error("No APK files found for package=$packageName")
        }

        val archiveBytes = ByteArrayOutputStream().use { byteArray ->
            ZipOutputStream(BufferedOutputStream(byteArray)).use { zip ->
                apkFiles.forEachIndexed { index, file ->
                    val entryName = if (index == 0) {
                        "base.apk"
                    } else {
                        sanitizeApkName(file.name, index)
                    }

                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
            byteArray.toByteArray()
        }

        val appLabel = appInfo.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() }
        return ExportPayload(appLabel = appLabel, archiveBytes = archiveBytes)
    }

    private fun collectApkFiles(appInfo: ApplicationInfo): List<File> {
        val paths = mutableListOf<String>()
        appInfo.sourceDir?.let(paths::add)
        appInfo.splitSourceDirs?.let(paths::addAll)

        return paths
            .distinct()
            .map { File(it) }
            .filter { it.exists() && it.isFile }
    }

    private fun estimateApkSize(appInfo: ApplicationInfo): Long {
        return collectApkFiles(appInfo).sumOf { it.length().coerceAtLeast(0L) }
    }

    @Suppress("DEPRECATION")
    private fun queryLauncherActivities(intent: Intent): List<ResolveInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            packageManager.getPackageInfo(packageName, 0)
        }
    }

    @Suppress("DEPRECATION")
    private fun applicationInfo(packageName: String): ApplicationInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            packageManager.getApplicationInfo(packageName, 0)
        }
    }

    private fun readableVersionCode(info: PackageInfo): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toString()
        }
    }

    private fun sanitizeApkName(rawName: String, index: Int): String {
        val base = rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "split-$index.apk" }

        return if (base.lowercase(Locale.US).endsWith(".apk")) {
            base
        } else {
            "$base.apk"
        }
    }

    private fun sanitizeLogValue(raw: String): String {
        return raw.replace('\n', ' ').replace('\r', ' ').trim()
    }

    private fun ByteArray.decodeUtf8(): String = String(this, Charsets.UTF_8)

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
