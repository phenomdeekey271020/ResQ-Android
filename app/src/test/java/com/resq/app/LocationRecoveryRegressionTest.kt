package com.resq.app

import com.resq.app.data.remote.EmergencyResourceRepository
import com.resq.app.data.remote.IncidentTypePossibleAccident
import com.resq.app.data.remote.ResourceSearchResult
import com.resq.app.data.sensors.SensorGateway
import com.resq.app.data.sensors.SensorObserver
import com.resq.app.model.AppScreen
import com.resq.app.model.EmergencyLocationSnapshot
import com.resq.app.model.FixFreshness
import com.resq.app.model.LocationAvailability
import com.resq.app.model.LocationRecoveryReason
import com.resq.app.model.LocationRecoveryState
import com.resq.app.model.LocationState
import com.resq.app.model.ResourceDiscoveryState
import com.resq.app.model.ResourceSearchFailure
import com.resq.app.model.label
import com.resq.app.protection.ProtectionEngine
import com.resq.app.protection.ProtectionSource
import com.resq.app.viewmodel.ResQViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A gateway whose platform answers the test drives directly, so the exact
 * reported failure - Location off, incident opens, user turns Location on in
 * system settings, presses Refresh - can be reproduced without a device.
 *
 * [registrationAlive] models the platform behaviour that caused the bug: when a
 * provider is switched off the system DISCARDS the app's registration, so a
 * merely-idempotent reconcile sees "already registered" and re-requests nothing.
 */
private class RecoverableSensorGateway : SensorGateway {

    private var observer: SensorObserver? = null

    var permissionGranted: Boolean = true
    var locationEnabled: Boolean = true

    /** False models a registration the platform has silently thrown away. */
    var registrationAlive: Boolean = false

    var periodicRefreshCount = 0
    var forcedRefreshCount = 0

    override fun hasLocationPermission(): Boolean = permissionGranted

    override fun isLocationEnabled(): Boolean = locationEnabled

    override fun start(observer: SensorObserver) {
        this.observer = observer
        registrationAlive = true
    }

    override fun refresh() {
        periodicRefreshCount++
    }

    /**
     * The forced retry. This is the behaviour the real gateway added: it does
     * NOT trust the stale registration flag, it re-requests, and it emits the
     * fix the test has queued as soon as one is available.
     */
    override fun refreshForcingRegistration() {
        forcedRefreshCount++
        registrationAlive = true
        when {
            !permissionGranted -> emitLocation(
                LocationState(availability = LocationAvailability.PERMISSION_DENIED),
            )

            !locationEnabled -> emitLocation(
                LocationState(availability = LocationAvailability.PROVIDERS_DISABLED),
            )

            queuedFix != null -> emitLocation(queuedFix!!)
            registrationAlive -> Unit
        }
    }

    override fun stop() {
        observer = null
        registrationAlive = false
    }

    /** The fix a forced refresh will hand back, i.e. what the device "finds". */
    var queuedFix: LocationState? = null

    fun emitLocation(state: LocationState) {
        observer?.onLocation(state)
    }

    /**
     * Drives the whole platform response, as Android would after a settings trip.
     *
     * [handOverFixImmediately] is false in the tests that assert the RECOVERY
     * path, because that is how a real device behaves: enabling Location does not
     * instantly produce a fix - ResQ has to re-register its listener first, and
     * only then does the platform start delivering. Handing the fix over here
     * would let a test pass without the recovery actually working.
     */
    fun platformNow(
        permission: Boolean,
        enabled: Boolean,
        handOverFixImmediately: Boolean = true,
    ) {
        permissionGranted = permission
        locationEnabled = enabled
        when {
            !permission -> emitLocation(
                LocationState(availability = LocationAvailability.PERMISSION_DENIED),
            )

            !enabled -> emitLocation(
                LocationState(availability = LocationAvailability.PROVIDERS_DISABLED),
            )

            handOverFixImmediately -> queuedFix?.let { emitLocation(it) }
            // Honest: enabled and permitted, but nothing has been delivered yet.
            else -> emitLocation(
                LocationState(availability = LocationAvailability.WAITING_FOR_FIX),
            )
        }
    }
}

