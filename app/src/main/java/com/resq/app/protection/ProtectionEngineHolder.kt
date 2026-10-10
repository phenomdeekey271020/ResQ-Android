package com.resq.app.protection

import android.content.Context
import com.resq.app.data.sensors.AndroidSensorGateway
import com.resq.app.data.sensors.LocationFixStore
import com.resq.app.data.sensors.SensorGateway
import com.resq.app.data.sensors.SharedPreferencesLocationFixStore
import com.resq.app.escalation.EscalationLedger
import com.resq.app.escalation.InMemoryEscalationLedger
import com.resq.app.escalation.SharedPreferencesEscalationLedger
import com.resq.app.model.DetectionConfig
import com.resq.app.settings.ProtectionPreferences

/*
 * Process-wide holder for the single [ProtectionEngine].
 *
 * This is the dependency seam of the app: the UI ([com.resq.app.MainActivity])
 * and the foreground service ([com.resq.app.service.ProtectionForegroundService])
 * both resolve the same engine here, so exactly one gateway, one detector window
 * and one accident flow exist no matter how often the Activity is recreated.
 *
 * Deliberately a plain object rather than a DI framework - ResQ has no need for
 * more, and a container keeps the wiring auditable in one screen.
 */
object ProtectionEngineHolder {

    @Volatile
    private var instance: ProtectionEngine? = null

    /** True while the protection foreground service is running in this process. */
    @Volatile
    var isServiceRunning: Boolean = false
        internal set

    /**
     * The process-wide engine, created on first use from [context]'s application
     * context. Repeated calls return the same instance, so the sensors can only
     * ever be registered once.
     */
    fun engine(context: Context): ProtectionEngine {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: run {
                val appContext = context.applicationContext
                // ONE store instance for the whole process, handed to BOTH sides:
                // the gateway writes every usable fix into it, and the engine
                // reads it back for the emergency screen. Sharing the instance is
                // what keeps "the fix the foreground service cached" and "the fix
                // the emergency UI displays" the same row of data.
                val locationFixStore = SharedPreferencesLocationFixStore(appContext)
                build(
                    sensorGateway = AndroidSensorGateway(
                        appContext,
                        locationFixStore = locationFixStore,
                    ),
                    locationFixStore = locationFixStore,
                    config = DetectionConfig(
                        alertCountdownSeconds = ProtectionPreferences(appContext)
                            .alertCountdownSeconds,
                    ),
                    // DURABLE, so the "has this incident already sent its alert
                    // text / already rung its contact?" question survives the
                    // process being killed mid-incident. Without it, an app that
                    // is killed between dispatching and recording would have no
                    // memory of the incident at all when it restarted.
                    escalationLedger = SharedPreferencesEscalationLedger(appContext),
                ).also { instance = it }
            }
        }
    }

    /**
     * The production wiring of the engine, factored out of [engine] so it can be
     * exercised by a unit test that has no Android Context.
     *
     * WHY THIS FUNCTION EXISTS - THE REPORTED PHYSICAL-DEVICE FAILURE:
     *
     * [ProtectionEngine] takes its own `locationFixStore` parameter, separate
     * from the one [AndroidSensorGateway] takes for writing. The holder used to
     * pass the store to the gateway only. That compiles, runs, and fails
     * silently: every fix was still written to the private cache, but the ONLY
     * read path in the app - [ProtectionEngine.emergencyLocationSnapshot] - saw
     * `null` and fell through to the live channel, which with Location switched
     * off carries no coordinates. The emergency screen therefore reported
     * "Last known location: Unknown / Position basis: No position" even when a
     * perfectly good fix was sitting in the store, and force-stopping the app
     * changed nothing because the read side was never connected in the first
     * place. Hand-built engines in tests all passed the store, so no test could
     * see the omission.
     *
     * Building the engine here - in one function that a test calls - turns that
     * omission into a failing test instead of a missing position in an
     * emergency.
     */
    internal fun build(
        sensorGateway: SensorGateway,
        locationFixStore: LocationFixStore,
        config: DetectionConfig = DetectionConfig(),
        samplePublishIntervalMillis: Long = SamplePublishIntervalMillis,
        escalationLedger: EscalationLedger = InMemoryEscalationLedger(),
    ): ProtectionEngine = ProtectionEngine(
        sensorGateway = sensorGateway,
        config = config,
        // Hardware arrives at ~30 callbacks a second. Only the raw sample
        // values are coalesced for the UI; a detection, a flow change or a
        // monitoring change is always delivered immediately, so nothing the
        // user must react to is ever delayed.
        samplePublishIntervalMillis = samplePublishIntervalMillis,
        locationFixStore = locationFixStore,
        escalationLedger = escalationLedger,
    )

    /** Upper bound on how often the UI is told about new sensor values: 10 Hz. */
    const val SamplePublishIntervalMillis: Long = 100L
}