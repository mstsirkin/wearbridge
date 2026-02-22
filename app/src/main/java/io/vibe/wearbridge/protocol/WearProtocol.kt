package io.vibe.wearbridge.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

object WearProtocol {
    const val SYNC_REQUEST_PATH = "/request-sync"
    const val REGISTRY_PATH = "/app-registry"
    const val APP_DATA_PATH_PREFIX = "/app/"
    const val LOG_MESSAGE_PATH = "/log-message"
    const val DELETE_APP_PATH = "/delete-app"

    const val REQUEST_APK_PATH = "/request-apk"
    const val EXPORTED_APK_PATH = "/apk-export"
    const val REQUEST_SCREENSHOT_PATH = "/request-screenshot"
    const val SCREENSHOT_EXPORT_PATH = "/screenshot-export"

    const val APP_LIST_START_PATH = "/app-list-start"
    const val APP_LIST_CHUNK_PATH = "/app-list-chunk"
    const val APP_LIST_END_PATH = "/app-list-end"

    const val CHECK_COMPANION_PATH = "/check-companion"
    const val CHECK_COMPANION_RESPONSE_PATH = "/check-companion-response"
    const val CHECK_CAPABILITIES_PATH = "/check-capabilities"
    const val CHECK_CAPABILITIES_RESPONSE_PATH = "/check-capabilities-response"

    const val INSTALL_DATA_PATH_PREFIX = "/apk/"

    const val KEY_PACKAGE_NAME = "package_name"
    const val KEY_APP_LABEL = "app_label"
    const val KEY_APK_COUNT = "apk_count"
    const val KEY_APK_FILE_ASSET = "apk_file"
    const val KEY_SCREENSHOT_FILE_ASSET = "screenshot_file"
    const val KEY_REQUEST_ID = "request_id"
    const val KEY_MIME_TYPE = "mime_type"
    const val KEY_CAPTURE_TIMESTAMP = "capture_timestamp"
    const val KEY_IMAGE_WIDTH = "width"
    const val KEY_IMAGE_HEIGHT = "height"
}

fun indexedApkAssetKey(index: Int): String = "apk_$index"
fun indexedApkNameKey(index: Int): String = "file_name_$index"
fun indexedApkSizeKey(index: Int): String = "apk_size_$index"

@Serializable
data class CompanionInfo(
    val versionName: String,
    val versionCode: Int
)

@Serializable
data class ScreenshotRequest(
    @SerialName("request_id")
    val requestId: String? = null,
    val source: String? = null
)

@Serializable
data class CapabilityCheckRequest(
    @SerialName("request_id")
    val requestId: String? = null,
    val features: List<String>? = null
)

@Serializable
data class CapabilityReport(
    @SerialName("request_id")
    val requestId: String? = null,
    @SerialName("protocol_version")
    val protocolVersion: Int? = null,
    val capabilities: Map<String, CapabilityStatus>? = null
)

@Serializable
data class CapabilityStatus(
    val supported: Boolean,
    val ready: Boolean,
    val method: String? = null,
    @SerialName("missing_permissions")
    val missingPermissions: List<String>? = null,
    @SerialName("missing_requirements")
    val missingRequirements: List<String>? = null,
    @SerialName("required_user_actions")
    val requiredUserActions: List<String>? = null,
    val details: JsonObject? = null
)

@Serializable
data class RemoteAppInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val icon: ByteArray?,
    val size: Long,
    val installTime: Long
)

data class WearAppRecord(
    val packageName: String,
    val label: String,
    val versionName: String,
    val size: Long,
    val installTime: Long,
    val iconBytes: ByteArray?
)