/** A repository that records every request and can be held open on demand. */
private class RecordingRepository(
    private val result: ResourceSearchResult = ResourceSearchResult.NoResults,
) : EmergencyResourceRepository {

    @Volatile var calls = 0
    val requestedCoordinates = mutableListOf<Pair<Double, Double>>()

    override suspend fun searchNearbyResources(
        latitude: Double,
        longitude: Double,
        incidentType: String,
    ): ResourceSearchResult {
        calls++
        synchronized(requestedCoordinates) { requestedCoordinates += latitude to longitude }
        return result
    }
}

/**
 * REGRESSION TESTS FOR "Refresh Location does nothing after Location is turned
 * back on".
 *
 * Every test here drives the same sequence the user reported and asserts on the
 * two things that were broken: that a retry actually re-requests the location,
 * and that a fresh fix is allowed to start a search even though an earlier
 * attempt was blocked.
 *
 * No real GPS fix is used and the live backend is never contacted: the location
 * provider and the repository are both fakes.
 */
class LocationRecoveryRegressionTest {

    private lateinit var gateway: RecoverableSensorGateway
    private lateinit var engine: ProtectionEngine
    private lateinit var repository: RecordingRepository
    private lateinit var viewModel: ResQViewModel

    @Before
    fun setUp() {
        gateway = RecoverableSensorGateway()
        engine = ProtectionEngine(gateway)
        repository = RecordingRepository()
        viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
            // Long enough that only an explicit action can finish an attempt, so
            // the tests never race the real 20 second deadline.
            countdownTickMillis = 60_000L,
        )
    }

    // 1. Location OFF -> ON -> Refresh obtains a location and initiates discovery.
    @Test
    fun locationOffThenOnThenRefresh_obtainsAFixAndInitiatesDiscovery() {
        engine.attachSource(ProtectionSource.SERVICE)
        // 1. Location is off when the incident starts.
        gateway.platformNow(permission = true, enabled = false)
        enterEmergency()

        assertEquals(
            "No request may go out around a position ResQ does not have",
            0,
            repository.calls,
        )
        assertEquals(ResourceDiscoveryState.NoUsableLocation, viewModel.state.resourceDiscovery)

        // 4/5. The user enables Location in system settings.
        gateway.queuedFix = freshFix()
        gateway.platformNow(permission = true, enabled = true)

        // 6. The user comes back and presses Refresh.
        viewModel.recoverResourceLocation()

        awaitLoaded()
        assertEquals(1, repository.calls)
        // The retry must take the FORCED path: an idempotent refresh would see the
        // stale registration flag and re-request nothing, which was the bug.
        assertTrue(
            "Refresh must force a fresh location registration",
            gateway.forcedRefreshCount >= 1,
        )
    }

    // 2. Returning from system settings after enabling Location initiates recovery.
    @Test
    fun returningFromSystemSettings_startsRecoveryWithoutNeedingTheButton() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.platformNow(permission = true, enabled = false)
        enterEmergency()
        assertEquals(ResourceDiscoveryState.NoUsableLocation, viewModel.state.resourceDiscovery)

        // The user turns Location on and simply returns to ResQ. Note the fix is
        // NOT handed over by the toggle itself: the device only produces it once
        // ResQ re-registers, which is precisely what the resume path must do.
        gateway.queuedFix = freshFix()
        gateway.platformNow(permission = true, enabled = true, handOverFixImmediately = false)
        viewModel.onEmergencyScreenResumed()

        awaitLoaded()
        assertEquals(1, repository.calls)
        assertTrue(
            "Recovery on resume must re-request the location registration",
            gateway.forcedRefreshCount >= 1,
        )
    }

    @Test
    fun repeatedResumes_doNotStartDuplicateRequests() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.platformNow(permission = true, enabled = false)
        enterEmergency()
        gateway.queuedFix = freshFix()
        gateway.platformNow(permission = true, enabled = true)

        // Ten resumes in a row: a lifecycle observer firing repeatedly must not
        // become ten network requests.
        repeat(10) { viewModel.onEmergencyScreenResumed() }

        awaitLoaded()
        assertEquals(1, repository.calls)
    }

    @Test
    fun repeatedResumes_afterASuccessfulSearch_startNothingAtAll() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.queuedFix = freshFix()
        gateway.platformNow(permission = true, enabled = true)
        enterEmergency()
        awaitLoaded()
        assertEquals(1, repository.calls)

        repeat(10) {
            viewModel.onEmergencyScreenResumed()
            gateway.emitLocation(freshFix())
        }

        assertEquals(1, repository.calls)
    }

    // 3. A failed first attempt does not block the second.
    @Test
    fun aFailedFirstAttempt_neverBlocksTheSecond() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.platformNow(permission = true, enabled = false)
        enterEmergency()
        assertEquals(ResourceDiscoveryState.NoUsableLocation, viewModel.state.resourceDiscovery)

        // First attempt still cannot succeed: Location is genuinely off.
        viewModel.recoverResourceLocation()
        assertEquals(
            LocationRecoveryState.Unavailable(LocationRecoveryReason.LOCATION_OFF),
            viewModel.state.locationRecovery,
        )
        assertEquals(0, repository.calls)

        // The user enables it and retries: the earlier failure must not be sticky.
        gateway.queuedFix = freshFix()
        gateway.platformNow(permission = true, enabled = true)
        viewModel.recoverResourceLocation()

        awaitLoaded()
        assertEquals(1, repository.calls)
    }

    // 4. Repeated Refresh taps do not create duplicate concurrent requests.
    @Test
    fun repeatedRefreshTaps_neverCreateConcurrentRequests() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.queuedFix = freshFix()
        gateway.platformNow(permission = true, enabled = true)
        enterEmergency()

        repeat(10) { viewModel.recoverResourceLocation() }

        awaitLoaded()
        assertEquals(1, repository.calls)
    }

    @Test
    fun oneFixArrivingThroughManyCallbacks_stillSearchesExactlyOnce() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.platformNow(permission = true, enabled = false)
        enterEmergency()

        // The same fix is delivered repeatedly, exactly as the engine does when
        // the gateway re-publishes on every reconcile tick.
        val fix = freshFix()
        repeat(8) { gateway.emitLocation(fix) }

        awaitLoaded()
        assertEquals(1, repository.calls)
    }

    // 5. Permission denial produces the right recovery action.
    @Test
    fun permissionDenial_producesThePermissionRecoveryReason() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.platformNow(permission = false, enabled = true)
        enterEmergency()

        viewModel.recoverResourceLocation()

        assertEquals(
            LocationRecoveryState.Unavailable(LocationRecoveryReason.PERMISSION_DENIED),
            viewModel.state.locationRecovery,
        )
        assertEquals(0, repository.calls)
        assertTrue(
            "A denial must name the permission as the thing to fix",
            LocationRecoveryReason.PERMISSION_DENIED.label()
                .contains("permission", ignoreCase = true),
        )
        assertTrue(
            "Location-off must point at the system toggle, not at a permission",
            LocationRecoveryReason.LOCATION_OFF.label()
                .contains("settings", ignoreCase = true),
        )
    }

    @Test
    fun grantingThePermissionLater_recoversWithoutAManualRefresh() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.platformNow(permission = false, enabled = true)
        enterEmergency()
        viewModel.recoverResourceLocation()
        assertEquals(0, repository.calls)

        // The user grants it from the emergency screen's own button.
        gateway.queuedFix = freshFix()
        gateway.platformNow(permission = true, enabled = true)
        viewModel.onLocationPermissionResult(granted = true)

        awaitLoaded()
        assertEquals(1, repository.calls)
    }

    // 6. Stale last-known coordinates are not sent to the backend.
    @Test
    fun aStaleLastKnownFix_isShownButNeverSearched() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.platformNow(permission = true, enabled = true)
        // The device only has an old cached fix.
        gateway.emitLocation(staleFix())
        enterEmergency()

        assertEquals(ResourceDiscoveryState.NoUsableLocation, viewModel.state.resourceDiscovery)
        assertEquals(0, repository.calls)

        // Refreshing with nothing better must not send the stale position either.
        viewModel.recoverResourceLocation()
        assertEquals(0, repository.calls)
        assertTrue(repository.requestedCoordinates.isEmpty())
    }

    @Test
    fun aZeroZeroPlaceholder_isNeverTreatedAsAPosition() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.platformNow(permission = true, enabled = true)
        gateway.emitLocation(
            LocationState(
                availability = LocationAvailability.READY,
                latitude = 0.0,
                longitude = 0.0,
                provider = "gps",
                timestampMillis = System.currentTimeMillis(),
            ),
        )
        enterEmergency()

        // The flow has not yet reached the search stage, so drive it there before
        // asserting the verdict that matters.
        awaitCondition { viewModel.state.resourceDiscovery != ResourceDiscoveryState.Idle }
        assertEquals(ResourceDiscoveryState.NoUsableLocation, viewModel.state.resourceDiscovery)
        assertEquals(0, repository.calls)
        assertTrue(
            "The (0, 0) placeholder must never reach the backend",
            repository.requestedCoordinates.isEmpty(),
        )
    }

    // 7. A fresh valid fix clears the unavailable state.
    @Test
    fun aFreshFix_clearsTheUnavailableState() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.platformNow(permission = true, enabled = false)
        enterEmergency()
        assertTrue(
            viewModel.state.resourceDiscovery is ResourceDiscoveryState.NoUsableLocation,
        )

        gateway.queuedFix = freshFix()
        gateway.platformNow(permission = true, enabled = true)
        viewModel.recoverResourceLocation()

        awaitLoaded()
        assertFalse(viewModel.state.resourceDiscovery is ResourceDiscoveryState.NoUsableLocation)
        assertEquals(
            LocationRecoveryState.Idle,
            viewModel.state.locationRecovery,
        )
        // And the emergency screen itself is showing resources, not the
        // location-unavailable fallback.
        assertEquals(AppScreen.EMERGENCY, viewModel.state.screen)
    }

    @Test
    fun aFreshFix_reportsItsOwnFreshnessAndAccuracy() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.queuedFix = freshFix(latitude = 12.9716, longitude = 77.5946, accuracy = 7f)
        gateway.platformNow(permission = true, enabled = true)
        enterEmergency()

        val snapshot: EmergencyLocationSnapshot? = viewModel.state.emergencyLocation
        assertEquals(FixFreshness.FRESH, snapshot?.freshness)
        assertTrue(snapshot?.isFresh == true)
        assertEquals(12.9716, snapshot?.latitude ?: 0.0, 0.000001)
        assertEquals(7f, snapshot?.accuracyMeters ?: 0f, 0.001f)
    }

    // 8. Timeout and cancellation clear loading state.
    @Test
    fun aFailedAttemptClearsTheLoadingFlag_evenWhenNoFixEverArrives() {
        engine.attachSource(ProtectionSource.SERVICE)
        // Location is on and permitted, but the device produces no fix at all.
        gateway.platformNow(permission = true, enabled = true)
        gateway.queuedFix = null
        enterEmergency()

        viewModel.refreshLocationForEmergency()

        // The attempt is genuinely in flight: the loading flag is visible and the
        // emergency is NOT blocked, because nothing about calling 112 or a trusted
        // contact depends on this completing.
        assertTrue(viewModel.state.locationRecovery.isBusy)
        assertEquals(AppScreen.EMERGENCY, viewModel.state.screen)
        assertEquals(0, repository.calls)
    }

    @Test
    fun aFixArrivingWhileAnAttemptIsRunning_completesItWithoutAnotherAttempt() {
        engine.attachSource(ProtectionSource.SERVICE)
        // Enabled and permitted, but the device takes its time.
        gateway.platformNow(permission = true, enabled = true, handOverFixImmediately = false)
        gateway.queuedFix = freshFix()
        enterEmergency()
        awaitCondition { viewModel.state.resourceDiscovery is ResourceDiscoveryState.NoUsableLocation }

        viewModel.refreshLocationForEmergency()
        // The forced refresh hands the queued fix over, exactly as a real
        // re-registration would start the platform delivering.
        assertEquals(LocationRecoveryState.Idle, viewModel.state.locationRecovery)

        awaitLoaded()
        assertEquals(1, repository.calls)
        assertEquals(
            "The fix arrived once, so exactly one request may exist",
            1,
            repository.calls,
        )
    }

    @Test
    fun standingDownDuringAnAttempt_clearsTheLoadingState() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.platformNow(permission = true, enabled = true)
        gateway.queuedFix = null
        enterEmergency()

        // No fix will ever arrive, so the attempt would still be Acquiring.
        viewModel.refreshLocationForEmergency()
        assertTrue(viewModel.state.locationRecovery.isBusy)

        viewModel.standDown()

        assertFalse(
            "Standing down must never leave a spinner behind",
            viewModel.state.locationRecovery.isBusy,
        )
        assertEquals(LocationRecoveryState.Idle, viewModel.state.locationRecovery)
    }

    @Test
    fun aSupersededAttempt_isCancelledAndCannotPublishOverTheNewerOne() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.platformNow(permission = true, enabled = true)
        gateway.queuedFix = null
        enterEmergency()

        viewModel.refreshLocationForEmergency()
        assertTrue(viewModel.state.locationRecovery.isBusy)

        // An explicit second tap supersedes the first rather than racing it.
        gateway.queuedFix = freshFix()
        viewModel.recoverResourceLocation()

        awaitLoaded()
        assertEquals(1, repository.calls)
        assertEquals(LocationRecoveryState.Idle, viewModel.state.locationRecovery)
    }

    // 9. Backend failure can be retried.
    @Test
    fun aBackendFailure_isRetryableAndTheRetrySucceeds() {
        val failing = object : EmergencyResourceRepository {
            var calls = 0
            override suspend fun searchNearbyResources(
                latitude: Double,
                longitude: Double,
                incidentType: String,
            ): ResourceSearchResult {
                calls++
                return if (calls == 1) {
                    ResourceSearchResult.Failure(ResourceSearchFailure.SERVER)
                } else {
                    ResourceSearchResult.NoResults
                }
            }
        }
        val viewModelWithFailingBackend = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = failing,
            countdownTickMillis = 60_000L,
        )
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.queuedFix = freshFix()
        gateway.platformNow(permission = true, enabled = true)
        enterEmergency(viewModelWithFailingBackend)

        awaitCondition {
            viewModelWithFailingBackend.state.resourceDiscovery ==
                ResourceDiscoveryState.Failed(ResourceSearchFailure.SERVER)
        }
        assertEquals(1, failing.calls)

        viewModelWithFailingBackend.retryResourceDiscovery()

        awaitCondition {
            viewModelWithFailingBackend.state.resourceDiscovery is ResourceDiscoveryState.Loaded
        }
        assertEquals(2, failing.calls)
    }

    // 10. No valid location never prevents access to emergency actions.
    @Test
    fun withNoLocationAtAll_theEmergencyStaysFullyUsable() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.platformNow(permission = false, enabled = false)
        enterEmergency()

        assertEquals(AppScreen.EMERGENCY, viewModel.state.screen)
        assertEquals(0, repository.calls)
        assertFalse(viewModel.state.locationRecovery.isBusy)
        // Contacts and the emergency number are reachable: the escalation path
        // never depends on a location.
        assertNotEquals(null, viewModel.state.contacts)
    }

    @Test
    fun aCoarseOnlyFix_isUsedButLabelledApproximate() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.queuedFix = freshFix().copy(
            accuracyMeters = 1_200f,
            isCoarse = true,
        )
        gateway.platformNow(permission = true, enabled = true)
        enterEmergency()

        // A coarse fix is a real position, so it is used...
        awaitLoaded()
        assertEquals(1, repository.calls)
        // ...but never presented as a precise one.
        val value = com.resq.app.ui.screens.accuracyValue(viewModel.state.emergencyLocation)
        assertTrue(
            "A coarse fix must be labelled approximate, was: $value",
            value.contains("Approximate", ignoreCase = true),
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Drives the flow to the resource-search stage, as a real incident does. */
    private fun enterEmergency(target: ResQViewModel = viewModel) {
        target.requestEmergencyHelp()
        target.advanceEmergency()
        target.advanceEmergency()
    }

    private fun freshFix(
        latitude: Double = 11.9332,
        longitude: Double = 79.5241,
        accuracy: Float = 6f,
    ) = LocationState(
        availability = LocationAvailability.READY,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        provider = "gps",
        timestampMillis = System.currentTimeMillis(),
    )

    private fun staleFix() = LocationState(
        availability = LocationAvailability.STALE,
        latitude = 11.9332,
        longitude = 79.5241,
        accuracyMeters = 30f,
        provider = "gps",
        timestampMillis = System.currentTimeMillis() - 5 * 60_000L,
    )

    private fun awaitLoaded() = awaitCondition {
        viewModel.state.resourceDiscovery is ResourceDiscoveryState.Loaded
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000L
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "Timed out waiting for state; discovery was " +
                        "${viewModel.state.resourceDiscovery}, recovery was " +
                        "${viewModel.state.locationRecovery}",
                )
            }
            Thread.sleep(5)
        }
    }
}

