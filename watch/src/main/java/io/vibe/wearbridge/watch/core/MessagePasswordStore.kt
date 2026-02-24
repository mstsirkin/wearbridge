package io.vibe.wearbridge.watch.core

import android.content.Context

class MessagePasswordStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPassword(): String? {
        return prefs.getString(KEY_PASSWORD, null)?.takeIf { it.isNotBlank() }
    }

    fun setPassword(password: String?) {
        prefs.edit().putString(KEY_PASSWORD, password?.takeIf { it.isNotBlank() }).apply()
    }

    companion object {
        private const val PREFS_NAME = "wearbridge_security"
        private const val KEY_PASSWORD = "message_password"
    }
}
