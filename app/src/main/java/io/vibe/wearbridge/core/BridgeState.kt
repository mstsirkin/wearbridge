package io.vibe.wearbridge.core

import android.util.Log
import io.vibe.wearbridge.protocol.CapabilityReport
import io.vibe.wearbridge.protocol.CompanionInfo
import io.vibe.wearbridge.protocol.RemoteAppInfo
import io.vibe.wearbridge.protocol.WearAppRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BridgeState {
    private const val LOG_LIMIT = 300
    private const val LOG_TAG = "WearBridge"

    private enum class SessionKind {
        INSTALL,
        SCREENSHOT
    }

    private val chunkMutex = Mutex()
    private val chunkBuffer = mutableListOf<RemoteAppInfo>()
    @Volatile
    private var activeSessionId: String? = null
    @Volatile
    private var activeSessionKind: SessionKind? = null
    @Volatile
    private var activeScreenshotRequestId: String? = null

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _apps = MutableStateFlow<List<WearAppRecord>>(emptyList())
    val apps: StateFlow<List<WearAppRecord>> = _apps.asStateFlow()

    private val _companionInfo = MutableStateFlow<CompanionInfo?>(null)
    val companionInfo: StateFlow<CompanionInfo?> = _companionInfo.asStateFlow()

    private val _capabilityReport = MutableStateFlow<CapabilityReport?>(null)
    val capabilityReport: StateFlow<CapabilityReport?> = _capabilityReport.asStateFlow()

    fun log(message: String) {
        emit(message, null)
    }

    fun beginInstallSession(sessionId: String, metadata: String? = null) {
        beginSession(
            sessionId = sessionId,
            kind = SessionKind.INSTALL,
            screenshotRequestId = null,
            metadata = metadata
        )
    }

    fun beginScreenshotSession(
        sessionId: String,
        requestId: String? = null,
        metadata: String? = null
    ) {
        val requestPart = requestId?.takeIf { it.isNotBlank() }?.let { "request_id=$it" }
        val combinedMetadata = listOfNotNull(metadata, requestPart).joinToString(" ").ifBlank { null }
        beginSession(
            sessionId = sessionId,
            kind = SessionKind.SCREENSHOT,
            screenshotRequestId = requestId?.takeIf { it.isNotBlank() },
            metadata = combinedMetadata
        )
    }

    fun isSessionActive(sessionId: String): Boolean {
        return activeSessionId == sessionId
    }

    fun logSessionState(sessionId: String, state: String, details: String? = null) {
        val suffix = details?.let { " $it" } ?: ""
        emit("session=$sessionId state=$state$suffix", sessionId)
    }

    fun endInstallSession(sessionId: String? = activeSessionId, reason: String? = null) {
        endSession(sessionId = sessionId, reason = reason)
    }

    fun endScreenshotSession(reason: String, requestId: String? = null) {
        val sessionId = activeSessionId ?: return
        if (activeSessionKind != SessionKind.SCREENSHOT) return
        if (!matchesActiveScreenshotRequest(requestId)) return
        endSession(sessionId = sessionId, reason = reason)
    }

    fun onScreenshotRequestSent(requestId: String? = null, sentNodes: Int? = null) {
        val sessionId = activeSessionId ?: return
        if (activeSessionKind != SessionKind.SCREENSHOT) return
        if (!matchesActiveScreenshotRequest(requestId)) return

        val details = buildString {
            requestId?.takeIf { it.isNotBlank() }?.let {
                append("request_id=")
                append(it)
            }
            sentNodes?.let {
                if (isNotEmpty()) append(' ')
                append("nodes=")
                append(it)
            }
        }.ifBlank { null }
        logSessionState(sessionId, "screenshot_request_sent", details)
    }

    fun onScreenshotRequestFailed(reasonCode: String, details: String? = null, requestId: String? = null) {
        val sessionId = activeSessionId ?: return
        if (activeSessionKind != SessionKind.SCREENSHOT) return
        if (!matchesActiveScreenshotRequest(requestId)) return
        val combined = listOfNotNull(
            requestId?.takeIf { it.isNotBlank() }?.let { "request_id=$it" },
            reasonCode.takeIf { it.isNotBlank() }?.let { "code=$it" },
            details
        ).joinToString(" ").ifBlank { null }
        logSessionState(sessionId, "screenshot_request_failed", combined)
        endSession(sessionId = sessionId, reason = "screenshot_request_failed")
    }

    fun onScreenshotReceived(requestId: String? = null, details: String? = null) {
        val sessionId = activeSessionId ?: return
        if (activeSessionKind != SessionKind.SCREENSHOT) return
        if (!matchesActiveScreenshotRequest(requestId)) return
        val combined = listOfNotNull(
            requestId?.takeIf { it.isNotBlank() }?.let { "request_id=$it" },
            details
        ).joinToString(" ").ifBlank { null }
        logSessionState(sessionId, "screenshot_received", combined)
    }

    fun onScreenshotSaved(
        requestId: String? = null,
        fileName: String? = null,
        uriText: String? = null
    ) {
        val sessionId = activeSessionId ?: return
        if (activeSessionKind != SessionKind.SCREENSHOT) return
        if (!matchesActiveScreenshotRequest(requestId)) return
        val details = listOfNotNull(
            requestId?.takeIf { it.isNotBlank() }?.let { "request_id=$it" },
            fileName?.takeIf { it.isNotBlank() }?.let { "file=$it" },
            uriText?.takeIf { it.isNotBlank() }?.let { "uri=$it" }
        ).joinToString(" ").ifBlank { null }
        logSessionState(sessionId, "screenshot_saved", details)
        endSession(sessionId = sessionId, reason = "screenshot_saved")
    }

    fun onScreenshotSaveFailed(requestId: String? = null, details: String? = null) {
        val sessionId = activeSessionId ?: return
        if (activeSessionKind != SessionKind.SCREENSHOT) return
        if (!matchesActiveScreenshotRequest(requestId)) return
        val combined = listOfNotNull(
            requestId?.takeIf { it.isNotBlank() }?.let { "request_id=$it" },
            details
        ).joinToString(" ").ifBlank { null }
        logSessionState(sessionId, "screenshot_save_failed", combined)
        endSession(sessionId = sessionId, reason = "screenshot_save_failed")
    }

    fun activeScreenshotRequestId(): String? {
        if (activeSessionKind != SessionKind.SCREENSHOT) return null
        return activeScreenshotRequestId
    }

    private fun endSession(sessionId: String?, reason: String? = null) {
        if (sessionId == null) return
        if (activeSessionId == sessionId) {
            activeSessionId = null
            activeSessionKind = null
            activeScreenshotRequestId = null
        }
        val suffix = reason?.let { " reason=$it" } ?: ""
        emit("session=$sessionId state=session_finished$suffix", sessionId)
    }

    fun logWatchMessage(message: String) {
        val clean = message.replace('\n', ' ').replace('\r', ' ')
        val sessionId = activeSessionId
        if (sessionId != null) {
            emit("session=$sessionId watch=\"$clean\"", sessionId)
            if (isLikelyTerminalWatchMessage(clean)) {
                logSessionState(sessionId, "watch_terminal")
                when (activeSessionKind) {
                    SessionKind.INSTALL -> {
                        endSession(sessionId, "watch_terminal")
                    }
                    SessionKind.SCREENSHOT -> {
                        if (isScreenshotWatchFailure(clean)) {
                            onScreenshotRequestFailed(
                                reasonCode = "watch_terminal_failure",
                                details = "message=${sanitizeSessionValue(clean)}",
                                requestId = activeScreenshotRequestId
                            )
                        }
                    }
                    null -> Unit
                }
            }
        } else {
            emit("Watch: $clean", null)
        }
    }

    private fun isLikelyTerminalWatchMessage(message: String): Boolean {
        val value = message.lowercase(Locale.ROOT)
        return value.contains("status_success") ||
            value.contains("status_failure") ||
            value.contains("failure") ||
            value.contains("error") ||
            value.contains("ошиб") ||
            value.contains("успеш") ||
            value.contains("aborted") ||
            value.contains("incompatible") ||
            value.contains("storage")
    }

    suspend fun startChunkTransfer(expectedChunks: Int?) {
        chunkMutex.withLock {
            chunkBuffer.clear()
        }
        if (expectedChunks != null) {
            log("Sync started ($expectedChunks chunks)")
        } else {
            log("Sync started")
        }
    }

    suspend fun appendChunk(chunk: List<RemoteAppInfo>) {
        chunkMutex.withLock {
            chunkBuffer.addAll(chunk)
        }
    }

    suspend fun finishChunkTransfer() {
        val finalized = chunkMutex.withLock {
            val records = chunkBuffer
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase(Locale.ROOT) }
                .map {
                    WearAppRecord(
                        packageName = it.packageName,
                        label = it.label,
                        versionName = it.versionName,
                        size = it.size,
                        installTime = it.installTime,
                        iconBytes = it.icon
                    )
                }
            chunkBuffer.clear()
            records
        }

        _apps.value = finalized
        log("Sync complete: ${finalized.size} apps")
    }

    fun setCompanionInfo(info: CompanionInfo) {
        _companionInfo.value = info
    }

    fun setCapabilityReport(report: CapabilityReport) {
        _capabilityReport.value = report
    }

    private fun beginSession(
        sessionId: String,
        kind: SessionKind,
        screenshotRequestId: String?,
        metadata: String?
    ) {
        activeSessionId = sessionId
        activeSessionKind = kind
        activeScreenshotRequestId = screenshotRequestId
        val suffix = metadata?.let { " $it" } ?: ""
        emit("session=$sessionId state=session_started$suffix", sessionId)
    }

    private fun matchesActiveScreenshotRequest(requestId: String?): Boolean {
        if (activeSessionKind != SessionKind.SCREENSHOT) return false
        val activeRequestId = activeScreenshotRequestId
        if (activeRequestId.isNullOrBlank() || requestId.isNullOrBlank()) return true
        return activeRequestId == requestId
    }

    private fun isScreenshotWatchFailure(message: String): Boolean {
        val value = message.lowercase(Locale.ROOT)
        return value.contains("screenshot_status") && value.contains("status_failure")
    }

    private fun sanitizeSessionValue(raw: String): String {
        return raw
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('"', '\'')
            .trim()
    }

    private fun emit(message: String, _sessionId: String?) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val clean = message.replace('\n', ' ').replace('\r', ' ')
        val line = "$stamp  $clean"
        _logs.update { current ->
            val updated = current + line
            if (updated.size > LOG_LIMIT) updated.takeLast(LOG_LIMIT) else updated
        }
        Log.i(LOG_TAG, clean)
    }
}
