package com.resq.app.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.jvm.JvmName

/**
 * Settings that change how the accident flow behaves, persisted in the same
 * preferences store as the appearance mode so the user's choice survives app
 * restarts.
 *
 * The alert-countdown override defaults to null, which keeps the existing
 * per-level behavior (10 s for POSSIBLE, 5 s for HIGH) unchanged.
 */
class ProtectionPreferences(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var alertCountdownSeconds: Int? by mutableStateOf(readAlertCountdown())
        private set

    /** Persist a flat alert-countdown value. Null restores the per-level (10 / 5 s) default. */
    fun updateAlertCountdownSeconds(value: Int?) {
        if (alertCountdownSeconds == value) return
        alertCountdownSeconds = value
        preferences.edit().apply {
            if (value == null) {
                remove(KEY_ALERT_COUNTDOWN)
            } else {
                putInt(KEY_ALERT_COUNTDOWN, value)
            }
            apply()
        }
    }

    fun updateAlertCountdownSeconds(value: Int) {
        updateAlertCountdownSeconds(value.takeIf { it in ALERT_COUNTDOWN_OPTIONS })
    }

    private fun readAlertCountdown(): Int? {
        val value = preferences.getInt(KEY_ALERT_COUNTDOWN, -1)
        return if (value == -1) null else value
    }

    private companion object {
        const val PREFERENCES_NAME = "resq_settings"
        const val KEY_ALERT_COUNTDOWN = "alert_countdown_seconds"
        val ALERT_COUNTDOWN_OPTIONS = setOf(10, 20, 30, 45, 60)
    }
}
