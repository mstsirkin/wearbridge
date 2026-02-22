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
import io.vibe.wearbridge.watch.install.InstallResultReceiver
import io.vibe.wearbridge.watch.protocol.CapabilityCheckRequest
import io.vibe.wearbridge.watch.protocol.CapabilityReport
import io.vibe.wearbridge.watch.protocol.CapabilityStatus
import io.vibe.wearbridge.watch.protocol.CompanionInfo
import io.vibe.wearbridge.watch.protocol.RemoteAppInfo
import io.vibe.wearbridge.watch.protocol.WearProtocol
import io.vibe.wearbridge.watch.protocol.indexedApkAssetKey
import io.vibe.wearbridge.watch.protocol.indexedApkNameKey
import io.vibe.wearbridge.watch.protocol.indexedApkSizeKey
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
                handleSyncRequest(messageEvent.sourceNodeId)
            }

            WearProtocol.CHECK_COMPANION_PATH -> {
                handleCompanionCheck(messageEvent.sourceNodeId)
            }

            WearProtocol.CHECK_CAPABILITIES_PATH -> {
                handleCapabilitiesCheck(
                    sourceNodeId = messageEvent.sourceNodeId,
                    payloadBytes = messageEvent.data
                )
            }

            WearProtocol.REQUEST_APK_PATH -> {
                val packageName = messageEvent.data.decodeUtf8().trim()
                handleExportRequest(packageName)
            }

            WearProtocol.DELETE_APP_PATH -> {
                val packageName = messageEvent.data.decodeUtf8().trim()
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

    private suspend fun handleCapabilitiesCheck(sourceNodeId: String, payloadBytes: ByteArray) {
        val request = parseCapabilityCheckRequest(payloadBytes)
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

    private fun buildCapabilityReport(request: CapabilityCheckRequest?): CapabilityReport {
        val requested = request?.features?.toSet().orEmpty()
        val includeScreenshot = requested.isEmpty() || "screenshot" in requested

        val screenshotStatus = if (includeScreenshot) {
            CapabilityStatus(
                supported = false,
                ready = false,
                method = "none",
                missingPermissions = emptyList(),
                missingRequirements = listOf("feature_not_built"),
                requiredUserActions = listOf("update_watch_app"),
                details = buildJsonObject {
                    put("api_level", Build.VERSION.SDK_INT)
                    put("watch_sdk_int", Build.VERSION.SDK_INT)
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
        val apkCount = dataMap.getInt(WearProtocol.KEY_APK_COUNT)
        val installDir = File(cacheDir, "install-${System.currentTimeMillis()}").apply { mkdirs() }

        try {
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
