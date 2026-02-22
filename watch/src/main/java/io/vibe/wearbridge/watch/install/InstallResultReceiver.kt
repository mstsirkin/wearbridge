package io.vibe.wearbridge.watch.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import io.vibe.wearbridge.watch.core.PhoneNodeMessenger
import io.vibe.wearbridge.watch.core.WatchBridgeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class InstallResultReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_INSTALL_RESULT = "io.vibe.wearbridge.watch.action.INSTALL_RESULT"
        const val EXTRA_PACKAGE_NAME = "io.vibe.wearbridge.watch.extra.PACKAGE_NAME"

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return

        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                handleResult(context.applicationContext, intent)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleResult(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val statusMessage = sanitizeLogValue(
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        )

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmationIntent = intent.pendingUserActionIntent()
                if (confirmationIntent != null) {
                    confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching {
                        context.startActivity(confirmationIntent)
                    }.onFailure { error ->
                        PhoneNodeMessenger.safeLogToPhone(
                            context,
                            "INSTALL_STATUS status_failure package=$packageName code=$status message=${
                                sanitizeLogValue(error.message ?: "unable_to_launch_confirmation")
                            }"
                        )
                        WatchBridgeState.log("Unable to launch install confirmation: ${error.message}")
                        return
                    }
                }

                PhoneNodeMessenger.safeLogToPhone(
                    context,
                    "INSTALL_PENDING_USER_ACTION message=${
                        if (statusMessage.isBlank()) "user_confirmation_required" else statusMessage
                    }"
                )
                WatchBridgeState.log("Install pending user action for $packageName")
            }

            PackageInstaller.STATUS_SUCCESS -> {
                PhoneNodeMessenger.safeLogToPhone(
                    context,
                    "INSTALL_STATUS status_success package=$packageName code=0 message=${
                        if (statusMessage.isBlank()) "ok" else statusMessage
                    }"
                )
                WatchBridgeState.log("Install success for $packageName")
            }

            else -> {
                PhoneNodeMessenger.safeLogToPhone(
                    context,
                    "INSTALL_STATUS status_failure package=$packageName code=$status message=${
                        if (statusMessage.isBlank()) "installer_status_$status" else statusMessage
                    }"
                )
                WatchBridgeState.log("Install failure for $packageName status=$status")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.pendingUserActionIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        }
    }

    private fun sanitizeLogValue(raw: String): String {
        return raw.replace('\n', ' ').replace('\r', ' ').trim()
    }
}
