package io.vibe.wearbridge

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.Node
import io.vibe.wearbridge.core.BridgeState
import io.vibe.wearbridge.core.MessagePasswordStore
import io.vibe.wearbridge.core.UploadProgress
import io.vibe.wearbridge.core.WearBridgeClient
import io.vibe.wearbridge.files.FileSelection
import io.vibe.wearbridge.files.SelectedFile
import io.vibe.wearbridge.protocol.CapabilityCheckRequest
import io.vibe.wearbridge.protocol.ScreenshotRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val client = WearBridgeClient(application)
    private val passwordStore = MessagePasswordStore(application)

    val logs = BridgeState.logs
    val apps = BridgeState.apps
    val companionInfo = BridgeState.companionInfo
    val capabilityReport = BridgeState.capabilityReport

    private val _connectedNodes = MutableStateFlow<List<Node>>(emptyList())
    val connectedNodes: StateFlow<List<Node>> = _connectedNodes.asStateFlow()

    private val _selectedFiles = MutableStateFlow<List<SelectedFile>>(emptyList())
    val selectedFiles: StateFlow<List<SelectedFile>> = _selectedFiles.asStateFlow()

    private val _packageNameInput = MutableStateFlow("")
    val packageNameInput: StateFlow<String> = _packageNameInput.asStateFlow()

    private val _messagePassword = MutableStateFlow(passwordStore.getPassword().orEmpty())
    val messagePassword: StateFlow<String> = _messagePassword.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _uploadProgress = MutableStateFlow<UploadProgress?>(null)
    val uploadProgress: StateFlow<UploadProgress?> = _uploadProgress.asStateFlow()

    init {
        refreshNodes()
    }

    fun setPackageNameInput(value: String) {
        _packageNameInput.value = value
    }

    fun setMessagePassword(value: String) {
        _messagePassword.value = value
        passwordStore.setPassword(value)
    }

    fun clearMessagePassword() {
        _messagePassword.value = ""
        passwordStore.setPassword(null)
    }

    fun clearSelectedFiles() {
        _selectedFiles.value = emptyList()
        _uploadProgress.value = null
    }

    fun refreshNodes() {
        launchBusy {
            val nodes = client.connectedNodes()
            _connectedNodes.value = nodes
            BridgeState.log("Connected watches: ${nodes.size}")
        }
    }

    fun requestSync() {
        launchBusy {
            val sent = client.requestAppSync(password = currentGuiPassword())
            if (sent == 0) {
                BridgeState.log("No watch connected for sync request")
            } else {
                BridgeState.log("Sync request sent to $sent watch node(s)")
            }
        }
    }

    fun checkCompanion() {
        launchBusy {
            val sent = client.requestCompanionInfo(password = currentGuiPassword())
            if (sent == 0) {
                BridgeState.log("No watch connected for companion check")
            } else {
                BridgeState.log("Companion check sent to $sent watch node(s)")
                probeCapabilitiesWithFallback()
            }
        }
    }

    fun requestWatchScreenshot() {
        launchBusy {
            sendScreenshotRequest(
                sessionId = null,
                requestId = null,
                source = "phone_ui",
                passwordOverride = currentGuiPassword(),
                requireExplicitPassword = false
            )
        }
    }

    fun requestWatchScreenshotFromIntent(
        sessionId: String?,
        requestId: String?,
        source: String?,
        password: String?
    ) {
        launchBusy {
            sendScreenshotRequest(
                sessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() },
                requestId = requestId?.trim()?.takeIf { it.isNotEmpty() },
                source = source?.trim()?.takeIf { it.isNotEmpty() } ?: "adb",
                passwordOverride = password,
                requireExplicitPassword = true
            )
        }
    }

    fun requestExport(packageName: String) {
        launchBusy {
            val sent = client.requestApkExport(packageName, password = currentGuiPassword())
            if (sent == 0) {
                BridgeState.log("No watch connected for export request")
            } else {
                BridgeState.log("Export requested for $packageName")
            }
        }
    }

    fun requestDelete(packageName: String) {
        launchBusy {
            val sent = client.requestDelete(packageName, password = currentGuiPassword())
            if (sent == 0) {
                BridgeState.log("No watch connected for delete request")
            } else {
                BridgeState.log("Delete requested for $packageName")
            }
        }
    }

    fun onFilesPicked(
        uris: List<Uri>,
        autoSend: Boolean = false,
        packageNameOverride: String? = null,
        sessionId: String? = null,
        autoSendPasswordOverride: String? = null,
        requireExplicitAutoSendPassword: Boolean = false
    ) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            val context = getApplication<Application>()
            val normalizedSessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() }
            if (normalizedSessionId != null) {
                BridgeState.beginInstallSession(normalizedSessionId, "source=phone_intent")
            }

            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }

            runCatching {
                val files = FileSelection.resolve(context, uris)
                _selectedFiles.value = files
                _uploadProgress.value = null
                BridgeState.log("Selected ${files.size} file(s)")
                if (normalizedSessionId != null) {
                    BridgeState.logSessionState(
                        normalizedSessionId,
                        "files_selected",
                        "count=${files.size}"
                    )
                }

                val guessed = FileSelection.inferPackageName(context, files)
                val resolvedPackage = when {
                    !packageNameOverride.isNullOrBlank() -> packageNameOverride.trim()
                    !guessed.isNullOrBlank() -> guessed
                    else -> ""
                }

                if (resolvedPackage.isNotBlank()) {
                    _packageNameInput.value = resolvedPackage
                    if (!packageNameOverride.isNullOrBlank()) {
                        BridgeState.log("Using package override: $resolvedPackage")
                        if (normalizedSessionId != null) {
                            BridgeState.logSessionState(
                                normalizedSessionId,
                                "package_override",
                                "package=$resolvedPackage"
                            )
                        }
                    } else {
                        BridgeState.log("Detected package name: $resolvedPackage")
                        if (normalizedSessionId != null) {
                            BridgeState.logSessionState(
                                normalizedSessionId,
                                "package_detected",
                                "package=$resolvedPackage"
                            )
                        }
                    }
                } else {
                    BridgeState.log("Package name not detected automatically")
                    if (normalizedSessionId != null) {
                        BridgeState.logSessionState(
                            normalizedSessionId,
                            "package_missing"
                        )
                    }
                }

                if (autoSend) {
                    val resolvedAutoSendPassword = when {
                        requireExplicitAutoSendPassword -> {
                            autoSendPasswordOverride?.takeIf { it.isNotBlank() }
                        }
                        else -> autoSendPasswordOverride?.takeIf { it.isNotBlank() } ?: currentGuiPassword()
                    }
                    if (resolvedPackage.isBlank()) {
                        BridgeState.log("Auto-send skipped: package name is required")
                        if (normalizedSessionId != null) {
                            BridgeState.logSessionState(
                                normalizedSessionId,
                                "auto_send_skipped",
                                "reason=no_package_name"
                            )
                            BridgeState.endInstallSession(normalizedSessionId, "no_package_name")
                        }
                        return@runCatching
                    }
                    _busy.value = true
                    _uploadProgress.value = UploadProgress(percent = 0, stage = "starting")
                    if (normalizedSessionId != null) {
                        BridgeState.logSessionState(
                            normalizedSessionId,
                            "auto_send_started",
                            "package=$resolvedPackage count=${files.size}"
                        )
                    }
                    runCatching {
                        client.sendInstallData(
                            packageName = resolvedPackage,
                            selectedFiles = files,
                            password = resolvedAutoSendPassword,
                            onProgress = { progress ->
                                reportUploadProgress(normalizedSessionId, progress)
                            }
                        )
                        BridgeState.log(
                            "Auto-send queued (${files.size} file(s)) for $resolvedPackage"
                        )
                        if (normalizedSessionId != null) {
                            BridgeState.logSessionState(
                                normalizedSessionId,
                                "auto_send_queued",
                                "package=$resolvedPackage count=${files.size}"
                            )
                        }
                    }.onFailure { error ->
                        BridgeState.log("Auto-send failed: ${error.message}")
                        _uploadProgress.value = null
                        if (normalizedSessionId != null) {
                            BridgeState.logSessionState(
                                normalizedSessionId,
                                "auto_send_failed",
                                "error=${error.message ?: "unknown"}"
                            )
                            BridgeState.endInstallSession(normalizedSessionId, "auto_send_failed")
                        }
                    }
                    _busy.value = false
                } else if (normalizedSessionId != null) {
                    BridgeState.logSessionState(
                        normalizedSessionId,
                        "awaiting_manual_send"
                    )
                }
            }.onFailure { error ->
                BridgeState.log("Failed to process selected files: ${error.message}")
                if (normalizedSessionId != null) {
                    BridgeState.logSessionState(
                        normalizedSessionId,
                        "processing_failed",
                        "error=${error.message ?: "unknown"}"
                    )
                    BridgeState.endInstallSession(normalizedSessionId, "processing_failed")
                }
            }
        }
    }

    fun installSelectedFiles() {
        val files = _selectedFiles.value
        val packageName = _packageNameInput.value.trim()

        if (files.isEmpty()) {
            BridgeState.log("Select files first")
            return
        }

        if (packageName.isBlank()) {
            BridgeState.log("Package name is required")
            return
        }

        viewModelScope.launch {
            _busy.value = true
            _uploadProgress.value = UploadProgress(percent = 0, stage = "starting")
            runCatching {
                client.sendInstallData(
                    packageName = packageName,
                    selectedFiles = files,
                    password = currentGuiPassword(),
                    onProgress = { progress ->
                        reportUploadProgress(null, progress)
                    }
                )
                BridgeState.log("Install payload queued (${files.size} file(s)) for $packageName")
            }.onFailure { error ->
                _uploadProgress.value = null
                BridgeState.log("Operation failed: ${error.message}")
            }
            _busy.value = false
        }
    }

    private fun reportUploadProgress(sessionId: String?, progress: UploadProgress) {
        _uploadProgress.value = progress
        val detail = buildString {
            append("percent=${progress.percent}")
            append(" stage=${progress.stage}")
            if (!progress.details.isNullOrBlank()) {
                append(" ")
                append(progress.details)
            }
        }

        if (!sessionId.isNullOrBlank()) {
            BridgeState.logSessionState(sessionId, "upload_progress", detail)
        } else {
            BridgeState.log("Upload progress: $detail")
        }
    }

    private suspend fun probeCapabilitiesWithFallback() {
        val requestId = "caps-${System.currentTimeMillis()}"
        val request = CapabilityCheckRequest(
            requestId = requestId,
            features = listOf("screenshot"),
            password = currentGuiPassword()
        )

        val sent = runCatching {
            client.requestCapabilities(request)
        }.getOrElse { error ->
            BridgeState.log("Capability probe failed to send: ${error.message}")
            return
        }

        if (sent == 0) {
            BridgeState.log("Capability probe skipped: no watch connected")
            return
        }

        BridgeState.log("Capability probe sent to $sent watch node(s)")

        val matched = withTimeoutOrNull(1500) {
            BridgeState.capabilityReport.first { it?.requestId == requestId }
        }

        if (matched == null) {
            BridgeState.log("Capability report unavailable (legacy watch or timeout)")
        }
    }

    private suspend fun sendScreenshotRequest(
        sessionId: String?,
        requestId: String?,
        source: String?,
        passwordOverride: String?,
        requireExplicitPassword: Boolean
    ) {
        val normalizedSessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedRequestId = (requestId ?: normalizedSessionId)?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedSource = source?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedPassword = if (requireExplicitPassword) {
            passwordOverride?.takeIf { it.isNotBlank() }
        } else {
            passwordOverride?.takeIf { it.isNotBlank() } ?: currentGuiPassword()
        }

        if (normalizedSessionId != null) {
            BridgeState.beginScreenshotSession(
                sessionId = normalizedSessionId,
                requestId = normalizedRequestId,
                metadata = normalizedSource?.let { "source=$it" }
            )
            BridgeState.logSessionState(
                normalizedSessionId,
                "screenshot_request_started",
                normalizedRequestId?.let { "request_id=$it" }
            )
        }

        val request = ScreenshotRequest(
            requestId = normalizedRequestId,
            source = normalizedSource,
            password = resolvedPassword
        )

        val sent = runCatching {
            client.requestScreenshot(request)
        }.getOrElse { error ->
            if (normalizedSessionId != null) {
                BridgeState.onScreenshotRequestFailed(
                    reasonCode = "send_exception",
                    details = "error=${error.message ?: "unknown"}",
                    requestId = normalizedRequestId
                )
            } else {
                BridgeState.log("Screenshot request failed: ${error.message}")
            }
            return
        }

        if (sent == 0) {
            if (normalizedSessionId != null) {
                BridgeState.onScreenshotRequestFailed(
                    reasonCode = "no_watch_connected",
                    details = null,
                    requestId = normalizedRequestId
                )
            } else {
                BridgeState.log("No watch connected for screenshot request")
            }
            return
        }

        if (normalizedSessionId != null) {
            BridgeState.onScreenshotRequestSent(
                requestId = normalizedRequestId,
                sentNodes = sent
            )
        }
        BridgeState.log("Screenshot request sent to $sent watch node(s)")
    }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { block() }
                .onFailure { error ->
                    BridgeState.log("Operation failed: ${error.message}")
                }
            _busy.value = false
        }
    }

    private fun currentGuiPassword(): String? {
        return _messagePassword.value.takeIf { it.isNotBlank() }
    }
}
