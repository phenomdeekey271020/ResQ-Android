package com.resq.app.protection

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.resq.app.service.ProtectionForegroundService

/*
 * START_PROTECTION / STOP_PROTECTION.
 *
 * The ViewModel talks to protection through this tiny interface instead of
 * touching a Service directly, which keeps the whole start/stop decision
 * unit testable (a fake controller records the calls) and keeps the ViewModel
 * free of Android framework types.
 *
 * Nothing here decides whether protection should run: the decision is always the
 * user's, taken from the Protection Home screen.
 */
interface ProtectionController {

    /** True while the foreground service is alive in this process. */
    val isProtectionRunning: Boolean

    /** Starts continuous protection. Calling it twice must not monitor twice. */
    fun startProtection()

    /** Stops continuous protection and releases the sensors. */
    fun stopProtection()
}

/** Starts and stops [ProtectionForegroundService]. */
class ForegroundServiceProtectionController(context: Context) : ProtectionController {

    private val appContext = context.applicationContext

    override val isProtectionRunning: Boolean
        get() = ProtectionEngineHolder.isServiceRunning

    override fun startProtection() {
        val intent = Intent(appContext, ProtectionForegroundService::class.java)
            .setAction(ProtectionForegroundService.ACTION_START)
        runCatching {
            ContextCompat.startForegroundService(appContext, intent)
        }.onFailure { error ->
            // Background-start restrictions can refuse the request. Protection
            // simply stays off; it is never silently faked.
            Log.w(LogTag, "Could not start the protection service", error)
        }
    }

    override fun stopProtection() {
        val intent = Intent(appContext, ProtectionForegroundService::class.java)
        runCatching { appContext.stopService(intent) }
            .onFailure { error -> Log.w(LogTag, "Could not stop the protection service", error) }
    }

    private companion object {
        const val LogTag = "ResQProtection"
    }
}