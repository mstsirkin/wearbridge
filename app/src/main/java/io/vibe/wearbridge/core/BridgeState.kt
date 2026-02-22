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

    private val chunkMutex = Mutex()
    private val chunkBuffer = mutableListOf<RemoteAppInfo>()
    @Volatile
    private var activeInstallSessionId: String? = null

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
        activeInstallSessionId = sessionId
        val suffix = metadata?.let { " $it" } ?: ""
        emit("session=$sessionId state=session_started$suffix", sessionId)
    }

    fun isSessionActive(sessionId: String): Boolean {
        return activeInstallSessionId == sessionId
    }

    fun logSessionState(sessionId: String, state: String, details: String? = null) {
        val suffix = details?.let { " $it" } ?: ""
        emit("session=$sessionId state=$state$suffix", sessionId)
    }

    fun endInstallSession(sessionId: String? = activeInstallSessionId, reason: String? = null) {
        if (sessionId == null) return
        if (activeInstallSessionId == sessionId) {
            activeInstallSessionId = null
        }
        val suffix = reason?.let { " reason=$it" } ?: ""
        emit("session=$sessionId state=session_finished$suffix", sessionId)
    }

    fun logWatchMessage(message: String) {
        val clean = message.replace('\n', ' ').replace('\r', ' ')
        val sessionId = activeInstallSessionId
        if (sessionId != null) {
            emit("session=$sessionId watch=\"$clean\"", sessionId)
            if (isLikelyTerminalWatchMessage(clean)) {
                logSessionState(sessionId, "watch_terminal")
                endInstallSession(sessionId, "watch_terminal")
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
