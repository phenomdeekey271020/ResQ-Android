package com.resq.app.protection

import com.resq.app.data.sensors.LocationFixStore
import com.resq.app.data.sensors.SensorGateway
import com.resq.app.data.sensors.SensorObserver
import com.resq.app.escalation.EscalationLedger
import com.resq.app.escalation.InMemoryEscalationLedger
import com.resq.app.detection.AccidentDetector
import com.resq.app.detection.AccidentEvent
import com.resq.app.detection.AccidentFlowState
import com.resq.app.detection.AccidentStateMachine
import com.resq.app.model.AccelerometerState
import com.resq.app.model.DetectionAssessment
import com.resq.app.model.DetectionConfig
import com.resq.app.model.DetectionLevel
import com.resq.app.model.EmergencyLocationSnapshot
import com.resq.app.model.GyroscopeState
import com.resq.app.model.LocationFixCachePolicy
import com.resq.app.model.LocationState
import com.resq.app.model.SensorState
import com.resq.app.model.emergencySnapshot

/*
 * THE SINGLE DETECTION PIPELINE OF THE APP.
 *
 *   SensorGateway -> ProtectionEngine -> AccidentDetector -> DetectionAssessment
 *                                                |
 *                                                v
 *                                       AccidentStateMachine -> AccidentFlowState
 *
 * One instance is shared by the whole process (see [ProtectionEngineHolder]) and
 * therefore by the UI ViewModel and the foreground service alike, so there is
 * exactly one detector window and one accident flow in ResQ. The class is pure
 * Kotlin - it knows nothing about Services, notifications, timers or Compose, so
 * the whole coordination can be unit tested with a fake gateway.
 *
 * It performs no networking, no dispatch and no phone calls. EMERGENCY is an
 * application state only, reached through the same state machine the UI already
 * uses.
 */

/**
 * What the platform reports right now about location access, independent of any
 * fix ResQ happens to be holding.
 *
 * Distinct from [com.resq.app.model.LocationAvailability] on purpose: that one
 * describes the last published reading (and therefore ages), while this one is a
 * fresh question asked of the system. A user who has just toggled Location in
 * settings must be answered by the latter.
 */
enum class LocationPlatformState {
    READY,
    LOCATION_OFF,
    PERMISSION_MISSING,
    UNKNOWN,
}

/** Who currently needs hardware samples. Sources are reference counted. */
enum class ProtectionSource {
    /** The Diagnostics screen wants to show live readings. */
    DIAGNOSTICS_UI,

    /** The foreground service is running continuous protection. */
    SERVICE,

    /**
     * The emergency experience needs a position for the incident in progress.
     *
     * This source exists because of the reported physical-device failure. Every
     * location entry point ([refreshLocation], [forceLocationRefresh]) is guarded
     * by `if (sources.isEmpty()) return`, because there is nothing to refresh when
     * nobody is listening. That guard is correct - and it was the bug: an incident
     * can reach the emergency screen with NO source attached (protection paused,
     * or an OEM battery manager having killed the foreground service, which is
     * routine on Vivo/OriginOS). In that state ResQ owned no listener, every
     * recovery action was a silent no-op, and neither Refresh nor the settings
     * return could ever produce a fix. The screen looked broken because it WAS.
     *
     * Attaching this source for the duration of the emergency is the fix: the
     * gateway is registered, the reference counting keeps it registered exactly
     * once, and recovery works with or without a running service.
     */
    EMERGENCY_UI,
}

/**
 * Who is allowed to drive the "are you okay?" countdown. Declaration order is
 * precedence order: a visible UI always wins over the background service, and
 * the service takes over again once the UI is gone.
 */
enum class CountdownOwner {
    NONE,
    SERVICE,
    APP_UI,
}

/**
 * Immutable snapshot of everything the protection pipeline currently knows.
 * Published synchronously to every [ProtectionEngineListener].
 */
