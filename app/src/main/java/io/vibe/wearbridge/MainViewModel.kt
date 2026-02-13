package io.vibe.wearbridge

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.Node
import io.vibe.wearbridge.core.BridgeState
import io.vibe.wearbridge.core.WearBridgeClient
import io.vibe.wearbridge.files.FileSelection
import io.vibe.wearbridge.files.SelectedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val client = WearBridgeClient(application)

    val logs = BridgeState.logs
    val apps = BridgeState.apps
    val companionInfo = BridgeState.companionInfo

    private val _connectedNodes = MutableStateFlow<List<Node>>(emptyList())
    val connectedNodes: StateFlow<List<Node>> = _connectedNodes.asStateFlow()

    private val _selectedFiles = MutableStateFlow<List<SelectedFile>>(emptyList())
    val selectedFiles: StateFlow<List<SelectedFile>> = _selectedFiles.asStateFlow()

    private val _packageNameInput = MutableStateFlow("")
    val packageNameInput: StateFlow<String> = _packageNameInput.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        refreshNodes()
    }

    fun setPackageNameInput(value: String) {
        _packageNameInput.value = value
    }

    fun clearSelectedFiles() {
        _selectedFiles.value = emptyList()
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
            val sent = client.requestAppSync()
            if (sent == 0) {
                BridgeState.log("No watch connected for sync request")
            } else {
                BridgeState.log("Sync request sent to $sent watch node(s)")
            }
        }
    }

    fun checkCompanion() {
        launchBusy {
            val sent = client.requestCompanionInfo()
            if (sent == 0) {
                BridgeState.log("No watch connected for companion check")
            } else {
                BridgeState.log("Companion check sent to $sent watch node(s)")
            }
        }
    }

    fun requestExport(packageName: String) {
        launchBusy {
            val sent = client.requestApkExport(packageName)
            if (sent == 0) {
                BridgeState.log("No watch connected for export request")
            } else {
                BridgeState.log("Export requested for $packageName")
            }
        }
    }

    fun requestDelete(packageName: String) {
        launchBusy {
            val sent = client.requestDelete(packageName)
            if (sent == 0) {
                BridgeState.log("No watch connected for delete request")
            } else {
                BridgeState.log("Delete requested for $packageName")
            }
        }
    }

    fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            val context = getApplication<Application>()

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
                BridgeState.log("Selected ${files.size} file(s)")

                val guessed = FileSelection.inferPackageName(context, files)
                if (!guessed.isNullOrBlank()) {
                    _packageNameInput.value = guessed
                    BridgeState.log("Detected package name: $guessed")
                } else {
                    BridgeState.log("Package name not detected automatically")
                }
            }.onFailure { error ->
                BridgeState.log("Failed to process selected files: ${error.message}")
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

        launchBusy {
            client.sendInstallData(packageName, files)
            BridgeState.log("Install payload queued (${files.size} file(s)) for $packageName")
        }
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
}
