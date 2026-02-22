package io.vibe.wearbridge.watch.screenshot

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import io.vibe.wearbridge.watch.core.WatchBridgeState
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

data class ScreenshotCapturePayload(
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
    val captureTimestampMillis: Long
)

sealed interface ScreenshotCaptureResult {
    data class Success(val payload: ScreenshotCapturePayload) : ScreenshotCaptureResult
    data class Failure(val code: String, val message: String) : ScreenshotCaptureResult
}

object WatchScreenshotController {
    @Volatile
    private var activeService: WatchScreenshotAccessibilityService? = null

    fun supportsScreenshot(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun isReady(): Boolean = supportsScreenshot() && activeService != null

    fun method(): String = if (supportsScreenshot()) "accessibility" else "none"

    fun onServiceConnected(service: WatchScreenshotAccessibilityService) {
        activeService = service
        WatchBridgeState.log("Screenshot accessibility service connected")
    }

    fun onServiceDisconnected(service: WatchScreenshotAccessibilityService) {
        if (activeService === service) {
            activeService = null
            WatchBridgeState.log("Screenshot accessibility service disconnected")
        }
    }

    suspend fun capture(): ScreenshotCaptureResult {
        if (!supportsScreenshot()) {
            return ScreenshotCaptureResult.Failure(
                code = "api_unsupported",
                message = "accessibility_screenshot_api_requires_android_11"
            )
        }
        val service = activeService ?: return ScreenshotCaptureResult.Failure(
            code = "accessibility_service_disabled",
            message = "enable_accessibility_service"
        )
        return service.capturePngScreenshot()
    }
}

class WatchScreenshotAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        WatchScreenshotController.onServiceConnected(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        WatchScreenshotController.onServiceDisconnected(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        WatchScreenshotController.onServiceDisconnected(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    suspend fun capturePngScreenshot(): ScreenshotCaptureResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenshotCaptureResult.Failure(
                code = "api_unsupported",
                message = "accessibility_screenshot_api_requires_android_11"
            )
        }
        return capturePngScreenshotApi30()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun capturePngScreenshotApi30(): ScreenshotCaptureResult {
        return suspendCancellableCoroutine { cont ->
            runCatching {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    ContextCompat.getMainExecutor(this),
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val result = runCatching {
                                val hardwareBuffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                    ?: error("wrap_hardware_buffer_failed")
                                val outputBitmap = if (hardwareBitmap.config == Bitmap.Config.HARDWARE) {
                                    hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                        ?: error("bitmap_copy_failed")
                                } else {
                                    hardwareBitmap
                                }
                                try {
                                    val bytes = ByteArrayOutputStream().use { out ->
                                        check(outputBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                                            "png_compress_failed"
                                        }
                                        out.toByteArray()
                                    }
                                    ScreenshotCaptureResult.Success(
                                        ScreenshotCapturePayload(
                                            pngBytes = bytes,
                                            width = outputBitmap.width,
                                            height = outputBitmap.height,
                                            captureTimestampMillis = System.currentTimeMillis()
                                        )
                                    )
                                } finally {
                                    if (outputBitmap !== hardwareBitmap) {
                                        outputBitmap.recycle()
                                    }
                                    hardwareBitmap.recycle()
                                    hardwareBuffer.close()
                                }
                            }.getOrElse { error ->
                                ScreenshotCaptureResult.Failure(
                                    code = "capture_failed",
                                    message = error.message ?: "capture_failed"
                                )
                            }
                            if (cont.isActive) {
                                cont.resume(result)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) {
                                cont.resume(
                                    ScreenshotCaptureResult.Failure(
                                        code = "capture_failed",
                                        message = "takeScreenshot_error_$errorCode"
                                    )
                                )
                            }
                        }
                    }
                )
            }.onFailure { error ->
                if (cont.isActive) {
                    cont.resume(
                        ScreenshotCaptureResult.Failure(
                            code = "capture_failed",
                            message = error.message ?: "takeScreenshot_invocation_failed"
                        )
                    )
                }
            }
        }
    }
}