data class ProtectionSnapshot(
    val monitoring: Boolean = false,
    val sources: Set<ProtectionSource> = emptySet(),
    val sensors: SensorState = SensorState(),
    val detection: DetectionAssessment = DetectionAssessment(),
    val flow: AccidentFlowState = AccidentFlowState.Normal,
)

/**
 * Incident id used when no incident is open. Distinct from every real id, which
 * the durable ledger starts at 1, so "no incident" can never be mistaken for
 * "incident zero" and inherit a stale mark.
 */
const val NO_INCIDENT: Long = 0L

/** Receives every [ProtectionSnapshot] the engine publishes. */
fun interface ProtectionEngineListener {
    fun onProtectionSnapshot(snapshot: ProtectionSnapshot)
}

/**
 * Coordinates the existing [SensorGateway], [AccidentDetector] and
 * [AccidentStateMachine]. It holds no thresholds of its own - every heuristic
 * still lives in [DetectionConfig].
 *
 * [samplePublishIntervalMillis] coalesces listener callbacks for raw sample
 * values only; monitoring changes, flow changes and detection level changes are
 * always delivered immediately. It defaults to zero - publish everything - so
 * unit tests observe every sample synchronously.
 */
class ProtectionEngine(
    private val sensorGateway: SensorGateway?,
    private val config: DetectionConfig = DetectionConfig(),
    private val samplePublishIntervalMillis: Long = 0L,
    /**
     * The pre-incident cache of recent valid fixes.
     *
     * Read by [emergencyLocationSnapshot] so a position measured BEFORE the
     * incident can still be shown as honest historical context when nothing live
     * is available. Null in plain unit tests that do not care about caching.
     */
    private val locationFixStore: LocationFixStore? = null,
    /**
     * Durable bookkeeping for the escalation side effects.
     *
     * Injected rather than kept in memory on purpose: sending an SMS and placing
     * a call are NOT idempotent, and Android may kill this process at any moment
     * - including between deciding to send and recording that it was sent. The
     * production implementation is disk-backed; see [EscalationLedger] for the
     * at-most-once trade-off it makes and why.
     */
    private val escalationLedger: EscalationLedger = InMemoryEscalationLedger(),
) {

    private val detector = AccidentDetector(config)
    private val accidentFlow = AccidentStateMachine(config)
    private val sources = linkedSetOf<ProtectionSource>()
    private val listeners = mutableListOf<ProtectionEngineListener>()

    /*
     * SERIALIZATION POINT FOR THE WHOLE INCIDENT FLOW.
     *
     * The accident flow is mutated from several threads at once: sensor
     * callbacks (main thread), the app's countdown and emergency-stage timers
     * (Dispatchers.Default), the foreground service's own ticker
     * (Dispatchers.Default) and every user intent (main thread). A
     * read-modify-write of the flow or of a listener's UI projection that
     * interleaves with another one can lose an event - a stand-down that is
     * overwritten by an in-flight stage advance, or a navigation update that
     * disappears under a telemetry pulse. That is exactly how an in-app back
     * tap used to appear to "do nothing".
     *
     * One re-entrant lock fixes it at the source: every mutation, every
     * published snapshot and every listener callback runs under this lock, so
     * transitions and their UI projection are applied as one atomic step, in a
     * deterministic order. Listeners run re-entrantly on the same thread, and
     * no other lock exists anywhere in the pipeline, so lock ordering cannot
     * deadlock.
     *
     * It is public because [com.resq.app.viewmodel.ResQViewModel] performs its
     * own multi-step state updates with `synchronized(flowLock)` - the very same
     * lock - so a user intent can never interleave with a snapshot mid-update.
     */
    val flowLock: Any = Any()

    /**
     * Whether THIS incident has already produced its one automatic
     * primary-contact call attempt.
     *
     * Lives on the engine - the process-wide singleton - rather than in the
     * ViewModel, so an Activity recreation (a new ViewModel over the same
     * engine) cannot dial a second time for an incident that is still running.
     * Cleared automatically whenever the flow leaves EMERGENCY, which is the
     * same incident boundary the rest of the app uses.
     */
    /**
     * Identity of the incident currently in progress.
     *
     * This is the app's single source of truth for "which incident is this?".
     * Both escalation side effects - the alert text and the primary-contact call -
     * are keyed by it, so they can never disagree about whether they have already
     * run for the same incident, and a later incident can never be mistaken for
     * an earlier one.
     *
     * It is minted by [submit] at the exact transition that opens an incident,
     * which is why it is correct no matter who submitted the event: the UI, a
     * recreated ViewModel, or the foreground service's own countdown.
     */
    var currentIncidentId: Long = NO_INCIDENT
        private set

    /**
     * Whether the incident currently in progress came from Developer/Test Mode.
     *
     * Checked by every automatic side effect. It is a property of the INCIDENT,
     * not of the event that happens to arrive later: a simulated incident that
     * walks PossibleAccident -> Confirmation -> Emergency has a real CountdownTick
     * and a real NoResponse submitted by the foreground service on its behalf, and
     * neither of those may launder it into a "real" incident.
     *
     * Set only by [submit]'s `simulated` argument, which the simulation entry
     * points pass, and cleared the moment a new incident begins.
     */
    var incidentSimulated: Boolean = false
        private set

    /** True when an incident is open and its identity is known. */
    val hasOpenIncident: Boolean get() = currentIncidentId != NO_INCIDENT

    /** The durable ledger, so the escalation coordinator shares this one source. */
    val ledger: EscalationLedger get() = escalationLedger

    /**
     * Re-delivers the current snapshot to every listener.
     *
     * Needed because the escalation coordinator updates its own outcome AFTER the
     * engine has already published - it learns whether the carrier accepted the
     * message asynchronously. Without this the UI would only ever see the
     * pre-escalation state until the next unrelated sensor sample happened to
     * publish. Listeners run under the flow lock, so this cannot race a flow
     * change.
     */
    fun republishNow() {
        synchronized(flowLock) {
            listeners.toList().forEach { listener -> listener.onProtectionSnapshot(published) }
        }
    }

    private var sensors = SensorState()
    private var detection = DetectionAssessment()
    private var published = ProtectionSnapshot()
    private var publishedSources: Set<ProtectionSource> = emptySet()
    private var lastNotifiedAtMillis = Long.MIN_VALUE

    /**
     * When false, incoming samples are still published so the UI can show live
     * readings, but they are never scored and can never raise an incident. This
     * is how "protection paused" is honoured while Diagnostics is still reading
     * the hardware.
     */
    var detectionEnabled: Boolean = true

    /**
     * Only continuous protection may raise an incident from hardware.
     *
     * The Diagnostics screen scores and displays real samples so a reviewer can
     * see the engine working, but it never escalates on its own: a jolt caused by
     * poking at the diagnostics list must never be able to open a real alert.
     * The moment the foreground service is attached the same samples do feed the
     * shared state machine, which is what continuous protection means.
     */
    private val canEscalate: Boolean get() = sources.contains(ProtectionSource.SERVICE)

    /** The owner currently driving the countdown. */
    var countdownOwner: CountdownOwner = CountdownOwner.NONE
        private set

    /** Current position in the shared accident flow. */
    val flow: AccidentFlowState get() = synchronized(flowLock) { accidentFlow.state }

    /**
     * Length of the unconfirmed-safety (no-response) window in seconds, read from
     * the same [DetectionConfig] the state machine uses so the service that runs
     * the window in the background cannot drift from the UI.
     */
    val confirmationWindowSeconds: Int get() = config.confirmationWindowSeconds

    /** Flat alert-countdown override; null → per-level values of 10 s / 5 s. */
    var alertCountdownSeconds: Int?
        set(value) {
            accidentFlow.setAlertCountdownSeconds(value)
        }
        get() = config.alertCountdownSeconds

    /** True while at least one source needs samples. */
    val isMonitoring: Boolean get() = synchronized(flowLock) { sources.isNotEmpty() }

    /** The latest published snapshot. */
    val snapshot: ProtectionSnapshot get() = synchronized(flowLock) { published }

    fun addListener(listener: ProtectionEngineListener) {
        synchronized(flowLock) {
            listeners.add(listener)
            listener.onProtectionSnapshot(published)
        }
    }

    fun removeListener(listener: ProtectionEngineListener) {
        synchronized(flowLock) { listeners.remove(listener) }
    }

    // ------------------------------------------------------------------
    // Hardware lifecycle - reference counted, so two owners cannot register
    // the sensors twice.
    // ------------------------------------------------------------------

    /**
     * Registers [source] as a consumer of hardware samples. The gateway is
     * started on the first consumer only, which is what prevents a duplicate
     * registration when Diagnostics opens while protection is already running.
     *
     * [restart] forces a fresh gateway registration even when [source] was
     * already attached, which is what a newly granted runtime permission needs.
     */
    fun attachSource(source: ProtectionSource, restart: Boolean = false) {
        synchronized(flowLock) {
            val wasEmpty = sources.isEmpty()
            sources.add(source)
            val gateway = sensorGateway
            if (gateway != null && (wasEmpty || restart)) {
                gateway.start(observer)
            }
            publish(force = true)
        }
    }

    /**
     * Detaches [source]. The gateway is unregistered only when the last consumer
     * leaves, and the detector window plus the published assessment are cleared
     * at that point so a paused protection session can never resume on evidence
     * collected before the pause.
     */
    fun detachSource(source: ProtectionSource) {
        synchronized(flowLock) {
            if (!sources.remove(source)) return
            if (sources.isEmpty()) {
                sensorGateway?.stop()
                detector.reset()
                detection = DetectionAssessment()
            }
            publish(force = true)
        }
    }

    /**
     * Re-checks what the platform can change without telling us - a revoked
     * location permission, the device location toggle - and republishes the
     * current sensors.
     *
     * This is how ResQ notices a permission change while protection is running,
     * instead of only when Diagnostics is reopened. It never re-registers the
     * motion sensors and never restarts detection.
     */
    fun refreshLocation() {
        synchronized(flowLock) {
            if (sources.isEmpty()) return
            sensorGateway?.refresh()
        }
    }

    /**
     * The FORCED counterpart of [refreshLocation], used by the emergency screen's
     * "Refresh location" action and by the lifecycle resume hook.
     *
     * A merely idempotent refresh could not fix the reported bug: with the device
     * Location toggle having been switched off and on, the platform drops the
     * provider's registration along with the provider, so the gateway still
     * believed it was listening while no callback could ever arrive again. This
     * tears that stale registration down and re-requests, which is what actually
     * recovers the session.
     */
    fun forceLocationRefresh() {
        synchronized(flowLock) {
            if (sources.isEmpty()) return
            sensorGateway?.refreshForcingRegistration()
        }
    }

    /**
     * The platform facts the recovery decision needs, read straight from the
     * gateway rather than inferred from a possibly-stale snapshot.
     *
     * They exist so "is Location still off?" and "is the permission still missing?"
     * are answered by the platform at the moment the user asked, not by whatever
     * the last published state happened to say.
     */
    fun locationPlatformState(): LocationPlatformState = synchronized(flowLock) {
        val gateway = sensorGateway
        when {
            gateway == null -> LocationPlatformState.UNKNOWN
            !gateway.hasLocationPermission() -> LocationPlatformState.PERMISSION_MISSING
            !gateway.isLocationEnabled() -> LocationPlatformState.LOCATION_OFF
            else -> LocationPlatformState.READY
        }
    }

    /**
     * The best position available right now, for the emergency experience.
     *
     * Local state, not dispatch: the engine itself performs no networking. The
     * ViewModel uses this snapshot to search for nearby emergency resources
     * while the flow is in EMERGENCY; it never alerts, messages or calls
     * anyone, and a stale one is labelled stale rather than passed off as
     * current (and is not used for the search).
     *
     * FALLBACK LADDER, in the order the requirements demand:
     *
     *  1. a live fix from a permitted provider - used exactly as it arrives;
     *  2. otherwise the pre-incident cache, which is a real earlier measurement
     *     and is returned as a snapshot whose freshness is FORCED to STALE;
     *  3. otherwise nothing. There is no third source and no fabricated default,
     *     so "no usable position" stays representable instead of becoming (0, 0).
     *
     * Step 2 is what lets ResQ still show a real position to a rescuer when the
     * accident happened with Location already switched off, without ever implying
     * it describes where the phone is now.
     */
    fun emergencyLocationSnapshot(nowMillis: Long = now()): EmergencyLocationSnapshot =
        synchronized(flowLock) {
            sensors.location.emergencySnapshot(nowMillis).takeIf { it.hasCoordinates }
                ?: locationFixStore
                    ?.read()
                    ?.let { LocationFixCachePolicy.historicalSnapshot(it, nowMillis) }
                ?: sensors.location.emergencySnapshot(nowMillis)
        }

    // ------------------------------------------------------------------
    // Detection flow
    // ------------------------------------------------------------------

    /**
     * Applies [event] to the shared accident flow and publishes the result.
     *
     * The transition, the snapshot and every listener callback happen as one
     * atomic step under [flowLock], no matter which thread offered the event.
     *
     * [simulated] is how an incident's ORIGIN is recorded, and it is deliberately
     * a parameter of this method rather than a separate call. The original design
     * let the ViewModel set a pending flag and stamp it from inside a listener
     * callback, which made the origin depend on listener ordering - and left the
     * per-incident field stale when an incident began without passing through the
     * screen that stamped it. Deciding it here, inside the same atomic step that
     * mints the incident id, removes both problems: the flag can no longer be
     * applied to the wrong incident, and a ViewModel recreation cannot lose it.
     *
     * A new incident begins whenever the flow leaves NORMAL for any other state.
     * That single rule covers every entry: hardware detection, the countdown
     * expiring, and a user tapping "I Need Help" from a calm screen - which is
     * exactly the case that used to inherit a stale SIMULATED origin.
     */
    fun submit(event: AccidentEvent, simulated: Boolean = false): AccidentFlowState =
        synchronized(flowLock) {
            val previous = accidentFlow.state
            val next = accidentFlow.reduce(event, now())

            // Incident boundary: NORMAL -> anything else. Everything about the
            // previous incident is finished, and a brand-new identity is minted
            // from the durable ledger so the new incident can never inherit a
            // side-effect mark left by an older one.
            if (previous == AccidentFlowState.Normal && next != AccidentFlowState.Normal) {
                currentIncidentId = escalationLedger.beginIncident(simulated)
                incidentSimulated = simulated
            } else if (simulated) {
                // Defensive: a simulation submitted while an incident is already
                // open still marks that incident as simulated rather than being
                // silently dropped.
                incidentSimulated = true
            }

            // Leaving EMERGENCY ends the incident's escalation window.
            if (previous is AccidentFlowState.Emergency &&
                next !is AccidentFlowState.Emergency
            ) {
                currentIncidentId = NO_INCIDENT
                incidentSimulated = false
            }

            publish(force = true)
            next
        }

    /**
     * Scores a synthetic sequence through the very same detector used for real
     * hardware. The published sensor state is deliberately left untouched so a
     * simulation can never be mistaken for a measurement.
     */
    fun evaluateSynthetic(samples: List<SensorState>): DetectionAssessment = synchronized(flowLock) {
        detector.reset()
        var assessment = DetectionAssessment()
        samples.forEach { sample ->
            assessment = detector.evaluate(sample, sample.accelerometer.timestampMillis)
        }
        assessment
    }

    /** Forgets the detector window and returns the flow to NORMAL. */
    fun reset() {
        synchronized(flowLock) {
            detector.reset()
            accidentFlow.reset()
            currentIncidentId = NO_INCIDENT
            incidentSimulated = false
            detection = DetectionAssessment()
            publish(force = true)
        }
    }

    // ------------------------------------------------------------------
    // Countdown ownership
    // ------------------------------------------------------------------

    /**
     * Claims the countdown for [owner]. Returns true when [owner] may drive it -
     * either because nobody holds it, or because [owner] outranks the current
     * holder (a visible UI takes over from the background service).
     */
    fun requestCountdown(owner: CountdownOwner): Boolean {
        synchronized(flowLock) {
            if (owner == CountdownOwner.NONE) return false
            if (countdownOwner == owner) return true
            if (countdownOwner.ordinal > owner.ordinal) return false
            countdownOwner = owner
            return true
        }
    }

    /** Releases the countdown so a lower ranked owner can take it. */
    fun releaseCountdown(owner: CountdownOwner) {
        synchronized(flowLock) {
            if (countdownOwner != owner) return
            countdownOwner = CountdownOwner.NONE
        }
    }

    // ------------------------------------------------------------------
    // Sample intake
    // ------------------------------------------------------------------

    private val observer = object : SensorObserver {
        /**
         * The accelerometer is the detector's clock: one window entry is created
         * per accelerometer sample, and the detector always pairs it with the
         * latest gyroscope and location. Scoring on gyroscope callbacks as well
         * would push duplicate accelerometer entries into the same window, and
         * enough of those duplicates would let a single violent sample masquerade
         * as several agreeing ones.
         */
        override fun onAccelerometer(accelerometerState: AccelerometerState) {
            synchronized(flowLock) {
                if (sources.isEmpty()) return
                sensors = sensors.copy(accelerometer = accelerometerState)
                score()
            }
        }

        override fun onGyroscope(gyroscopeState: GyroscopeState) {
            synchronized(flowLock) {
                if (sources.isEmpty()) return
                sensors = sensors.copy(gyroscope = gyroscopeState)
                publish()
            }
        }

        override fun onLocation(locationState: LocationState) {
            synchronized(flowLock) {
                if (sources.isEmpty()) return
                sensors = sensors.copy(location = locationState)
                publish()
            }
        }
    }

    /**
     * Scores one window and offers the verdict to the state machine. Runs on the
     * thread that owns the sensor listener: no logging, no buffering and no
     * collection allocation per sample.
     */
    private fun score() {
        if (detectionEnabled) detection = detector.evaluate(sensors, now())
        publish()
        // A level other than NORMAL is the only thing a sensor sample may ever
        // contribute; the state machine decides what it means.
        if (detectionEnabled && canEscalate && detection.level != DetectionLevel.NORMAL) {
            submit(AccidentEvent.Detected(detection.level))
        }
    }

    /** MUST be called while holding [flowLock]; listeners run inline. */
    private fun publish(force: Boolean = false) {
        if (sources.toSet() != publishedSources) publishedSources = sources.toSet()
        val next = ProtectionSnapshot(
            monitoring = sources.isNotEmpty(),
            sources = publishedSources,
            sensors = sensors,
            detection = detection,
            flow = accidentFlow.state,
        )
        val previous = published
        published = next
        if (next == previous) return

        // Anything the user could act on - monitoring, sources, the accident flow
        // or the detection level - is delivered immediately. Only the raw sample
        // values are coalesced, so a backgrounded UI does not recompose on every
        // one of the ~30 sensor callbacks per second.
        val significant = next.monitoring != previous.monitoring ||
            next.sources != previous.sources ||
            next.flow != previous.flow ||
            next.detection.level != previous.detection.level
        if (!force && !significant && now() - lastNotifiedAtMillis < samplePublishIntervalMillis) {
            return
        }
        lastNotifiedAtMillis = now()
        // Copied first: a listener may legitimately add or remove listeners.
        listeners.toList().forEach { listener -> listener.onProtectionSnapshot(next) }
    }

    private fun now(): Long = System.currentTimeMillis()
}