package io.vibe.wearbridge.files

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long
)

object FileSelection {
    suspend fun resolve(context: Context, uris: List<Uri>): List<SelectedFile> = withContext(Dispatchers.IO) {
        uris.map { uri ->
            val (name, size) = queryMetadata(context.contentResolver, uri)
            SelectedFile(uri = uri, name = name, sizeBytes = if (size < 0) 0 else size)
        }
    }

    suspend fun inferPackageName(context: Context, selectedFiles: List<SelectedFile>): String? =
        withContext(Dispatchers.IO) {
            val apkFiles = selectedFiles.filter { it.name.lowercase(Locale.ROOT).endsWith(".apk") }
                .sortedByDescending { it.name.equals("base.apk", ignoreCase = true) }

            for (file in apkFiles) {
                val packageName = parsePackageFromApkUri(context, file.uri)
                if (!packageName.isNullOrBlank()) return@withContext packageName
            }

            val archiveFiles = selectedFiles.filter {
                val lower = it.name.lowercase(Locale.ROOT)
                lower.endsWith(".apks") || lower.endsWith(".zip")
            }

            for (file in archiveFiles) {
                val packageName = parsePackageFromArchive(context, file.uri)
                if (!packageName.isNullOrBlank()) return@withContext packageName
            }

            null
        }

    private fun queryMetadata(contentResolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var fileName = uri.lastPathSegment ?: "file"
        var fileSize = -1L

        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex) ?: fileName
                    }

                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }

        if (fileSize < 0) {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                fileSize = pfd.statSize
            }
        }

        return fileName to fileSize
    }

    private fun parsePackageFromApkUri(context: Context, uri: Uri): String? {
        val tempApk = File.createTempFile("wearbridge_pkg_", ".apk", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempApk.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            packageNameFromArchiveApk(context, tempApk)
        } finally {
            tempApk.delete()
        }
    }

    private fun parsePackageFromArchive(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        resolver.openInputStream(uri)?.use { rawInput ->
            ZipInputStream(BufferedInputStream(rawInput)).use { zipInput ->
                var fallbackApkBytes: ByteArray? = null
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.lowercase(Locale.ROOT).endsWith(".apk")) {
                        val bytes = zipInput.readBytes()
                        val isBase = entry.name.substringAfterLast('/').equals("base.apk", ignoreCase = true)
                        if (isBase) {
                            return packageNameFromApkBytes(context, bytes)
                        }
                        if (fallbackApkBytes == null) {
                            fallbackApkBytes = bytes
                        }
                    }
                    zipInput.closeEntry()
                }

                if (fallbackApkBytes != null) {
                    return packageNameFromApkBytes(context, fallbackApkBytes)
                }
            }
        }

        return null
    }

    private fun packageNameFromApkBytes(context: Context, bytes: ByteArray): String? {
        val tempApk = File.createTempFile("wearbridge_extract_", ".apk", context.cacheDir)
        return try {
            tempApk.writeBytes(bytes)
            packageNameFromArchiveApk(context, tempApk)
        } finally {
            tempApk.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun packageNameFromArchiveApk(context: Context, apkFile: File): String? {
        val pm = context.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
        }
        return packageInfo?.packageName
    }
}
