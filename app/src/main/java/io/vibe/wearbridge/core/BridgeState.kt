package io.vibe.wearbridge.core

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

    private val chunkMutex = Mutex()
    private val chunkBuffer = mutableListOf<RemoteAppInfo>()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _apps = MutableStateFlow<List<WearAppRecord>>(emptyList())
    val apps: StateFlow<List<WearAppRecord>> = _apps.asStateFlow()

    private val _companionInfo = MutableStateFlow<CompanionInfo?>(null)
    val companionInfo: StateFlow<CompanionInfo?> = _companionInfo.asStateFlow()

    fun log(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$stamp  $message"
        _logs.update { current ->
            val updated = current + line
            if (updated.size > LOG_LIMIT) updated.takeLast(LOG_LIMIT) else updated
        }
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
}
