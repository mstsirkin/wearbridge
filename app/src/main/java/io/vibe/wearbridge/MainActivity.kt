package io.vibe.wearbridge

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vibe.wearbridge.core.UploadProgress
import io.vibe.wearbridge.files.SelectedFile
import io.vibe.wearbridge.protocol.CompanionInfo
import io.vibe.wearbridge.protocol.WearAppRecord
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_INSTALL_TO_WATCH = "io.vibe.wearbridge.action.INSTALL_TO_WATCH"
        const val EXTRA_AUTO_SEND = "io.vibe.wearbridge.extra.AUTO_SEND"
        const val EXTRA_PACKAGE_NAME = "io.vibe.wearbridge.extra.PACKAGE_NAME"
        const val EXTRA_FILE_COUNT = "io.vibe.wearbridge.extra.FILE_COUNT"
        const val EXTRA_FILE_URI_PREFIX = "io.vibe.wearbridge.extra.FILE_URI_"
        const val EXTRA_SESSION_ID = "io.vibe.wearbridge.extra.SESSION_ID"
    }

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        setContent {
            MaterialTheme {
                val connectedNodes by viewModel.connectedNodes.collectAsStateWithLifecycle()
                val selectedFiles by viewModel.selectedFiles.collectAsStateWithLifecycle()
                val packageName by viewModel.packageNameInput.collectAsStateWithLifecycle()
                val logs by viewModel.logs.collectAsStateWithLifecycle()
                val apps by viewModel.apps.collectAsStateWithLifecycle()
                val companionInfo by viewModel.companionInfo.collectAsStateWithLifecycle()
                val busy by viewModel.busy.collectAsStateWithLifecycle()
                val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()

                val picker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    viewModel.onFilesPicked(uris)
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text("WearBridge")
                            }
                        )
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                StatusCard(
                                    connectedCount = connectedNodes.size,
                                    companionInfo = companionInfo,
                                    busy = busy
                                )
                            }

                            item {
                                ActionBar(
                                    onRefreshNodes = viewModel::refreshNodes,
                                    onSync = viewModel::requestSync,
                                    onCheckCompanion = viewModel::checkCompanion
                                )
                            }

                            item {
                                FileInstallCard(
                                    selectedFiles = selectedFiles,
                                    packageName = packageName,
                                    onPackageNameChanged = viewModel::setPackageNameInput,
                                    onPickFiles = {
                                        picker.launch(
                                            arrayOf(
                                                "application/vnd.android.package-archive",
                                                "application/zip",
                                                "*/*"
                                            )
                                        )
                                    },
                                    onClear = viewModel::clearSelectedFiles,
                                    onInstall = viewModel::installSelectedFiles,
                                    busy = busy,
                                    uploadProgress = uploadProgress
                                )
                            }

                            item {
                                Text(
                                    text = "Watch Apps (${apps.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            items(items = apps, key = { it.packageName }) { app ->
                                WatchAppCard(
                                    app = app,
                                    onDelete = { viewModel.requestDelete(app.packageName) },
                                    onExport = { viewModel.requestExport(app.packageName) }
                                )
                            }

                            item {
                                HorizontalDivider()
                            }

                            item {
                                Text(
                                    text = "Logs",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            items(logs.takeLast(80).asReversed()) { logLine ->
                                Text(
                                    text = logLine,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val payload = intent ?: return
        val uris = extractIncomingUris(payload)
        val autoSend = payload.getBooleanExtra(
            EXTRA_AUTO_SEND,
            payload.action == ACTION_INSTALL_TO_WATCH
        )
        val packageNameOverride = payload.getStringExtra(EXTRA_PACKAGE_NAME)
        val sessionId = payload.getStringExtra(EXTRA_SESSION_ID)

        if (uris.isNotEmpty()) {
            viewModel.onFilesPicked(
                uris = uris,
                autoSend = autoSend,
                packageNameOverride = packageNameOverride,
                sessionId = sessionId
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun extractIncomingUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()

        when (intent.action) {
            ACTION_INSTALL_TO_WATCH -> {
                val count = intent.getIntExtra(EXTRA_FILE_COUNT, 0)
                if (count > 0) {
                    for (index in 0 until count) {
                        val key = EXTRA_FILE_URI_PREFIX + index
                        intent.getStringExtra(key)?.let { raw ->
                            runCatching { Uri.parse(raw) }.getOrNull()?.let(uris::add)
                        }
                    }
                }
            }

            Intent.ACTION_SEND -> {
                getSingleStreamUri(intent)?.let(uris::add)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                uris.addAll(getMultipleStreamUris(intent))
            }

            Intent.ACTION_VIEW -> {
                intent.data?.let(uris::add)
            }
        }

        val clip = intent.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let(uris::add)
            }
        }

        if (uris.isEmpty()) {
            intent.data?.let(uris::add)
        }

        return uris.distinct()
    }

    @Suppress("DEPRECATION")
    private fun getSingleStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    @Suppress("DEPRECATION")
    private fun getMultipleStreamUris(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM) ?: emptyList()
        }
    }
}

@Composable
private fun StatusCard(
    connectedCount: Int,
    companionInfo: CompanionInfo?,
    busy: Boolean
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Connected watches: $connectedCount")
            if (companionInfo != null) {
                Text("Watch companion: ${companionInfo.versionName} (${companionInfo.versionCode})")
            } else {
                Text("Watch companion: unknown")
            }
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ActionBar(
    onRefreshNodes: () -> Unit,
    onSync: () -> Unit,
    onCheckCompanion: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = onRefreshNodes) { Text("Refresh nodes") }
        Button(onClick = onSync) { Text("Sync apps") }
        OutlinedButton(onClick = onCheckCompanion) { Text("Check companion") }
    }
}

@Composable
private fun FileInstallCard(
    selectedFiles: List<SelectedFile>,
    packageName: String,
    onPackageNameChanged: (String) -> Unit,
    onPickFiles: () -> Unit,
    onClear: () -> Unit,
    onInstall: () -> Unit,
    busy: Boolean,
    uploadProgress: UploadProgress?
) {
    Card(shape = RoundedCornerShape(14.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Install to watch", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFiles) { Text("Select files") }
                if (selectedFiles.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear") }
                }
            }

            if (selectedFiles.isEmpty()) {
                Text("No files selected", style = MaterialTheme.typography.bodySmall)
            } else {
                selectedFiles.forEach { file ->
                    Text(
                        text = "• ${file.name} (${formatBytes(file.sizeBytes)})",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            OutlinedTextField(
                value = packageName,
                onValueChange = onPackageNameChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Package name") },
                placeholder = { Text("com.example.app") }
            )

            Button(
                onClick = onInstall,
                enabled = !busy && selectedFiles.isNotEmpty() && packageName.isNotBlank()
            ) {
                Text("Send install payload")
            }

            if (uploadProgress != null) {
                val percent = uploadProgress.percent.coerceIn(0, 100)
                val progress = percent / 100f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Upload: ${percent}% (${uploadProgress.stage})",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun WatchAppCard(
    app: WearAppRecord,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    val iconBitmap = remember(app.iconBytes) {
        app.iconBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }

    Card(shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color.Transparent, CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${app.versionName} • ${formatBytes(app.size)} • ${formatDate(app.installTime)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onExport) { Text("Export") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private fun formatBytes(size: Long): String {
    if (size <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = size.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return "unknown time"
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
}
