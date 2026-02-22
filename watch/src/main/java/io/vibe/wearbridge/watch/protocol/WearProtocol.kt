package io.vibe.wearbridge.watch.protocol

import kotlinx.serialization.Serializable

object WearProtocol {
    const val SYNC_REQUEST_PATH = "/request-sync"
    const val LOG_MESSAGE_PATH = "/log-message"
    const val DELETE_APP_PATH = "/delete-app"

    const val REQUEST_APK_PATH = "/request-apk"
    const val EXPORTED_APK_PATH = "/apk-export"

    const val APP_LIST_START_PATH = "/app-list-start"
    const val APP_LIST_CHUNK_PATH = "/app-list-chunk"
    const val APP_LIST_END_PATH = "/app-list-end"

    const val CHECK_COMPANION_PATH = "/check-companion"
    const val CHECK_COMPANION_RESPONSE_PATH = "/check-companion-response"

    const val INSTALL_DATA_PATH_PREFIX = "/apk/"

    const val KEY_PACKAGE_NAME = "package_name"
    const val KEY_APP_LABEL = "app_label"
    const val KEY_APK_COUNT = "apk_count"
    const val KEY_APK_FILE_ASSET = "apk_file"
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
data class RemoteAppInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val icon: ByteArray?,
    val size: Long,
    val installTime: Long
)
