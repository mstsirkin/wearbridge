package io.vibe.wearbridge.core

import android.content.Context
import java.io.File

object ProgressLogFile {
    private const val SESSIONS_DIR_NAME = "WearBridgeSessions"
    private const val GLOBAL_LOG_FILE_NAME = "bridge-progress.log"
    private const val SESSION_LOG_FILE_NAME = "progress.log"

    @Volatile
    private var sessionsBaseDir: File? = null
    @Volatile
    private var globalLogFile: File? = null

    fun init(context: Context) {
        synchronized(this) {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val sessionsDir = File(baseDir, SESSIONS_DIR_NAME)
            if (!sessionsDir.exists()) {
                sessionsDir.mkdirs()
            }

            val file = File(sessionsDir, GLOBAL_LOG_FILE_NAME)
            if (!file.exists()) {
                file.createNewFile()
            }
            sessionsBaseDir = sessionsDir
            globalLogFile = file
        }
    }

    fun pathOrNull(): String? = globalLogFile?.absolutePath

    fun appendLine(line: String) {
        val file = globalLogFile ?: return
        synchronized(this) {
            runCatching {
                file.appendText(line + "\n")
            }
        }
    }

    fun appendSessionLine(sessionId: String, line: String) {
        val base = sessionsBaseDir ?: return
        synchronized(this) {
            runCatching {
                val sessionDir = File(base, sessionId)
                if (!sessionDir.exists()) {
                    sessionDir.mkdirs()
                }
                val sessionLog = File(sessionDir, SESSION_LOG_FILE_NAME)
                if (!sessionLog.exists()) {
                    sessionLog.createNewFile()
                }
                sessionLog.appendText(line + "\n")
            }
        }
    }
}
