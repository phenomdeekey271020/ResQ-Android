package com.resq.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.resq.app.MainActivity
import com.resq.app.R
import com.resq.app.detection.AccidentEvent
import com.resq.app.detection.AccidentFlowState
import com.resq.app.escalation.EscalationCoordinatorHolder
import com.resq.app.protection.CountdownOwner
import com.resq.app.protection.ProtectionEngine
import com.resq.app.protection.ProtectionEngineHolder
import com.resq.app.protection.ProtectionEngineListener
import com.resq.app.protection.ProtectionSnapshot
import com.resq.app.protection.ProtectionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * CONTINUOUS PROTECTION - FOREGROUND SERVICE.
 *
 * It does four things and nothing else:
 *
 *   1. publishes an ongoing, truthful notification,
 *   2. attaches itself to the shared [ProtectionEngine] as a hardware consumer,
 *   3. keeps the "are you okay?" countdown moving while the app is not in front,
 *   4. detaches cleanly, unregistering every listener exactly once.
 *
 * It owns no detection logic: samples travel gateway -> engine -> AccidentDetector
 * -> AccidentStateMachine, all of which already existed before this task. There
 * is no networking, no dispatch, no phone call and no database here.
 */
class ProtectionForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var engine: ProtectionEngine
    private var listening = false
    private var foreground = false
    private var countdownJob: Job? = null
    private var reconcileJob: Job? = null
    private var lastCopy: ProtectionNotificationCopy? = null
    private var lastAlertCopy: ProtectionNotificationCopy? = null

    /** Type mask the service was last promoted with, so it is only set once. */
    private var lastForegroundType = 0

    private val engineListener = ProtectionEngineListener { snapshot ->
        onEngineSnapshot(snapshot)
    }

    override fun onCreate() {
        super.onCreate()
        engine = ProtectionEngineHolder.engine(applicationContext)
        /*
         * THE LIFECYCLE FIX, IN ONE LINE.
         *
         * The alert text and the primary-contact call used to be fired from the
         * ViewModel, which unregisters itself as an engine listener in
         * onCleared(). Once Android destroyed the Activity, this service could
         * still walk the incident to EMERGENCY and NOTHING was sent - which is
         * precisely the screen-off scenario the escalation exists for.
         *
         * Creating the coordinator here, from the component that actually survives
         * (stopWithTask=false, started by an explicit user tap), means the side
         * effects exist whenever protection does. It is process-wide and
         * idempotent, so this cannot create a second one, and the Activity only
         * READS its outcome afterwards.
         */
        EscalationCoordinatorHolder.coordinator(applicationContext)
        createNotificationChannel()
        createAlertChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopProtection()
            else -> startProtection()
        }
        // Not sticky: if the system reclaims the service, protection stops instead
        // of silently restarting on its own. Re-arming is always a user action.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseEverything()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private fun startProtection() {
        // Android requires the foreground notification within seconds of the
        // start request, so it is posted before any hardware is touched.
        if (!enterForeground()) {
            stopSelf()
            return
        }
        if (!listening) {
            engine.addListener(engineListener)
            listening = true
        }
        // Idempotent: a second start request re-attaches the same source, which
        // the engine reference counts, so the sensors are never registered twice.
        engine.attachSource(ProtectionSource.SERVICE)
        ProtectionEngineHolder.isServiceRunning = true
        startReconcileTicker()
    }

    private fun stopProtection() {
        releaseEverything()
        stopSelf()
    }

    /** Idempotent teardown: safe from stopSelf, onDestroy or a repeated stop. */
    private fun releaseEverything() {
        countdownJob?.cancel()
        countdownJob = null
        reconcileJob?.cancel()
        reconcileJob = null
        engine.releaseCountdown(CountdownOwner.SERVICE)
        if (listening) {
            engine.removeListener(engineListener)
            listening = false
        }
        engine.detachSource(ProtectionSource.SERVICE)
        ProtectionEngineHolder.isServiceRunning = false
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        clearAlertNotification()
        lastCopy = null
        lastAlertCopy = null
        lastForegroundType = 0
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Protection status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while ResQ continuously monitors for a possible incident."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * The incident channel, deliberately separate from the calm protection one.
     *
     * IMPORTANCE_HIGH is what makes the alert a real fallback: it heads-up on
     * screen and survives Do Not Disturb settings that silence the low-importance
     * status notification. The audio belongs to the announcer, not to this
     * notification, so it is posted silently and the alert tone is the single
     * source of sound.
     */
    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ALERT_ID,
            "Incident alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "A possible incident needs your attention."
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun enterForeground(): Boolean {
        val copy = protectionNotificationCopy(engine.snapshot) ?: CalmCopy
        val type = foregroundServiceType()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(copy), type)
            } else {
                // No typed overload before API 29; the manifest type applies.
                startForeground(NOTIFICATION_ID, buildNotification(copy))
            }
        }.onFailure { error ->
            Log.w(LogTag, "Foreground start refused, protection stays off", error)
        }.isSuccess
        foreground = started
        if (started) {
            lastCopy = copy
            lastForegroundType = type
        }
        return started
    }

    /**
     * The foreground-service types actually requested at runtime, or 0 on releases
     * that predate the typed startForeground() overload (the two-argument call
     * there has no type, and the manifest declaration governs instead).
     *
     * The manifest declares both `location` and `specialUse`, but the bits are not
     * simply OR-ed together:
     *
     *  - LOCATION is added only once location permission has actually been
     *    granted. Android 14+ checks the caller's permissions when a service is
     *    promoted and throws SecurityException for a type it cannot back up, so
     *    including it unconditionally would stop protection from starting at all
     *    for a user who declines location. This is what lets protection run - and
     *    keep monitoring motion - with location denied or switched off.
     *  - SPECIAL_USE only exists from API 34. Before that the constant is not a
     *    meaningful type bit, so it is left out and the manifest declaration is
     *    what the platform uses.
     */
    private fun foregroundServiceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var type = 0
        if (hasLocationPermission()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return type
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Adds or drops the LOCATION type if the permission was granted or revoked
     * while the service was already running. Android permits calling
     * startForeground() again to change the type set; without this, granting
     * location later would leave a location-using service without the location
     * type, and Android would stop delivering fixes once the app is backgrounded.
     */
    private fun syncForegroundType() {
        // Nothing to do before API 29: there is no typed startForeground() to
        // re-call there, and the manifest declaration is what applies.
        if (!foreground || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val desired = foregroundServiceType()
        if (desired == 0 || desired == lastForegroundType) return
        val copy = lastCopy ?: CalmCopy
        val changed = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification(copy), desired)
        }.onFailure { error ->
            Log.w(LogTag, "Could not update the foreground service type", error)
        }.isSuccess
        if (changed) lastForegroundType = desired
    }

    /**
     * Slow reconciliation of things Android changes without notifying us.
     *
     * The provider-toggle broadcast covers most cases immediately; this 5 second
     * tick is the safety net for a permission that was changed in system settings,
     * which has no broadcast at all. It is deliberately slow: two permission
     * checks every five seconds is not measurable CPU, and there is no polling
     * loop anywhere near the location stream itself - fixes arrive as callbacks.
     */
    private fun startReconcileTicker() {
        if (reconcileJob?.isActive == true) return
        reconcileJob = scope.launch {
            while (isActive) {
                delay(ReconcileIntervalMillis)
                engine.refreshLocation()
                syncForegroundType()
            }
        }
    }

    /** Tapping either notification brings the app to the incident screen. */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Mirrors the emergency number the emergency screen offers, so the
     * notification action and the in-app button can never disagree.
     */
    private val IncidentEmergencyNumber = "112"

    private fun buildNotification(copy: ProtectionNotificationCopy): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_protection)
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    /**
     * The visible incident notification. Posted while an incident is unresolved
     * and removed the moment the flow returns to NORMAL, so it can never outlive
     * the incident it describes.
     */
    private fun buildAlertNotification(copy: ProtectionNotificationCopy): Notification =
        NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(R.drawable.ic_stat_protection)
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.text))
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            // The notification is the ONLY reliable surface when the phone is
            // locked or the app is backgrounded, because Android may refuse to
            // start the call UI from there. Offering "Call emergency number"
            // here is what keeps that case a usable fallback rather than a dead
            // end: the tap is a genuine user action, so ACTION_DIAL is the right
            // mechanism - the user confirms it, and no consent is required.
            .addAction(
                R.drawable.ic_stat_protection,
                "Call $IncidentEmergencyNumber",
                dialEmergencyNumberIntent(),
            )
            .build()

    /**
     * Opens the dialer for the emergency number from the notification.
     *
     * A PendingIntent to the dialer, not to ACTION_CALL: from a notification the
     * user has to confirm, ResQ must never silently dial 112 on their behalf, and
     * an automated test can never reach this code path (it is Android-only).
     */
    private fun dialEmergencyNumberIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        DIAL_REQUEST_CODE,
        Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$IncidentEmergencyNumber"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Keeps the incident notification in step with the flow. It reuses the same
     * wording function as the ongoing notification, so the two can never claim
     * different things, and does nothing at all until the wording actually
     * changes - one update per sensor sample would be pure waste.
     */
    private fun syncAlertNotification(snapshot: ProtectionSnapshot) {
        val copy = incidentAlertCopy(snapshot)
        if (copy == null) {
            clearAlertNotification()
            return
        }
        if (copy == lastAlertCopy) return
        lastAlertCopy = copy
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(ALERT_NOTIFICATION_ID, buildAlertNotification(copy)) }
    }

    private fun clearAlertNotification() {
        if (lastAlertCopy == null) return
        lastAlertCopy = null
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(ALERT_NOTIFICATION_ID) }
    }

    /**
     * Engine callback. The notification is only rebuilt when the wording would
     * actually change - one update per sensor sample would be pure waste.
     */
    private fun onEngineSnapshot(snapshot: ProtectionSnapshot) {
        if (!foreground || !snapshot.monitoring) return
        val copy = protectionNotificationCopy(snapshot)
        if (copy != null && copy != lastCopy) {
            lastCopy = copy
            notify(copy)
        }
        // The visible fallback runs whenever protection is monitoring, so a
        // possible incident is still obvious if speech could not play.
        syncAlertNotification(snapshot)
        if (snapshot.flow is AccidentFlowState.PossibleAccident ||
            snapshot.flow == AccidentFlowState.Confirmation
        ) {
            startCountdownIfNeeded()
        }
    }

    private fun notify(copy: ProtectionNotificationCopy) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(copy)) }
    }

    // ------------------------------------------------------------------
    // Countdown while the app is not in front
    // ------------------------------------------------------------------

    /**
     * The UI drives the countdown while it is visible and alive. In the background
     * the service takes over, so a possible incident still runs its countdown and
     * still escalates when nobody answers - even with the screen off. The engine's
     * ownership rule guarantees exactly one ticker is ever alive, so the two can
     * never double-tick the same incident.
     *
     * Both stages are covered here:
     *
     *  - POSSIBLE_ACCIDENT - the "are you okay?" window, one tick per second;
     *  - CONFIRMATION      - the unconfirmed-safety window, which ends the incident's
     *                        wait by escalating, exactly as the UI does.
     */
    private fun startCountdownIfNeeded() {
        if (countdownJob?.isActive == true) return
        if (!engine.requestCountdown(CountdownOwner.SERVICE)) return
        countdownJob = scope.launch {
            var confirmationSecondsLeft = 0
            while (isActive) {
                delay(CountdownTickMillis)
                if (!engine.requestCountdown(CountdownOwner.SERVICE)) break
                when (engine.flow) {
                    is AccidentFlowState.PossibleAccident -> {
                        confirmationSecondsLeft = 0
                        engine.submit(AccidentEvent.CountdownTick)
                    }

                    AccidentFlowState.Confirmation -> {
                        if (confirmationSecondsLeft <= 0) {
                            confirmationSecondsLeft = engine.confirmationWindowSeconds
                        }
                        confirmationSecondsLeft -= 1
                        if (confirmationSecondsLeft <= 0) {
                            engine.submit(AccidentEvent.NoResponse)
                        }
                    }

                    // NORMAL, or an emergency that is already running: nothing
                    // left to tick.
                    else -> break
                }
            }
            engine.releaseCountdown(CountdownOwner.SERVICE)
        }
    }

    companion object {
        const val ACTION_START = "com.resq.app.action.START_PROTECTION"
        const val ACTION_STOP = "com.resq.app.action.STOP_PROTECTION"

        const val NOTIFICATION_ID = 4711
        const val CHANNEL_ID = "resq_protection"

        /** Separate id and channel so the incident alert can outrank the status one. */
        const val ALERT_NOTIFICATION_ID = 4712
        const val CHANNEL_ALERT_ID = "resq_incident_alerts"

        private const val CountdownTickMillis = 1_000L
        private const val ReconcileIntervalMillis = 5_000L

        /** Distinct request code so the dial PendingIntent never collides. */
        private const val DIAL_REQUEST_CODE = 1
        private const val LogTag = "ResQProtection"

        /** Shown for the few milliseconds before the engine publishes a snapshot. */
        private val CalmCopy = ProtectionNotificationCopy(
            title = "ResQ Protection Active",
            text = "Monitoring for possible incidents",
        )
    }
}