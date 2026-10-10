package com.resq.app.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.resq.app.alert.AlertAnnouncer
import com.resq.app.alert.AlertCue
import com.resq.app.contacts.CallLaunchResult
import com.resq.app.contacts.CallLauncher
import com.resq.app.contacts.EmergencyContactsPolicy
import com.resq.app.contacts.EmergencyContactsStore
import com.resq.app.data.remote.EmergencyResourceRepository
import com.resq.app.data.remote.IncidentTypePossibleAccident
import com.resq.app.data.remote.ResourceSearchResult
import com.resq.app.data.remote.searchKey
import com.resq.app.data.sensors.SensorGateway
import com.resq.app.detection.AccidentEvent
import com.resq.app.detection.AccidentFlowState
import com.resq.app.detection.EmergencyStage
import com.resq.app.detection.SimulationSamples
import com.resq.app.escalation.EmergencyEscalationCoordinator
import com.resq.app.model.AppScreen
import com.resq.app.model.BaselineAccidentScore
import com.resq.app.model.DetectionAssessment
import com.resq.app.model.DetectionConfidence
import com.resq.app.model.DetectionConfig
import com.resq.app.model.DetectionLevel
import com.resq.app.model.DetectionState
import com.resq.app.model.EmergencyContact
import com.resq.app.model.EmergencyLocationSnapshot
import com.resq.app.model.LastKnownLocationFix
import com.resq.app.model.LocationFixCachePolicy
import com.resq.app.model.EmergencyProgress
import com.resq.app.model.EmergencyState
import com.resq.app.model.FixFreshness
import com.resq.app.model.GpsStatus
import com.resq.app.model.IncidentState
import com.resq.app.model.LocationAvailability
import com.resq.app.model.LocationFreshnessPolicy
import com.resq.app.model.LocationRecoveryReason
import com.resq.app.model.LocationRecoveryState
import com.resq.app.model.LocationState
import com.resq.app.model.PrimaryContactCall
import com.resq.app.model.ProtectionState
import com.resq.app.model.ResQUiState
import com.resq.app.model.ResourceDiscoveryState
import com.resq.app.model.ResourceSearchFailure
import com.resq.app.model.SensorReading
import com.resq.app.model.SensorState
import com.resq.app.model.SensorStatus
import com.resq.app.model.SmsEscalation
import com.resq.app.model.TelemetryState
import com.resq.app.model.confidence
import com.resq.app.model.countdownSeconds
import com.resq.app.model.label
import com.resq.app.model.reasonSummary
import com.resq.app.protection.CountdownOwner
import com.resq.app.protection.LocationPlatformState
import com.resq.app.protection.ProtectionController
import com.resq.app.protection.ProtectionEngine
import com.resq.app.protection.ProtectionEngineListener
import com.resq.app.protection.ProtectionSnapshot
import com.resq.app.protection.ProtectionSource
import com.resq.app.sms.SmsSender
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * UI state holder for all four ResQ experiences.
 *
 * Layering: screens (UI) call these intents, intents mutate the typed
 * [ResQUiState], and the coordination lives in one place - the process-wide
 * [ProtectionEngine]:
 *
 *  - [SensorGateway] supplies real accelerometer, gyroscope and location samples
 *    and is owned by that engine, never by this class.
 *  - [com.resq.app.detection.AccidentDetector] scores those samples into a
 *    prototype confidence. It is a pure domain class with no knowledge of
 *    Compose, timers or emergencies.
 *  - [com.resq.app.detection.AccidentStateMachine] decides what the app may do
 *    next, including the countdown and the move into the emergency experience.
 *  - [ProtectionController] starts and stops the foreground service, so
 *    continuous monitoring survives the Activity while still being entirely
 *    user controlled.
 *
 * This class renders that shared pipeline into UI state; it never interprets a
 * sensor value itself. It owns the two external effects the incident flow can
 * produce, both injected so they can be faked and so no test can reach a real
 * speaker or dialer:
 *
*  - [alertAnnouncer] speaks the first and second warnings and plays the alert
 *     tone (see com.resq.app.alert);
 *  - [callLauncher] places a real call to the primary emergency contact when an
 *     incident escalates and nobody answered, or opens the dialer when that is
 *     not permitted. At most once per incident, only for the primary contact,
 *     only with explicit consent, and never in Developer/Test Mode.
 *  - [smsSender] sends ONE alert text per incident, from the user's own SIM, to
 *     the saved contacts - only with a separate, default-off SMS consent, only
 *     with the SEND_SMS grant re-checked at escalation time, never from
 *     Developer/Test Mode and never to the emergency number. It is a second
 *     permitted ACTION at the existing escalation point, not a second escalation
 *     engine: there is still exactly one countdown, one incident state machine
 *     and one one-per-incident budget for each effect.
 *
 * About both: ResQ may ask Android to place a call or send a message, but it can
 * never know whether a call was answered or whether a text was read. Every
 * outcome recorded and displayed therefore describes only what was requested and
 * what the platform reported - never that a person was reached. Every
 * user-initiated action stays ACTION_DIAL, which never places a call on its own.
 *
 * The only networking is the nearby-resource search run through
 * [resourceRepository] while the flow is in EMERGENCY. Nothing is ever dispatched
 * to emergency services or any third party, and the APK holds no API secret: the
 * alert text goes from the user's SIM through their carrier.
 */
