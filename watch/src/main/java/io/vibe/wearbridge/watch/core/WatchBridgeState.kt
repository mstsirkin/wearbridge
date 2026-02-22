package io.vibe.wearbridge.watch.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WatchBridgeState {
    private const val LOG_TAG = "WearBridgeWatch"
    private const val LOG_LIMIT = 200

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun log(message: String) {
        val clean = message.replace('\n', ' ').replace('\r', ' ').trim()
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$stamp  $clean"
        _logs.update { current ->
            val updated = current + line
            if (updated.size > LOG_LIMIT) updated.takeLast(LOG_LIMIT) else updated
        }
        Log.i(LOG_TAG, clean)
    }
}