class ResQViewModel(
    sensorGateway: SensorGateway? = null,
    private val config: DetectionConfig = DetectionConfig(),
    engine: ProtectionEngine? = null,
    private val protectionController: ProtectionController? = null,
    private val resourceRepository: EmergencyResourceRepository? = null,
    /** Speaks the first and second spoken warnings. Null in plain unit tests. */
    private val alertAnnouncer: AlertAnnouncer? = null,
    /** Persists the user's trusted contacts and their escalation consent. */
    private val contactsStore: EmergencyContactsStore? = null,
    /**
     * Places a call to the primary contact, or opens the dialer. Injected so no
     * automated test can ever reach a real dialer or place a real call, and so a
     * test can assert exactly how many call attempts one incident produced.
     */
    private val callLauncher: CallLauncher? = null,
    /**
     * The process-wide escalation coordinator.
     *
     * OBSERVATION ONLY. The alert text and the primary-contact call are dispatched
     * by this object, not by this class, precisely so they keep working when the
     * Activity is destroyed and this ViewModel is cleared. The ViewModel reads
     * [EmergencyEscalationCoordinator.smsOutcome] and
     * [EmergencyEscalationCoordinator.callOutcome] purely to render truthful copy.
     *
     * Nullable so a plain unit test can construct a ViewModel with no coordinator
     * at all; when present it is also used to keep the escalation state in sync.
     */
    private val escalationCoordinator: EmergencyEscalationCoordinator? = null,
    /**
     * Interval of both countdowns. Defaults to the real one second; tests shrink
     * it so a whole incident can be driven to its end deterministically, in the
     * same spirit as the engine's `samplePublishIntervalMillis`.
     */
    private val countdownTickMillis: Long = 1_000L,
    /**
     * Deadline for one explicit location acquisition attempt.
     *
     * Injected so the timeout path is unit tested for real rather than assumed: a
     * test can shrink it to a few milliseconds and assert that a timed-out attempt
     * leaves no spinner behind and that the next one succeeds.
     */
    private val locationRecoveryTimeoutMillis: Long = 20_000L,
) : ViewModel(), ProtectionEngineListener {

    var state by mutableStateOf(ResQUiState())
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val protection = engine ?: ProtectionEngine(sensorGateway, config)
    private var countdownJob: Job? = null
    private var confirmationJob: Job? = null
    private var emergencyJob: Job? = null
    private var detectionJob: Job? = null
    private var telemetryJob: Job? = null
    private var resourceDiscoveryJob: Job? = null

    /**
     * The explicit location-recovery attempt currently running, if any.
     *
     * It exists because "Refresh location" used to be a single synchronous
     * re-evaluation of the snapshot. That can only ever reproduce the verdict
     * already on screen, because a fix arrives asynchronously seconds later -
     * so the button appeared to do nothing forever. The attempt is now a real,
     * bounded operation with a deadline, and this is the job that enforces it.
     *
     * Always cancelled in [cancelTimers], so a stand-down or an incident change
     * can never leave a stale acquisition running or the loading flag stuck on.
     */
    private var locationRecoveryJob: Job? = null

    /**
     * Whether the "Refresh location" / lifecycle-resume recovery path is allowed
     * to start a new acquisition right now.
     *
     * The latch exists purely to stop repeated resumes and recompositions from
     * piling up requests: it is set when an attempt starts and cleared when the
     * app leaves the emergency. It NEVER blocks a retry - an explicit user tap
     * calls [refreshLocationForEmergency] with force, which ignores the latch.
     */
    private var locationRecoveryArmed: Boolean = true

    /**
     * Identity token of the current location-acquisition attempt.
     *
     * Bumped whenever an attempt starts or is invalidated (cancelled by a new
     * tap, or torn down with the incident). A cancelled coroutine that was
     * already past its delay - for example parked on [ProtectionEngine.flowLock]
     * while the user tapped Refresh - checks this token before touching state,
     * so it can never clear the Acquiring flag or null the job reference of the
     * attempt that replaced it.
     */
    private var locationRecoveryAttempt: Int = 0

    /**
     * Identity of the fix the current (or most recent) search was based on.
     *
     * This is what makes "trigger discovery exactly once per fix" enforceable.
     * A LocationManager fix can arrive through several callbacks, and the
     * engine re-publishes on every sensor tick; without a key, each of those
     * could start its own network request. With it, a repeat callback for the
     * same fix is ignored while a genuinely NEW fix is always allowed to search
     * again - including after a NoUsableLocation verdict, which is the specific
     * case that used to be able to suppress discovery permanently.
     *
     * Null means "no usable fix has been searched for", so the first fresh fix
     * is always free to start one.
     */
    private var discoveryRequestKey: String? = null

    /**
     * Bumped whenever a discovery session starts or is forgotten, so a result
     * from a previous emergency (or a superseded request) can never surface
     * after the user has stood down.
     */
    private var resourceDiscoveryGeneration: Int = 0

    /**
     * Last flow this ViewModel already rendered, so a change is handled once.
     * Only ever touched while holding [ProtectionEngine.flowLock].
     */
    private var appliedFlow: AccidentFlowState = AccidentFlowState.Normal

    /** True once a service has confirmed it is actually collecting samples. */
    private var serviceMonitoring = false

    /*
     * THERE IS DELIBERATELY NO `incidentOrigin` FIELD HERE ANY MORE.
     *
     * It used to be a pair of ViewModel fields stamped from inside a listener
     * callback, and it was the source of two real defects:
     *
     *  1. it was never reset, so a simulated incident's SIMULATED origin leaked
     *     into the NEXT incident - including a user tapping "I Need Help" from a
     *     calm screen, which enters EMERGENCY without passing through the screen
     *     that used to stamp it. A genuine emergency was silently denied its
     *     escalation because of an unrelated earlier test.
     *  2. it lived here at all, so an Activity recreation lost it and a rebuilt
     *     ViewModel defaulted to HARDWARE, i.e. to "this was real".
     *
     * The origin now lives on the engine as a property of the INCIDENT, decided
     * in the same atomic step that mints the incident's id. The coordinator reads
     * it from there, so it is correct for every entry path and survives both
     * problems above.
     */

    /**
     * Seconds left in the unconfirmed-safety window. Mirrored into
     * [ResQUiState.confirmationSecondsRemaining] for the screen and counted down
     * by [startConfirmationWindow]; when it reaches zero the flow escalates.
     */
    private var confirmationSecondsRemaining: Int = 0

    init {
        synchronized(protection.flowLock) {
            protection.addListener(this)
            // Re-normalised on load: even a hand-edited or partially corrupted store
            // must not be able to leave the escalation with two primaries or none.
            val storedContacts = EmergencyContactsPolicy.ensureSinglePrimary(
                contactsStore?.contacts().orEmpty(),
            )
            val consent = contactsStore?.isEscalationConsentGranted() == true
            // SMS consent is a SEPARATE, also-default-off decision: agreeing to a
            // phone call is not agreeing to spend mobile credit on texts.
            val smsConsent = contactsStore?.isSmsEscalationConsentGranted() == true
            // Read the CALL_PHONE grant at construction so the Settings screen can
            // immediately tell the truth about what an escalation would do, rather
            // than showing a stale "open your dialer" after the user has granted
            // it in system settings.
            val callPermission = callLauncher?.canPlaceCallsDirectly() == true
            // Likewise for SEND_SMS: the capability the user sees must reflect the
            // grant as it is now, not as it was when the app last asked. The
            // coordinator is the component that owns the sender, so it is the
            // one asked - the ViewModel never talks to the radio itself.
            val smsPermission = escalationCoordinator?.hasSmsPermission() == true
            if (storedContacts.isNotEmpty() || consent || callPermission ||
                smsConsent || smsPermission
            ) {
                state = state.copy(
                    contacts = storedContacts,
                    escalationConsent = consent,
                    callPermissionGranted = callPermission,
                    smsEscalationConsent = smsConsent,
                    smsPermissionGranted = smsPermission,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Emergency contacts - configured before an incident, stored locally
    // ------------------------------------------------------------------

    /**
     * Adds a contact, or updates the one with the same id. Invalid input and a
     * duplicate number are refused rather than stored.
     *
     * @return the contact now in the list, or null when nothing was saved.
     */
    fun saveContact(
        id: String?,
        name: String,
        phoneNumber: String,
        makePrimary: Boolean,
    ): EmergencyContact? {
        synchronized(protection.flowLock) {
            val trimmedName = name.trim()
            val trimmedPhone = phoneNumber.trim()
            if (!EmergencyContactsPolicy.isValid(trimmedName, trimmedPhone)) return null
            val existing = state.contacts
            if (EmergencyContactsPolicy.isDuplicate(existing, trimmedPhone, excludingId = id)) return null
            val isNew = id == null || existing.none { it.id == id }
            if (isNew && existing.size >= EmergencyContactsPolicy.MaxContacts) return null

            val contact = EmergencyContact(
                id = id ?: "contact-${System.currentTimeMillis()}-${existing.size}",
                name = trimmedName,
                phoneNumber = trimmedPhone,
                // The first contact added is the primary by default, so a user who
                // never thinks about it still has a call target.
                isPrimary = makePrimary || existing.isEmpty(),
            )
            publishContacts(EmergencyContactsPolicy.upsert(existing, contact))
            return state.contacts.firstOrNull { it.id == contact.id }
        }
    }

    /** Removes a contact and re-establishes the primary invariant. */
    fun removeContact(id: String) {
        synchronized(protection.flowLock) {
            publishContacts(EmergencyContactsPolicy.remove(state.contacts, id))
        }
    }

    /** Makes [id] the contact an automatic escalation calls first. */
    fun makeContactPrimary(id: String) {
        synchronized(protection.flowLock) {
            publishContacts(EmergencyContactsPolicy.withPrimary(state.contacts, id))
        }
    }

    /**
     * Records the user's explicit consent (or refusal) for an automatic call
     * attempt when an emergency escalates. Persisted, so the decision is asked
     * for once and honoured later.
     */
    fun setEscalationConsent(granted: Boolean) {
        synchronized(protection.flowLock) {
            contactsStore?.setEscalationConsentGranted(granted)
            state = state.copy(escalationConsent = granted)
        }
    }

    /**
     * Records the user's separate, informed consent for alert texts sent from
     * their SIM.
     *
     * Kept apart from [setEscalationConsent] on purpose: the two consents buy
     * different things, and a user who is happy to have ResQ ring their contact
     * may not want their mobile credit spent without being asked again. Turning
     * this on does NOT enable text messages retroactively for an incident in
     * progress whose budget was already spent or refused.
     */
    fun setSmsEscalationConsent(granted: Boolean) {
        synchronized(protection.flowLock) {
            contactsStore?.setSmsEscalationConsentGranted(granted)
            state = state.copy(smsEscalationConsent = granted)
        }
    }

    /**
     * The result of the SEND_SMS prompt.
     *
     * A denial is recorded as a state, never retried automatically and never
     * turned into an error: the emergency screen keeps its manual contact actions,
     * which is the fallback that always works. Granting it later does NOT text
     * anyone for the incident already in progress - that incident's single budget
     * is spent - which is the honest behaviour and matches the call path.
     */
    fun onSmsPermissionResult(granted: Boolean) {
        synchronized(protection.flowLock) {
            state = state.copy(smsPermissionGranted = granted)
            if (!granted && state.smsEscalation !is SmsEscalation.NotAttempted) {
                // Reported honestly instead of being left looking like a generic
                // failure: the automatic text path is unavailable and the screen
                // must explain that the manual contact actions are what remains.
                state = state.copy(smsEscalation = SmsEscalation.SmsPermissionRequired)
            }
        }
    }

    /**
     * The explicit, user-initiated call from the emergency screen. Always
     * allowed - it is the fallback whenever the automatic attempt did not run or
     * could not start - and it never depends on consent, because the user is
     * tapping it right now.
     */
    fun callContact(contact: EmergencyContact) {
        synchronized(protection.flowLock) {
            // ACTION_DIAL even when a direct call is permitted: this is the user
            // tapping right now, so there is no reason to take the call away from
            // them, and it works without any permission. It never consumes the
            // automatic escalation budget - the user is present.
            val outcome = when (openDialer(contact.phoneNumber)) {
                CallLaunchResult.Opened -> PrimaryContactCall.DialerOpened(contact.name)
                CallLaunchResult.NoHandler -> PrimaryContactCall.CouldNotStart
                CallLaunchResult.PermissionNotGranted -> PrimaryContactCall.CouldNotStart
                CallLaunchResult.Placed -> PrimaryContactCall.DialerOpened(contact.name)
            }
            state = state.copy(primaryContactCall = outcome)
        }
    }

    /** Persists the new list and mirrors it into UI state in one step. */
    private fun publishContacts(contacts: List<EmergencyContact>) {
        val normalized = EmergencyContactsPolicy.ensureSinglePrimary(contacts)
        contactsStore?.saveContacts(normalized)
        state = state.copy(contacts = normalized)
    }

    // ------------------------------------------------------------------
    // Protection - user controlled, backed by the foreground service
    // ------------------------------------------------------------------

    /** START_PROTECTION. Starts the foreground service when one is wired. */
    fun startProtection() {
        synchronized(protection.flowLock) {
            if (!canChangeProtection() || state.protection == ProtectionState.ACTIVE) return
            protectionController?.startProtection()
            applyProtectionIntent(ProtectionState.ACTIVE)
        }
    }

    /** STOP_PROTECTION. Stops the foreground service when one is wired. */
    fun stopProtection() {
        synchronized(protection.flowLock) {
            if (!canChangeProtection() || state.protection == ProtectionState.PAUSED) return
            protectionController?.stopProtection()
            applyProtectionIntent(ProtectionState.PAUSED)
        }
    }

    /** The existing Home screen button. Delegates to the two intents above. */
    fun toggleProtection() {
        if (state.protection == ProtectionState.ACTIVE) stopProtection() else startProtection()
    }

    private fun canChangeProtection(): Boolean =
        state.incident == IncidentState.None && state.emergency == EmergencyState.Inactive

    /**
     * Optimistic local update so the button reacts immediately. When a service is
     * wired, the engine snapshot overwrites this a moment later with the real
     * monitoring state - a service that refused to start therefore shows as off.
     */
    private fun applyProtectionIntent(target: ProtectionState) {
        state = state.copy(
            protection = target,
            telemetry = state.telemetry.copy(
                sensors = if (target == ProtectionState.ACTIVE) {
                    SensorStatus.MONITORING
                } else {
                    SensorStatus.PAUSED
                },
                gps = if (target == ProtectionState.ACTIVE) GpsStatus.LOCKED else GpsStatus.STANDBY,
            ),
        )
        protection.detectionEnabled = target == ProtectionState.ACTIVE || isShowingDiagnostics()
    }

    private fun isShowingDiagnostics(): Boolean = state.screen == AppScreen.DIAGNOSTICS

    /**
     * Telemetry as reported by the protection service. GPS is only ever shown as
     * locked when a FRESH fix is actually in hand - not merely when latitude and
     * longitude exist. A stale last-known fix reads as standby, because the pill
     * is a claim about where the phone is now.
     */
    private fun protectionTelemetry(monitoring: Boolean) = state.telemetry.copy(
        sensors = if (monitoring) SensorStatus.MONITORING else SensorStatus.PAUSED,
        gps = if (monitoring && LocationFreshnessPolicy.isFresh(state.sensors.location, now())) {
            GpsStatus.LOCKED
        } else {
            GpsStatus.STANDBY
        },
    )

    private fun now(): Long = System.currentTimeMillis()

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    fun openDiagnostics() {
        synchronized(protection.flowLock) {
            state = state.copy(screen = AppScreen.DIAGNOSTICS)
            // Diagnostics is a read-only preview of the same detector, so scoring
            // stays on while the screen is open even when protection is paused.
            protection.detectionEnabled = true
            startSensorCollection()
            // Picks up a location permission or a location toggle that was changed in
            // system settings since ResQ last looked, so the screen opens showing the
            // truth rather than whatever was true when protection started.
            refreshLocation()
        }
    }

    fun closeDiagnostics() {
        synchronized(protection.flowLock) {
            stopSensorCollection()
            state = state.copy(screen = AppScreen.HOME)
            protection.detectionEnabled = state.protection == ProtectionState.ACTIVE
        }
    }

    fun openSettings() {
        synchronized(protection.flowLock) { state = state.copy(screen = AppScreen.SETTINGS) }
    }

    fun closeSettings() {
        synchronized(protection.flowLock) { state = state.copy(screen = AppScreen.HOME) }
    }

    // ------------------------------------------------------------------
    // Real sensor collection
    // ------------------------------------------------------------------

    /**
     * Asks the shared engine for hardware samples on behalf of the Diagnostics
     * screen. When protection is already running the sensors stay registered -
     * the engine reference counts its sources - so no listener is ever added
     * twice. Does nothing when no gateway is wired (for example in unit tests).
     */
    fun startSensorCollection(restart: Boolean = false) {
        protection.attachSource(ProtectionSource.DIAGNOSTICS_UI, restart = restart)
    }

    /** Releases the Diagnostics consumer. The sensors keep running if protection is on. */
    fun stopSensorCollection() {
        protection.detachSource(ProtectionSource.DIAGNOSTICS_UI)
    }

    /**
     * Asks the gateway to re-check the location permission and the device-wide
     * location toggle. Never re-registers the motion sensors and never restarts
     * detection, so it is safe to call at any time.
     */
    fun refreshLocation() {
        protection.refreshLocation()
    }

    /**
     * Result of the runtime location permission prompt. A denial is recorded as
     * a state rather than an error, so the app simply keeps showing the reason.
     */
    fun onCallPermissionResult(granted: Boolean) {
        synchronized(protection.flowLock) {
            state = state.copy(callPermissionGranted = granted)
            if (!granted) {
                // Reported honestly rather than left to look like a generic
                // failure: the automatic call path is now unavailable, and the
                // screen must explain that the dialer fallback is what remains.
                if (state.primaryContactCall is PrimaryContactCall.CallPlaced ||
                    state.primaryContactCall is PrimaryContactCall.DialerOpened
                ) {
                    state = state.copy(primaryContactCall = PrimaryContactCall.CallPermissionRequired)
                }
                return
            }
            // A newly granted permission must not retro-actively dial anyone:
            // the attempt budget for this incident is already spent (or was never
            // used because consent or a contact was missing). The upgrade applies
            // to the NEXT incident, which is the honest and expected behaviour.
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        synchronized(protection.flowLock) {
            if (granted) {
                // A newly granted permission needs a fresh registration to take effect.
                startSensorCollection(restart = true)
                // Permission may have been granted from the emergency's location
                // screen. This is an explicit user action, so it goes through the
                // same full recovery path as Refresh - including the forced
                // re-registration - rather than merely re-reading a snapshot that
                // cannot have changed yet. startResourceDiscovery()'s own guards
                // keep it from duplicating a request that is already in flight.
                if (protection.flow is AccidentFlowState.Emergency) {
                    refreshLocationForEmergency()
                } else if (state.resourceDiscovery is ResourceDiscoveryState.NoUsableLocation) {
                    startResourceDiscovery()
                }
            } else {
                state = state.copy(
                    sensors = state.sensors.copy(
                        location = LocationState(availability = LocationAvailability.PERMISSION_DENIED),
                    ),
                    // Reported as a recovery reason rather than left implicit, so
                    // the screen can point at the exact action that would fix it.
                    locationRecovery = LocationRecoveryState.Unavailable(
                        LocationRecoveryReason.PERMISSION_DENIED,
                    ),
                )
            }
        }
    }

    /**
     * The latest prototype assessment of the real sensor samples. Detection runs
     * while protection is active only; pausing protection stops scoring, it never
     * raises an alert.
     */
    fun currentDetection(): DetectionAssessment = state.detection

    // ------------------------------------------------------------------
    // Incident
    // ------------------------------------------------------------------

    fun simulatePossibleCollision() {
        runSimulationScenario(SimulationSamples.possibleCollision())
    }

    fun simulateHighConfidenceCollision() {
        runSimulationScenario(SimulationSamples.highConfidenceCollision())
    }

    /** Incident screen: the user confirms they are safe. */
    fun confirmUserOk() = reduce(AccidentEvent.UserOk)

    /** Diagnostics control: clears the alert (and returns home if it was open). */
    fun simulateUserOk() = reduce(AccidentEvent.UserOk)

    /**
     * Diagnostics control for the unanswered countdown. With an alert already
     * open this is the real "countdown expired" transition; from a calm screen it
     * jumps straight to the unconfirmed state so the screen can be inspected.
     *
     * CRITICAL SAFETY: this control runs on the Developer/Test screen, so any
     * incident it produces is a SIMULATED one and must never place a call. The
     * origin is stamped here - and consumed by [onFlowChanged] on BOTH incident
     * entries (PossibleAccident and the direct-to-Confirmation path) - because
     * leaving it at the HARDWARE default let a single Test Mode tap escalate all
     * the way to an automatic ACTION_CALL to the primary contact.
     */
    fun simulateNoResponse() {
        synchronized(protection.flowLock) {
            val previous = protection.flow
            val event = if (previous is AccidentFlowState.PossibleAccident) {
                AccidentEvent.NoResponse
            } else {
                AccidentEvent.ForceConfirmation
            }
            reduce(event, simulated = true)
        }
    }

    /** "I need help now" - opens the emergency experience. */
    fun requestEmergencyHelp() = reduce(AccidentEvent.NeedHelp)

    // ------------------------------------------------------------------
    // Emergency
    // ------------------------------------------------------------------

    fun standDown() {
        synchronized(protection.flowLock) {
            cancelTimers()
            // The incident is over: nothing may still be ringing or talking, and
            // the next incident starts with a fresh call budget (the engine
            // releases it the moment the flow leaves EMERGENCY).
            silenceAlert()
            reduce(AccidentEvent.Reset, screenOverride = AppScreen.HOME)
        }
    }

    // ------------------------------------------------------------------
    // Emergency - nearby resource discovery
    // ------------------------------------------------------------------

    /**
     * Explicit retry from the error state. Re-evaluates the location snapshot
     * and starts at most one new request; never runs on its own.
     */
    fun retryResourceDiscovery() {
        synchronized(protection.flowLock) {
            if (resourceDiscoveryJob?.isActive == true) return
            // An explicit retry must not be blocked by the fix key the previous
            // attempt used: the user is asking for this search to run again, so
            // the key is cleared first and the request proceeds.
            discoveryRequestKey = null
            startResourceDiscovery()
        }
    }

    /**
     * Location recovery offered by the location-unavailable state: re-check what
     * the platform reports and re-evaluate the search against the refreshed
     * snapshot. Only ever invoked by an explicit user action.
     */
    fun recoverResourceLocation() {
        refreshLocationForEmergency()
    }

    /**
     * The real "Refresh location" implementation, and the fix for the reported
     * failure where the button never recovered anything.
     *
     * What was wrong: it called the idempotent [ProtectionEngine.refreshLocation]
     * and then immediately re-evaluated the snapshot. After the user had turned
     * Location back ON, (a) the platform had already discarded the old provider
     * registration, so the idempotent refresh re-requested nothing, and (b) even
     * if a fix had arrived, it arrives asynchronously - so the synchronous
     * re-read still saw "no fix" and re-published the same unavailable state. The
     * user was stuck pressing a button that could not succeed.
     *
     * What it does now:
     *  1. asks the PLATFORM what is currently true (permission + Location toggle)
     *     instead of trusting the last published snapshot;
     *  2. forces a re-registration of the location listeners, which is what
     *     actually recovers a session whose provider was switched off;
     *  3. if a usable fix is already available, searches immediately;
     *  4. otherwise enters a bounded Acquiring state and clears it on success,
     *     failure, timeout or cancellation - never leaving a spinner forever.
     *
     * @param force true for an explicit user tap or a lifecycle return, which must
     *   work even while another automatic attempt is in flight. Automatic
     *   resume-driven recovery passes false and is skipped while one is running,
     *   so returning to ResQ repeatedly cannot stack up requests.
     */
    fun refreshLocationForEmergency(force: Boolean = true) {
        synchronized(protection.flowLock) {
            if (protection.flow !is AccidentFlowState.Emergency) return
            if (!force) {
                // Automatic path only: never compete with an attempt already in
                // flight, and only ever re-arm after the latch was cleared.
                if (!locationRecoveryArmed || locationRecoveryJob?.isActive == true) return
            }
            // An explicit tap always supersedes an in-flight attempt: the previous
            // one is cancelled (which clears its loading flag) rather than left to
            // race the new one.
            locationRecoveryJob?.cancel()
            locationRecoveryJob = null
            beginLocationRecovery(force = true)
        }
    }

    /**
     * Runs one bounded location-acquisition attempt and decides what it means.
     *
     * MUST be called while holding [ProtectionEngine.flowLock].
     *
     * The order matters and encodes the whole recovery policy:
     *  1. re-ask the platform first - a user who has not actually enabled Location
     *     yet must be told what to do rather than made to wait out a timeout;
     *  2. force the registration, which is what recovers a provider that was
     *     switched off and back on;
     *  3. re-read the snapshot AFTER that, because the forced reconciliation can
     *     publish a seeded last-known fix synchronously;
     *  4. only then either search or wait.
     */
    private fun beginLocationRecovery(force: Boolean) {
        // Invalidates every earlier attempt: one whose coroutine is already past
        // its delay will see the token mismatch and leave the new attempt alone.
        val attempt = ++locationRecoveryAttempt
        val platform = protection.locationPlatformState()
        if (force) protection.forceLocationRefresh() else protection.refreshLocation()

        when (platform) {
            LocationPlatformState.LOCATION_OFF -> {
                // Nothing to wait for: the system itself says no. Reported
                // immediately and honestly, with the settings action offered.
                finishLocationRecovery(LocationRecoveryState.Unavailable(LocationRecoveryReason.LOCATION_OFF))
                return
            }

            LocationPlatformState.PERMISSION_MISSING -> {
                finishLocationRecovery(
                    LocationRecoveryState.Unavailable(LocationRecoveryReason.PERMISSION_DENIED),
                )
                return
            }

            LocationPlatformState.READY, LocationPlatformState.UNKNOWN -> Unit
        }

        // The forced reconciliation may have published a fresh or seeded fix
        // synchronously, so the snapshot is re-read here rather than reused from
        // before the call.
        if (protection.emergencyLocationSnapshot().isFresh) {
            finishLocationRecovery(LocationRecoveryState.Idle)
            startResourceDiscovery()
            return
        }

        state = state.copy(locationRecovery = LocationRecoveryState.Acquiring)
        // Re-arm for the next automatic (lifecycle) attempt only after this one
        // has finished, so repeated resumes cannot each start their own request.
        locationRecoveryArmed = false

        val startedAtMillis = now()
        locationRecoveryJob = scope.launch {
            // A real deadline. Without one, a phone that never gets a fix indoors
            // would sit on Acquiring forever - precisely the "stuck spinner" the
            // user reported, just moved.
            while (now() - startedAtMillis < locationRecoveryTimeoutMillis) {
                delay(LocationRecoveryPollMillis)
                if (protection.emergencyLocationSnapshot().isFresh) {
                    synchronized(protection.flowLock) {
                        // Superseded while waiting for the lock: the attempt that
                        // replaced this one owns the state now.
                        if (attempt != locationRecoveryAttempt) return@synchronized
                        // A fix arrived: leave the recovery state before
                        // searching, so the unavailable screen can never be
                        // showing "waiting" while results are already loading.
                        finishLocationRecovery(LocationRecoveryState.Idle)
                        startResourceDiscovery()
                    }
                    return@launch
                }
            }
            synchronized(protection.flowLock) {
                if (attempt != locationRecoveryAttempt) return@synchronized
                val stillMissing = !protection.emergencyLocationSnapshot().isFresh
                // A late fix that arrived between the loop's last check and here
                // must not be reported as a timeout.
                if (!stillMissing) {
                    finishLocationRecovery(LocationRecoveryState.Idle)
                    startResourceDiscovery()
                    return@synchronized
                }
                finishLocationRecovery(
                    LocationRecoveryState.Unavailable(LocationRecoveryReason.TIMED_OUT),
                )
            }
        }
    }

    /**
     * Leaves the recovery state, always. Called on success, failure, timeout and
     * cancellation so no path can leave the loading flag on.
     *
     * MUST be called while holding [ProtectionEngine.flowLock].
     */
    private fun finishLocationRecovery(next: LocationRecoveryState) {
        locationRecoveryJob = null
        locationRecoveryArmed = true
        if (state.locationRecovery != next) {
            state = state.copy(locationRecovery = next)
        }
    }

/**
     * Whether a snapshot is precise enough to search around.
 *
 * Delegates to [LocationFixCachePolicy] so the number lives in exactly one place,
 * and treats a snapshot with no coordinates as unusable by definition - which
 * keeps the accuracy rule and the missing-coordinate rule from being answered
 * two different ways.
 */
private fun isAccurateEnoughToSearch(location: EmergencyLocationSnapshot): Boolean {
    val latitude = location.latitude ?: return false
    val longitude = location.longitude ?: return false
    if (!LocationFixCachePolicy.isPlausiblePosition(latitude, longitude)) return false
    return LocationFixCachePolicy.isAccurateEnoughForSearch(
        LastKnownLocationFix(
            latitude = latitude,
            longitude = longitude,
            timestampMillis = location.timestampMillis,
            accuracyMeters = location.accuracyMeters,
            isCoarse = location.isCoarse,
            provider = location.provider,
        ),
    )
}

/**
 * Lifecycle resume while the emergency is open - the "user came back from
 * Android Location settings" hook.
     *
     * Deliberately idempotent: it only acts when a recovery is actually needed
     * (the position is not fresh and the search has not already succeeded) and
     * never when the user has no location problem at all, so it cannot spam
     * requests on every recomposition or every trip to another screen. The
     * manual Refresh button remains available for the case where this is wrong.
     */
    fun onEmergencyScreenResumed() {
        synchronized(protection.flowLock) {
            if (protection.flow !is AccidentFlowState.Emergency) return
            if (state.emergency == EmergencyState.Inactive) return
            // Nothing to recover: a fresh fix is already in hand, or discovery
            // has already produced real results for this incident.
            if (protection.emergencyLocationSnapshot().isFresh) return
            if (state.resourceDiscovery is ResourceDiscoveryState.Loaded) return
            refreshLocationForEmergency(force = false)
        }
    }

    /**
     * Starts the nearby-resource search. Called once per emergency - when the
     * state machine enters the resource-discovery stage - and again on
     * [retryResourceDiscovery], on an explicit location refresh, or when a
     * genuinely new fresh fix arrives.
     *
     * The search uses the emergency location snapshot exactly as it is right
     * now. A missing or stale fix short-circuits to
     * [ResourceDiscoveryState.NoUsableLocation] without any request, so ResQ
     * never searches around a position it cannot vouch for.
     *
     * Two invariants make this safe to call from more places than it used to be,
     * without ever duplicating a search:
     *
     *  - a request already in flight is never duplicated;
     *  - [discoveryRequestKey] remembers WHICH fix the current or most recent
     *    search was based on. A fix arriving through several callbacks therefore
     *    triggers one search, while a genuinely newer fix is always allowed to
     *    re-open a previously failed one - which is what stops an old
     *    NoUsableLocation verdict from permanently suppressing discovery.
     */
    private fun startResourceDiscovery() {
        // A request already in flight is never duplicated.
        if (resourceDiscoveryJob?.isActive == true) return

        val location = protection.emergencyLocationSnapshot()
        val repository = resourceRepository
        // searchKey() is null for a missing coordinate AND for the (0, 0)
        // placeholder, so requiring a non-null key is exactly the right test for
        // "is there a real position here?". It keeps the classic open-ocean
        // sentinel from ever being treated as a fix or sent to the backend.
        val key = location.searchKey()
        // Freshness AND accuracy. A fresh-but-vague fix (a cell-tower estimate with
        // a multi-kilometre radius) would otherwise be searched around as if it
        // were a position, returning a plausible-looking list of hospitals that may
        // be nowhere near the user. Requiring both makes the guarantee explicit
        // instead of implicit in the search key.
        val usable = key != null &&
            location.freshness == FixFreshness.FRESH &&
            isAccurateEnoughToSearch(location)

        // Same fix as the one already searched: a new callback for it must not
        // produce a second network request.
        if (usable && discoveryRequestKey == key) return

        // Only now is a new generation minted, so a superseded request can never
        // publish over the verdict of the one replacing it.
        val generation = ++resourceDiscoveryGeneration
        when {
            !usable -> {
                // Remember that this verdict belongs to "nothing usable right now",
                // so the first genuinely fresh AND accurate fix is free to start a
                // real search.
                discoveryRequestKey = null
                publishResourceDiscovery(ResourceDiscoveryState.NoUsableLocation)
            }

            repository == null -> {
                // No backend wired (plain unit tests, for example): reported as
                // a retryable failure - never silently filled with mock data.
                discoveryRequestKey = key
                publishResourceDiscovery(ResourceDiscoveryState.Failed(ResourceSearchFailure.NETWORK))
            }

            else -> {
                // A usable key exists, which means both coordinates do. Taken as
                // non-null here so the request cannot be built from a guess.
                val latitude = checkNotNull(location.latitude)
                val longitude = checkNotNull(location.longitude)
                // Recorded BEFORE the request goes out, so a fix that arrives
                // through several callbacks while the request is in flight still
                // produces exactly one search.
                discoveryRequestKey = key
                publishResourceDiscovery(ResourceDiscoveryState.Loading)
                resourceDiscoveryJob = scope.launch {
                    // The API client bounds every phase and never throws, but a
                    // repository is not required to be that well behaved: any
                    // failure here must still become a usable, retryable state
                    // rather than an exception escaping into the scope.
                    //
                    // Cancellation is deliberately rethrown. Swallowing it would
                    // both break structured concurrency and let a superseded
                    // request publish a result the user already backed out of.
                    val result = try {
                        repository.searchNearbyResources(
                            latitude = latitude,
                            longitude = longitude,
                            incidentType = IncidentTypePossibleAccident,
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        ResourceSearchResult.Failure(ResourceSearchFailure.NETWORK)
                    }
                    // The user stood down or a newer search took over: drop it.
                    if (generation != resourceDiscoveryGeneration) return@launch
                    synchronized(protection.flowLock) {
                        if (generation != resourceDiscoveryGeneration) return@synchronized
                        publishResourceDiscovery(result.toDiscoveryState())
                        // A completed request no longer occupies the job slot, so
                        // an explicit retry must be able to start a new one. The
                        // key is kept, so retrying deliberately still works even
                        // though the fix itself has not changed.
                        resourceDiscoveryJob = null
                    }
                }
            }
        }
    }

    /**
     * Forgets any in-flight search so a late result can never resurface.
     *
     * [discoveryRequestKey] is cleared with it: that is what gives the NEXT
     * incident a fresh attempt budget, so a search is never suppressed by a
     * request belonging to an incident that has already ended.
     */
    private fun resetResourceDiscovery() {
        synchronized(protection.flowLock) {
            resourceDiscoveryGeneration++
            resourceDiscoveryJob?.cancel()
            resourceDiscoveryJob = null
            discoveryRequestKey = null
            if (state.resourceDiscovery != ResourceDiscoveryState.Idle) {
                publishResourceDiscovery(ResourceDiscoveryState.Idle)
            }
        }
    }

    /**
     * Cancels any running location attempt and returns to the resting state.
     *
     * MUST be called while holding [ProtectionEngine.flowLock].
     *
     * Part of leaving the emergency, so no acquisition can outlive the incident
     * that justified it and the next one starts from a clean, unarmed state.
     */
    private fun resetLocationRecovery() {
        locationRecoveryJob?.cancel()
        locationRecoveryJob = null
        locationRecoveryArmed = true
        locationRecoveryAttempt++
        if (state.locationRecovery != LocationRecoveryState.Idle) {
            state = state.copy(locationRecovery = LocationRecoveryState.Idle)
        }
    }

    /**
     * Applies a discovery outcome. The emergency projection is rebuilt in the
     * same update so [ResQUiState.emergency] and [ResQUiState.resourceDiscovery]
     * can never disagree, even when the result lands before the flow reaches
     * the READY stage.
     */
    private fun publishResourceDiscovery(next: ResourceDiscoveryState) {
        state = state.copy(
            resourceDiscovery = next,
            emergency = emergencyStateFor(protection.flow, next),
        )
    }

    // ------------------------------------------------------------------
    // Diagnostics simulation controls
    // ------------------------------------------------------------------

    /**
     * Simulates firm braking. The synthetic samples are scored by the very same
     * detector that scores real hardware, and a hard brake is expected to stay
     * NORMAL: braking is an input signal, never an accident, and can never move
     * the app towards an emergency on its own.
     */
    fun simulateHardBraking() {
        synchronized(protection.flowLock) {
            detectionJob?.cancel()
            val samples = SimulationSamples.hardBraking()
            val assessment = protection.evaluateSynthetic(samples)
            logDetection("HARD_BRAKING", assessment)
            val last = samples.last()
            state = state.copy(
                telemetry = state.telemetry.copy(
                    detection = DetectionState.HARD_BRAKING,
                    accidentScore = config.hardBrakingDisplayScore,
                    accelerometer = last.accelerometer.reading ?: TelemetryState().accelerometer,
                    gyroscope = last.gyroscope.reading ?: TelemetryState().gyroscope,
                ),
            )
            detectionJob = scope.launch {
                delay(HardBrakingDisplayMillis)
                synchronized(protection.flowLock) {
                    if (state.telemetry.detection == DetectionState.HARD_BRAKING) {
                        state = state.copy(
                            telemetry = state.telemetry.copy(
                                detection = DetectionState.IDLE,
                                accidentScore = BaselineAccidentScore,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun resetSimulation() {
        synchronized(protection.flowLock) {
            cancelTimers()
            resetResourceDiscovery()
            // The location attempt belongs to the discarded incident, so it must
            // not survive into the next one with its loading flag still set.
            resetLocationRecovery()
            // A discarded incident must also release the location source it held,
            // or a simulation that raised an emergency would leave the gateway
            // registered after the flow is already back to NORMAL.
            protection.detachSource(ProtectionSource.EMERGENCY_UI)
            silenceAlert()
            confirmationSecondsRemaining = 0
            protection.releaseCountdown(CountdownOwner.APP_UI)
            appliedFlow = AccidentFlowState.Normal
            val screen = if (state.screen == AppScreen.DIAGNOSTICS) {
                AppScreen.DIAGNOSTICS
            } else {
                AppScreen.HOME
            }
            protection.reset()
            // The incident is cleared, the user's contacts and their consent are not:
            // a reset must never silently delete the emergency network.
            state = ResQUiState(
                screen = screen,
                contacts = state.contacts,
                escalationConsent = state.escalationConsent,
                // The granted permission is a property of the device, not of the
                // incident, so it survives a reset - losing it would silently
                // downgrade escalation for no reason the user could understand.
                callPermissionGranted = state.callPermissionGranted,
                // The SMS equivalent: a discarded incident releases its one-message
                // budget, but the user's consent and the device's grant survive -
                // the next incident is allowed to send, exactly as the next one is
                // allowed to call.
                smsEscalationConsent = state.smsEscalationConsent,
                smsPermissionGranted = state.smsPermissionGranted,
            )
            protection.detectionEnabled = state.protection == ProtectionState.ACTIVE
            // Restart collection so the real hardware states are re-published after
            // the reset instead of showing the "never started" defaults.
            if (screen == AppScreen.DIAGNOSTICS) startSensorCollection(restart = true)
        }
    }

    /**
     * Starts the simulated telemetry stream. Called once from the composition so
     * that plain unit tests can drive the state machine without background
     * timers. It only feeds [TelemetryState]; real samples come
     * from the [SensorGateway] and the real samples are what the detector reads.
     */
    fun startTelemetryStream() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = scope.launch {
            while (true) {
                delay(TelemetryPulseMillis)
                pulseTelemetry()
            }
        }
    }

    // ------------------------------------------------------------------
    // Timing hooks (internal so the state machine can be unit tested)
    // ------------------------------------------------------------------

    internal fun onCountdownTick() {
        if (protection.flow !is AccidentFlowState.PossibleAccident) return
        reduce(AccidentEvent.CountdownTick)
    }

    internal fun advanceEmergency() {
        reduce(AccidentEvent.EmergencyAdvanced)
    }

    internal fun pulseTelemetry() {
        synchronized(protection.flowLock) {
            val telemetry = state.telemetry
            if (telemetry.sensors != SensorStatus.MONITORING) return
            // While a simulation is active the mock IMU values are held steady so
            // the spike stays readable; only the resting state drifts.
            if (telemetry.detection != DetectionState.IDLE) return
            state = state.copy(
                telemetry = telemetry.copy(
                    accidentScore = (telemetry.accidentScore - ScoreDecayPerPulse)
                        .coerceAtLeast(BaselineAccidentScore),
                    accelerometer = SensorReading(
                        x = around(0.00f, 0.06f, -0.4f, 0.4f),
                        y = around(0.05f, 0.06f, -0.4f, 0.4f),
                        z = around(9.81f, 0.08f, 9.2f, 10.4f),
                    ),
                    gyroscope = SensorReading(
                        x = around(0.01f, 0.03f, -0.5f, 0.5f),
                        y = around(-0.02f, 0.03f, -0.5f, 0.5f),
                        z = around(0.00f, 0.03f, -0.5f, 0.5f),
                    ),
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Shared pipeline -> UI state
    // ------------------------------------------------------------------

    /**
     * Single place where the shared protection pipeline becomes UI state. Called
     * synchronously by the engine for every snapshot, whether the samples came
     * from the foreground service or from the Diagnostics screen, so the UI can
     * never show a stale protection state after the Activity is recreated.
     */
    override fun onProtectionSnapshot(snapshot: ProtectionSnapshot) {
        // The projection runs under the engine's flow lock - the engine already
        // delivers snapshots with that lock held, and the explicit block keeps
        // that true even for a direct addListener callback. Without it, a
        // concurrent user intent (a back tap) could interleave with this
        // read-modify-write and be silently overwritten.
        synchronized(protection.flowLock) {
            val telemetry = state.telemetry
            val flow = snapshot.flow
            val next = state.copy(
                sensors = sensorsForDisplay(snapshot.sensors),
                detection = snapshot.detection,
                incident = flow.incidentState(config.alertCountdownSeconds),
                confirmationSecondsRemaining = if (flow == AccidentFlowState.Confirmation) {
                    confirmationSecondsRemaining
                } else {
                    null
                },
                emergency = emergencyStateFor(flow),
                emergencyLocation = emergencyLocationFor(flow),
                telemetry = telemetry.copy(
                    // A hard-braking simulation owns the tile until its timer ends.
                    detection = if (telemetry.detection == DetectionState.HARD_BRAKING) {
                        DetectionState.HARD_BRAKING
                    } else {
                        flow.detectionState
                    },
                    accidentScore = if (snapshot.detection.level == DetectionLevel.NORMAL) {
                        telemetry.accidentScore
                    } else {
                        snapshot.detection.confidenceFraction
                    },
                ),
            )
            if (next != state) state = next

            // Only meaningful when a service is wired: without one there is nothing
            // that could report monitoring, and the plain UI default is kept.
            if (protectionController != null) {
                syncProtectionWithService(snapshot.sources.contains(ProtectionSource.SERVICE))
            }

            if (flow != appliedFlow) {
                val previous = appliedFlow
                appliedFlow = flow
                onFlowChanged(previous, flow)
            }

            // Run on every snapshot, not only on a flow change: the escalation
            // coordinator updates its outcome asynchronously (the carrier verdict
            // authorises the follow-up call) and republishes so that lands here.
            syncEscalationOutcome()

            if (flow is AccidentFlowState.Emergency) {
                retryDiscoveryForFreshFix()
            }
        }
    }

    /**
     * Re-opens resource discovery when a genuinely new fresh fix arrives.
     *
     * This is the missing link in the reported bug. Discovery used to start only
     * on the flow's transition into the search stage, so if it was blocked once by
     * NoUsableLocation it stayed blocked for the rest of the incident - even after
     * Location was switched on and a perfectly good fix arrived. The user could
     * press Refresh forever and nothing would change.
     *
     * Guarded so it can never poll or duplicate: it only runs while the previous
     * verdict was NoUsableLocation (a failure the user must be able to retry, but
     * not something to re-request on every callback), and
     * [startResourceDiscovery] itself ignores a repeated key, so several
     * callbacks carrying the same fix still produce one request.
     */
    private fun retryDiscoveryForFreshFix() {
        if (state.resourceDiscovery !is ResourceDiscoveryState.NoUsableLocation) return
        val location = protection.emergencyLocationSnapshot()
        if (!location.isFresh) return
        finishLocationRecovery(LocationRecoveryState.Idle)
        startResourceDiscovery()
    }

    /**
     * The position snapshot captured while the app is in the EMERGENCY state, and
     * null the moment it leaves.
     *
     * Deliberately NOT frozen at the instant EMERGENCY was entered: it tracks the
     * best position available right now, so a fix that arrives during the
     * emergency is picked up. It is local state only - nothing sends it anywhere.
     */
    private fun emergencyLocationFor(flow: AccidentFlowState): EmergencyLocationSnapshot? =
        if (flow is AccidentFlowState.Emergency) {
            protection.emergencyLocationSnapshot()
        } else {
            null
        }

    /**
     * Live motion numbers are only displayed by Diagnostics. Mirroring 15 IMU
     * samples a second into Compose state while another screen is showing would
     * recompose the whole UI for nothing, so only the location channel - which
     * the Home screen does surface, once a second - is kept up to date there.
     */
    private fun sensorsForDisplay(sensors: SensorState): SensorState =
        if (state.screen == AppScreen.DIAGNOSTICS) {
            sensors
        } else {
            state.sensors.copy(location = sensors.location)
        }

    /**
     * Mirrors what the protection service actually did. Protection is shown as
     * off until the foreground service is collecting samples - opening
     * Diagnostics only previews the sensors, it never claims to be protecting.
     */
    private fun syncProtectionWithService(serviceAttached: Boolean) {
        serviceMonitoring = serviceAttached
        val target = if (serviceAttached) ProtectionState.ACTIVE else ProtectionState.PAUSED
        val telemetry = protectionTelemetry(serviceAttached)
        if (target == state.protection && telemetry == state.telemetry) return
        state = state.copy(protection = target, telemetry = telemetry)
    }

    /** True while a wired service has confirmed it is collecting samples. */
    fun isServiceMonitoring(): Boolean = serviceMonitoring

    /**
     * Screen and timer consequences of a flow change. Timers start and stop only
     * when the flow actually crosses a boundary, so an ongoing sample stream can
     * never restart a running countdown.
     */
    private fun onFlowChanged(previous: AccidentFlowState, flow: AccidentFlowState) {
        logTransition(previous, flow)

        val screen = screenFor(flow)
        if (screen != AppScreen.DIAGNOSTICS) leaveDiagnostics()
        if (screen != state.screen) state = state.copy(screen = screen)

        // A flow change also ends the temporary hard-braking display, otherwise its
        // auto-clear timer would put the telemetry tile back into HARD_BRAKING.
        if (state.telemetry.detection != DetectionState.HARD_BRAKING) detectionJob?.cancel()

        if (previous is AccidentFlowState.PossibleAccident && flow !is AccidentFlowState.PossibleAccident) {
            countdownJob?.cancel()
            protection.releaseCountdown(CountdownOwner.APP_UI)
            // The first window is over - either the user answered or nobody did.
            // Whatever was being spoken is cut off here; the second warning, when
            // it is due, is played by the unconfirmed-safety branch below.
            silenceAlert()
        }
        if (flow == AccidentFlowState.Confirmation && previous != AccidentFlowState.Confirmation) {
            // A Developer/Test "simulate no response" can open this state directly
            // from a calm screen, without ever passing through PossibleAccident.
            // Its simulated origin was already recorded by the engine when the
            // ForceConfirmation event opened this incident, so there is nothing
            // to stamp here any more.
            // Stage C: the "are you okay?" window closed with no answer. This is
            // the dedicated no-response state - it speaks the second warning and
            // runs a bounded window that escalates on its own, so the flow can
            // never wait forever on a screen nobody is looking at.
            confirmationSecondsRemaining = config.confirmationWindowSeconds
            state = state.copy(confirmationSecondsRemaining = confirmationSecondsRemaining)
            if (protection.requestCountdown(CountdownOwner.APP_UI)) startConfirmationWindow()
            announce(AlertCue.NO_RESPONSE)
        }
        if (previous == AccidentFlowState.Confirmation && flow != AccidentFlowState.Confirmation) {
            // This cancels the window that may be running this very call stack when
            // it expires. Kotlin cancellation is cooperative, so cancelling the
            // current job here cannot cut the rest of this handler short: the
            // escalation below still runs in the same synchronous pass.
            confirmationJob?.cancel()
            confirmationSecondsRemaining = 0
            state = state.copy(confirmationSecondsRemaining = null)
            protection.releaseCountdown(CountdownOwner.APP_UI)
            // Silence only when the incident actually ends: going on to the
            // emergency must not cut the no-response warning off mid-sentence.
            if (flow !is AccidentFlowState.Emergency) silenceAlert()
        }
        if (flow is AccidentFlowState.PossibleAccident && previous !is AccidentFlowState.PossibleAccident) {
            // A new incident begins: remember where it came from. The single
            // call budget lives on the engine and was released when the
            // A new incident begins. Its origin was decided by the engine, atomically
            // with the incident id; there is nothing to infer here.
            state = state.copy(primaryContactCall = PrimaryContactCall.NotAttempted)
            // A visible UI outranks the background service for the countdown.
            if (protection.requestCountdown(CountdownOwner.APP_UI)) {
                startCountdown()
                // Stage B: the audible first alert. This branch runs only on the
                // transition into the countdown, so ongoing samples cannot repeat
                // or restart it.
                announce(AlertCue.POSSIBLE_ACCIDENT)
            }
        }
        if (previous is AccidentFlowState.Emergency && flow !is AccidentFlowState.Emergency) {
            emergencyJob?.cancel()
            // Leaving the emergency forgets the search; a late network result
            // can no longer resurface after the user has stood down. The engine
            // releases the one-per-incident call budget in the same transition.
            resetResourceDiscovery()
            // The acquisition attempt belongs to this incident too, so it is
            // cancelled and its loading flag cleared - otherwise a timed-out or
            // cancelled attempt could survive into the next incident.
            resetLocationRecovery()
            // Released only here, and only because the emergency ended - never on
            // a screen change or a rotation.
            protection.detachSource(ProtectionSource.EMERGENCY_UI)
            silenceAlert()
            state = state.copy(primaryContactCall = PrimaryContactCall.NotAttempted)
        }
        if (flow is AccidentFlowState.Emergency && previous !is AccidentFlowState.Emergency) {
            // ROOT-CAUSE FIX for the reported device failure.
            //
            // Every location entry point is guarded by `sources.isEmpty()`, so an
            // emergency reached with protection paused - or with the foreground
            // service killed by an OEM battery manager, which is routine on
            // Vivo/OriginOS - owned no listener at all. Every recovery action was
            // then a silent no-op and the position could never arrive, no matter
            // how many times the user pressed Refresh.
            //
            // Attaching here guarantees the gateway is registered for the whole
            // incident. The engine reference counts sources, so this does not
            // double-register anything when the service is running too.
            protection.attachSource(ProtectionSource.EMERGENCY_UI)
            startEmergencySequence()
            // A new emergency starts with a clean discovery slate.
            resetResourceDiscovery()
            // The no-response warning was already spoken when the unconfirmed
            // window opened; it is deliberately not repeated here. An explicit
            // "I need help" came from the user, so the first message is stopped
            // and nothing more is read at them. The emergency screen is reached
            // either way, without waiting on audio.
            if (previous != AccidentFlowState.Confirmation) silenceAlert()
            // Stage D: at most one automatic call attempt, for this incident only.
            // The engine-wide ledger makes a recreated Activity skip the attempt
            // when the original one already made it.
// The alert text and the follow-up contact call are dispatched by the
            // process-wide EmergencyEscalationCoordinator, which is an engine
            // listener in its own right. They are deliberately NOT triggered
            // here: this ViewModel unregisters in onCleared(), so triggering
            // them from here would make the whole escalation depend on the
            // Activity still existing. The coordinator observes the same
            // transition and acts whether or not any UI is alive. This class
            // only mirrors the outcome, which happens in onProtectionSnapshot.
            // A ViewModel recreated after the flow already reached the search
            // stage (Activity destroyed mid-emergency) re-enters here with a
            // fresh, Idle discovery state - start it once so the emergency
            // screen does not sit on a spinner whose request never begins.
            if (flow.stage.ordinal >= EmergencyStage.SEARCHING_RESOURCES.ordinal &&
                state.resourceDiscovery == ResourceDiscoveryState.Idle
            ) {
                startResourceDiscovery()
            }
        }
        // The search fires exactly once: on the transition INTO the resource
        // discovery stage. Stage-agnostic snapshot traffic, recomposition and
        // screen rotation all stay on this side of the guard.
        if (flow is AccidentFlowState.Emergency &&
            flow.stage == EmergencyStage.SEARCHING_RESOURCES &&
            (previous as? AccidentFlowState.Emergency)?.stage != EmergencyStage.SEARCHING_RESOURCES
        ) {
            startResourceDiscovery()
        }
    }


    /**
     * The single point in the app that opens the system dialer for an explicitly
     * user-initiated call.
     *
     * A missing launcher (plain unit tests) is reported as "could not start"
     * rather than silently succeeding, so no test and no build can ever appear to
     * have dialed when it did not.
     */
    private fun openDialer(number: String): CallLaunchResult =
        callLauncher?.openDialer(number) ?: CallLaunchResult.NoHandler

    private fun screenFor(flow: AccidentFlowState): AppScreen = when (flow) {
        AccidentFlowState.Normal ->
            if (state.screen == AppScreen.INCIDENT) AppScreen.HOME else state.screen

        is AccidentFlowState.PossibleAccident, AccidentFlowState.Confirmation -> AppScreen.INCIDENT
        is AccidentFlowState.Emergency -> AppScreen.EMERGENCY
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            while (state.incident is IncidentState.PossibleImpact) {
                delay(countdownTickMillis)
                onCountdownTick()
            }
        }
    }

    /**
     * The unconfirmed-safety window.
     *
     * When it reaches zero the flow escalates on its own by offering
     * [AccidentEvent.NoResponse] to the state machine - exactly the event the
     * Diagnostics control sends, so the automatic and the manual path end in the
     * same place. The loop stops on its own the moment the state is no longer
     * [IncidentState.Confirmation] (the user answered, or the flow moved on), so
     * a cancelled incident can never escalate late.
     */
    private fun startConfirmationWindow() {
        confirmationJob?.cancel()
        confirmationJob = scope.launch {
            while (state.incident is IncidentState.Confirmation && confirmationSecondsRemaining > 0) {
                delay(countdownTickMillis)
                synchronized(protection.flowLock) {
                    confirmationSecondsRemaining = (confirmationSecondsRemaining - 1).coerceAtLeast(0)
                    state = state.copy(confirmationSecondsRemaining = confirmationSecondsRemaining)
                }
            }
            // Loop exited with a window that ran out and no user answer: escalate.
            if (confirmationSecondsRemaining == 0 && state.incident is IncidentState.Confirmation) {
                reduce(AccidentEvent.NoResponse)
            }
        }
    }

    private fun startEmergencySequence() {
        emergencyJob?.cancel()
        emergencyJob = scope.launch {
            delay(EmergencyStepMillis)
            advanceEmergency()
            delay(EmergencyStepMillis)
            advanceEmergency()
            delay(EmergencyResourcesMillis)
            advanceEmergency()
        }
    }

    // ------------------------------------------------------------------
    // State machine plumbing
    // ------------------------------------------------------------------

    /**
     * Single funnel into the accident flow: hand the event to the shared state
     * machine and then render the verdict into [ResQUiState]. Everything the flow
     * implies (screen, countdown, emergency progression) is applied by
     * [onFlowChanged], whether the event came from this class or from the
     * foreground service.
     */
    private fun reduce(
        event: AccidentEvent,
        prototypeScore: Float? = null,
        screenOverride: AppScreen? = null,
        /**
         * True when this event originates from Developer/Test Mode.
         *
         * Passed straight through to the ENGINE, which records it against the
         * incident it is opening. The previous design kept a pending field here
         * and stamped it later from inside a listener callback, which made an
         * incident's origin depend on which listener ran first and - worse -
         * left the field STALE when an incident began without passing through
         * the screen that set it. A user who simulated an incident, stood down
         * and later tapped "I Need Help" from Home used to inherit SIMULATED and
         * have a genuine emergency silently suppressed. Deciding it at the
         * transition removes that whole class of bug.
         */
        simulated: Boolean = false,
    ) {
        // The transition, its snapshot listeners and the screen override are one
        // atomic step: a tap handler on the main thread and a timer on a
        // background dispatcher can therefore never half-apply an event, and the
        // override can never be overwritten by a snapshot racing this call.
        synchronized(protection.flowLock) {
            val flow = protection.submit(event, simulated = simulated)
            applyTelemetry(flow, prototypeScore)
            screenOverride?.let { override ->
                if (state.screen != override) state = state.copy(screen = override)
            }
        }
    }

    /** Telemetry projection applied for an explicit event, simulation included. */
    private fun applyTelemetry(flow: AccidentFlowState, prototypeScore: Float?) {
        val telemetry = state.telemetry
        val detectionState = flow.detectionState
        val next = telemetry.copy(
            detection = detectionState,
            accidentScore = when {
                detectionState == DetectionState.IDLE -> BaselineAccidentScore
                prototypeScore != null -> prototypeScore
                else -> telemetry.accidentScore
            },
        )
        if (next != telemetry) state = state.copy(telemetry = next)
    }

    /**
     * Scores a synthetic sequence through the real detector and then offers the
     * verdict to the state machine, so a simulated scenario and real hardware
     * share one code path.
     */
    private fun runSimulationScenario(samples: List<SensorState>) {
        synchronized(protection.flowLock) {
            val assessment = protection.evaluateSynthetic(samples)
            // The origin travels with the event itself, into the engine, which
            // records it against the incident it opens. Nothing depends on
            // listener ordering and nothing can be left stale by a later incident.
            reduce(
                AccidentEvent.Detected(assessment.level),
                prototypeScore = assessment.confidenceFraction,
                simulated = true,
            )
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun cancelTimers() {
        countdownJob?.cancel()
        confirmationJob?.cancel()
        emergencyJob?.cancel()
        detectionJob?.cancel()
        // Every path that ends or replaces an incident goes through here, so this
        // is the single place a location attempt can be left running. Scope
        // cancellation still completes it; clearing the flag here guarantees the
        // UI cannot be left showing a spinner with no job behind it. The attempt
        // token is bumped so a cancelled coroutine parked on the lock cannot
        // touch the next incident's state.
        locationRecoveryJob?.cancel()
        locationRecoveryJob = null
        locationRecoveryArmed = true
        locationRecoveryAttempt++
    }

    /** Releases the hardware listeners when a flow replaces the Diagnostics screen. */
    private fun leaveDiagnostics() {
        if (state.screen == AppScreen.DIAGNOSTICS) stopSensorCollection()
    }

    private fun around(center: Float, noise: Float, min: Float, max: Float): Float =
        (center + Random.nextFloat() * 2f * noise - noise).coerceIn(min, max)

    /*
     * Audio never gets in the way of the incident flow. The production
     * announcer already guards its own platform calls, but a failure here must
     * still be contained: a broken TTS engine or a silenced device can lose the
     * spoken warning, never the emergency screen.
     */

    private fun announce(cue: AlertCue) {
        runCatching { alertAnnouncer?.announce(cue) }
    }

    private fun silenceAlert() {
        runCatching { alertAnnouncer?.silence() }
    }

    /**
     * Mirrors the process-wide coordinator's outcome into UI state.
     *
     * Purely a projection. The coordinator decides and acts on its own; this only
     * copies what it already did so the emergency screen can say something true.
     * It runs on every snapshot because the carrier verdict usually arrives
     * after the engine has already published the transition.
     */
    private fun syncEscalationOutcome() {
        val coordinator = escalationCoordinator ?: return
        // Read on EVERY snapshot, not only on a flow change: the carrier verdict
        // that authorises the follow-up call usually arrives long after the
        // engine published the transition into EMERGENCY, and the coordinator
        // republishes precisely so this copy happens.
        //
        // A NotAttempted outcome is never written over what is already shown. It
        // is the coordinator's "I have not done anything" value, and copying it
        // would erase the truthful result of a MANUAL call the user just made
        // from the emergency screen.
        val sms = coordinator.smsOutcome
        val call = coordinator.callOutcome
        val nextSms = if (sms == SmsEscalation.NotAttempted) state.smsEscalation else sms
        val nextCall =
            if (call == PrimaryContactCall.NotAttempted) state.primaryContactCall else call
        if (state.smsEscalation == nextSms && state.primaryContactCall == nextCall) return
        state = state.copy(smsEscalation = nextSms, primaryContactCall = nextCall)
    }

    /**
     * Projection of the domain flow onto the emergency model that the screens
     * render. The stage decides whether the resource section exists at all;
     * [discovery] decides what it currently says - loading, results, none
     * found, an error, or an unusable location - so hardcoded fixtures can
     * never stand in for a live answer.
     */
    private fun emergencyStateFor(
        flow: AccidentFlowState,
        discovery: ResourceDiscoveryState = state.resourceDiscovery,
    ): EmergencyState = when (flow) {
        is AccidentFlowState.Emergency -> when (flow.stage) {
            EmergencyStage.LOCATING -> EmergencyState.Preparing(EmergencyProgress.LOCATING)
            EmergencyStage.LINKING_CONTACTS -> EmergencyState.Preparing(EmergencyProgress.LINKING_CONTACTS)
            EmergencyStage.SEARCHING_RESOURCES -> EmergencyState.Preparing(EmergencyProgress.SEARCHING_RESOURCES)
            EmergencyStage.READY -> EmergencyState.Ready(
                resources = (discovery as? ResourceDiscoveryState.Loaded)?.resources.orEmpty(),
            )
        }

        else -> EmergencyState.Inactive
    }

    /**
     * Only meaningful flow changes are logged. Individual accelerometer or
     * gyroscope samples are never logged, so Logcat stays readable.
     */
    private fun logTransition(previous: AccidentFlowState, flow: AccidentFlowState) {
        if (previous == flow) return
        Log.i(LogTag, "Accident flow: ${previous.name} -> ${flow.name}")
    }

    private fun logDetection(label: String, assessment: DetectionAssessment) {
        Log.i(LogTag, "$label evaluated -> ${assessment.level.name} (${assessment.reasonSummary})")
    }

    override fun onCleared() {
        protection.removeListener(this)
        protection.releaseCountdown(CountdownOwner.APP_UI)
        // Speech and the alert tone are external resources: they are released with
        // the holder that owns the incident flow, never left running.
        alertAnnouncer?.release()
        stopSensorCollection()
        scope.cancel()
        super.onCleared()
    }

    private companion object {
        const val LogTag = "ResQDetection"
        const val EmergencyStepMillis = 1_200L
        const val EmergencyResourcesMillis = 1_600L
        const val TelemetryPulseMillis = 700L
        const val HardBrakingDisplayMillis = 2_500L
        const val ScoreDecayPerPulse = 0.01f

        /**
         * How long an explicit location attempt waits for a fix before it gives
         * up and says so.
         *
         * A GNSS fix outdoors is typically a few seconds; indoors it can be
         * minutes. 20 s is long enough that a user who just enabled Location and
         * is walking outside is not told "timed out" prematurely, and short
         * enough that the screen never looks stuck. The user can always retry.
         *
         * Injected per ViewModel rather than read from here, so a test can drive a
         * real timeout instead of asserting that one could not happen.
         */
        const val LocationRecoveryPollMillis = 250L
    }
}


/**
 * Projection of the domain flow onto the incident and emergency models that
 * already exist. Kept next to the ViewModel so the detection package stays free
 * of mock resources and navigation.
 *
 * [alertCountdownSeconds] is the user's flat override from Settings (null →
 * per-level values), carried into [IncidentState.PossibleImpact.totalSeconds]
 * so the countdown ring draws the true proportion of a configured window.
 */
private fun AccidentFlowState.incidentState(alertCountdownSeconds: Int?): IncidentState =
    when (this) {
        AccidentFlowState.Normal, is AccidentFlowState.Emergency -> IncidentState.None
        AccidentFlowState.Confirmation -> IncidentState.Confirmation
        is AccidentFlowState.PossibleAccident -> {
            val confidence = level.confidence ?: DetectionConfidence.POSSIBLE
            IncidentState.PossibleImpact(
                secondsRemaining = secondsRemaining,
                confidence = confidence,
                totalSeconds = alertCountdownSeconds ?: confidence.countdownSeconds,
            )
        }
    }

private val AccidentFlowState.detectionState: DetectionState
    get() = when (this) {
        AccidentFlowState.Normal, is AccidentFlowState.Emergency -> DetectionState.IDLE
        AccidentFlowState.Confirmation -> DetectionState.POSSIBLE_IMPACT
        is AccidentFlowState.PossibleAccident ->
            if (level == DetectionLevel.HIGH_CONFIDENCE) {
                DetectionState.HIGH_CONFIDENCE_IMPACT
            } else {
                DetectionState.POSSIBLE_IMPACT
            }
    }

/** Repository outcome -> what the resource section should say. */
private fun ResourceSearchResult.toDiscoveryState(): ResourceDiscoveryState = when (this) {
    is ResourceSearchResult.Success -> ResourceDiscoveryState.Loaded(resources)
    ResourceSearchResult.NoResults -> ResourceDiscoveryState.Loaded(emptyList())
    is ResourceSearchResult.Failure -> ResourceDiscoveryState.Failed(reason)
}
