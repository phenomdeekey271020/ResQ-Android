package com.resq.app

import com.resq.app.data.remote.EmergencyResourceRepository
import com.resq.app.data.remote.IncidentTypePossibleAccident
import com.resq.app.data.remote.ResourceSearchResult
import com.resq.app.data.sensors.InMemoryLocationFixStore
import com.resq.app.data.sensors.SensorGateway
import com.resq.app.data.sensors.SensorObserver
import com.resq.app.model.AppScreen
import com.resq.app.model.FixFreshness
import com.resq.app.model.LastKnownLocationFix
import com.resq.app.model.LocationAvailability
import com.resq.app.model.LocationFixAvailability
import com.resq.app.model.LocationFixCachePolicy
import com.resq.app.model.LocationRecoveryReason
import com.resq.app.model.LocationRecoveryState
import com.resq.app.model.LocationState
import com.resq.app.model.ResourceDiscoveryState
import com.resq.app.protection.ProtectionEngine
import com.resq.app.protection.ProtectionSource
import com.resq.app.ui.screens.locationBasisValue
import com.resq.app.viewmodel.ResQViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PRE-INCIDENT LOCATION CACHE AND THE STUCK-EMERGENCY ROOT CAUSE.
 *
 * The reported device failure is reproduced here directly: an emergency is
 * reached with NO protection source attached - protection paused, or an OEM
 * battery manager having killed the foreground service - and Location off. Before
 * the fix, every recovery action was a silent no-op, because the engine's location
 * entry points are guarded by "is anybody listening?" and nobody was.
 *
 * No real GPS fix, no real file and no real network call is involved: the
 * provider, the repository and the cache are all fakes.
 */
class LocationResilienceRegressionTest {

    private val now = System.currentTimeMillis()

    // ------------------------------------------------------------------
    // Cache policy - pure, no Android, no device
    // ------------------------------------------------------------------

    private fun fix(
        ageMillis: Long = 0L,
        accuracy: Float? = 6f,
        coarse: Boolean = false,
        latitude: Double = 11.9332,
        longitude: Double = 79.5241,
        timestamp: Long = now - ageMillis,
    ) = LastKnownLocationFix(
        latitude = latitude,
        longitude = longitude,
        timestampMillis = timestamp,
        accuracyMeters = accuracy,
        isCoarse = coarse,
        provider = "gps",
    )

    @Test
    fun aRecentFix_isShownAsUsable() {
        val verdict = LocationFixCachePolicy.availability(fix(ageMillis = 30_000L), now)

        assertTrue(verdict is LocationFixAvailability.Usable)
    }

    @Test
    fun anExpiredFix_isNeverShownAsAPosition() {
        val verdict = LocationFixCachePolicy.availability(
            fix(ageMillis = LocationFixCachePolicy.RetentionMillis + 1L),
            now,
        )

        assertTrue(verdict is LocationFixAvailability.Unusable)
        assertNull(LocationFixCachePolicy.best(fix(ageMillis = 60L * 60_000L), now))
    }

    @Test
    fun aVagueFix_isNotOfferedAsAPosition() {
        val verdict = LocationFixCachePolicy.availability(
            fix(accuracy = LocationFixCachePolicy.UsableAccuracyMeters + 1f),
            now,
        )

        assertTrue(verdict is LocationFixAvailability.Unusable)
    }

    /**
     * THE MOST IMPORTANT ASSERTION IN THIS FILE.
     *
     * A cached fix that is two seconds old is still not evidence of where the
     * phone is right now. If this ever returned FRESH, ResQ would search for
     * hospitals around a position nobody is standing at.
     */
    @Test
    fun aTwoSecondOldCachedFix_isStillNeverReportedAsFresh() {
        val snapshot = LocationFixCachePolicy.historicalSnapshot(fix(ageMillis = 2_000L), now)

        assertNotNull(snapshot)
        assertEquals(FixFreshness.STALE, snapshot?.freshness)
        assertFalse(snapshot?.isFresh == true)
    }

    @Test
    fun theCacheTimestampAndAccuracyAreRetainedExactly() {
        val stored = fix(ageMillis = 45_000L, accuracy = 12f, coarse = true)
        val snapshot = LocationFixCachePolicy.historicalSnapshot(stored, now)

        assertEquals(stored.latitude, snapshot?.latitude ?: 0.0, 0.0000001)
        assertEquals(stored.longitude, snapshot?.longitude ?: 0.0, 0.0000001)
        assertEquals(stored.timestampMillis, snapshot?.timestampMillis)
        assertEquals(12f, snapshot?.accuracyMeters ?: 0f, 0.0001f)
        assertEquals(45_000L, snapshot?.ageMillis)
        // Approximate is preserved, so the UI can keep saying "approximate".
        assertTrue(snapshot?.isCoarse == true)
    }

    @Test
    fun theZeroZeroPlaceholder_canNeverBeCachedOrShown() {
        val placeholder = fix(latitude = 0.0, longitude = 0.0)

        assertFalse(LocationFixCachePolicy.isCacheable(placeholder))
        assertNull(LocationFixCachePolicy.historicalSnapshot(placeholder, now))
        assertFalse(LocationFixCachePolicy.isPlausiblePosition(0.0, 0.0))
    }

    @Test
    fun outOfRangeAndNonFiniteCoordinates_areRejected() {
        assertFalse(LocationFixCachePolicy.isPlausiblePosition(91.0, 0.5))
        assertFalse(LocationFixCachePolicy.isPlausiblePosition(11.0, 181.0))
        assertFalse(LocationFixCachePolicy.isPlausiblePosition(Double.NaN, 79.5))
        assertFalse(LocationFixCachePolicy.isPlausiblePosition(11.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun aFixWithoutATimestamp_isNeverCacheable() {
        assertFalse(LocationFixCachePolicy.isCacheable(fix(timestamp = 0L)))
    }

    @Test
    fun anUnknownAccuracy_isAcceptedRatherThanTreatedAsBad() {
        // The platform withholding a number is not evidence of a bad position.
        assertTrue(LocationFixCachePolicy.isAccurateEnoughForSearch(fix(accuracy = null)))
        assertFalse(
            LocationFixCachePolicy.isAccurateEnoughForSearch(
                fix(accuracy = LocationFixCachePolicy.SearchAccuracyCeilingMeters + 1f),
            ),
        )
    }

    @Test
    fun noCachedFix_producesNoSnapshotRatherThanADefault() {
        assertNull(LocationFixCachePolicy.historicalSnapshot(null, now))
    }

    // ------------------------------------------------------------------
    // Engine-level fallback ladder
    // ------------------------------------------------------------------

    @Test
    fun withNoLiveFix_theCachedPositionIsOfferedAsLabelledHistory() {
        val store = InMemoryLocationFixStore(fix(ageMillis = 60_000L))
        val engine = ProtectionEngine(PlatformDrivenGateway(), locationFixStore = store)

        val snapshot = engine.emergencyLocationSnapshot(now)

        assertTrue(snapshot.hasCoordinates)
        assertEquals(FixFreshness.STALE, snapshot.freshness)
        assertEquals(11.9332, snapshot.latitude ?: 0.0, 0.0000001)
    }

    @Test
    fun aLiveFixAlwaysWinsOverTheCache() {
        val store = InMemoryLocationFixStore(fix(ageMillis = 120_000L))
        val gateway = PlatformDrivenGateway()
        val engine = ProtectionEngine(gateway, locationFixStore = store)
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitLiveFix(11.0, 79.0)

        val snapshot = engine.emergencyLocationSnapshot(now)

        assertTrue(snapshot.isFresh)
        assertEquals(11.0, snapshot.latitude ?: 0.0, 0.0000001)
    }

    @Test
    fun withNoLiveFixAndNoCache_thereIsHonestlyNoPosition() {
        val engine = ProtectionEngine(PlatformDrivenGateway(), locationFixStore = InMemoryLocationFixStore())

        val snapshot = engine.emergencyLocationSnapshot(now)

        assertFalse(snapshot.hasCoordinates)
        assertEquals(FixFreshness.NO_FIX, snapshot.freshness)
    }

    @Test
    fun aCachedHistoricalPosition_isLabelledAsHistoricalNotCurrent() {
        val store = InMemoryLocationFixStore(fix(ageMillis = 60_000L))
        val engine = ProtectionEngine(PlatformDrivenGateway(), locationFixStore = store)

        val basis = locationBasisValue(engine.emergencyLocationSnapshot(now))

        assertTrue(
            "The UI must never present a cached fix as the current position: $basis",
            basis.contains("not the current", ignoreCase = true),
        )
    }

    // ------------------------------------------------------------------
    // The root cause: an emergency with nothing attached can never recover
    // ------------------------------------------------------------------

    /**
     * THE REPORTED BUG, AS IT ACTUALLY HAPPENED.
     *
     * Protection is NOT attached (paused, or the foreground service was killed by
     * the OEM). Location is off. The incident is detected. Every location entry
     * point is guarded by `sources.isEmpty()`, so with nothing listening the whole
     * recovery path was a no-op and no amount of refreshing could ever work.
     */
    @Test
    fun anEmergencyWithNoProtectionSource_attachesOneSoRecoveryCanWork() {
        val gateway = PlatformDrivenGateway()
        val engine = ProtectionEngine(gateway)
        val repository = ResilienceRecordingRepository()
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
            countdownTickMillis = 60_000L,
        )

        // No attachSource(SERVICE): protection is off, exactly as on the device.
        gateway.setPlatform(permission = true, enabled = false)
        enterEmergency(viewModel)

        // The emergency owns a listener now, so recovery is possible at all.
        assertTrue(engine.isMonitoring)

        // The user turns Location on and comes back.
        gateway.setPlatform(permission = true, enabled = true)
        gateway.pendingFix = liveFix()
        viewModel.onEmergencyScreenResumed()

        awaitCondition { repository.calls == 1 }
        assertTrue(
            "Recovery must re-request the location registration",
            gateway.forcedRefreshCount >= 1,
        )
    }

    @Test
    fun leavingTheEmergency_releasesTheSourceItHeld() {
        val gateway = PlatformDrivenGateway()
        val engine = ProtectionEngine(gateway)
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            countdownTickMillis = 60_000L,
        )
        gateway.setPlatform(permission = true, enabled = false)
        enterEmergency(viewModel)
        assertTrue(engine.isMonitoring)

        viewModel.standDown()

        assertFalse(
            "A finished incident must not keep the location listener registered",
            engine.isMonitoring,
        )
    }

    /**
     * The platform says Location is off, so there is nothing to wait for. The
     * attempt must report that immediately and must NOT consume its whole deadline
     * pretending it might still succeed.
     */
    @Test
    fun locationStillOff_reportsTheRealReasonWithoutWaitingForTheDeadline() {
        val gateway = PlatformDrivenGateway()
        val engine = ProtectionEngine(gateway)
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            countdownTickMillis = 60_000L,
            locationRecoveryTimeoutMillis = 60_000L,
        )
        gateway.setPlatform(permission = true, enabled = false)
        enterEmergency(viewModel)

        viewModel.recoverResourceLocation()

        assertEquals(
            LocationRecoveryState.Unavailable(LocationRecoveryReason.LOCATION_OFF),
            viewModel.state.locationRecovery,
        )
        assertFalse(viewModel.state.locationRecovery.isBusy)
    }

    /**
     * A real timeout, not an assumed one: the deadline is shrunk to a few
     * milliseconds so the path is actually exercised.
     */
    @Test
    fun aTimedOutAttempt_clearsItsSpinnerAndTheNextAttemptSucceeds() {
        val gateway = PlatformDrivenGateway()
        val engine = ProtectionEngine(gateway)
        val repository = ResilienceRecordingRepository()
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
            countdownTickMillis = 60_000L,
            locationRecoveryTimeoutMillis = 30L,
        )
        gateway.setPlatform(permission = true, enabled = true)
        gateway.pendingFix = null
        enterEmergency(viewModel)

        // First attempt: permitted and enabled, but the device produces nothing.
        viewModel.recoverResourceLocation()
        assertTrue(viewModel.state.locationRecovery.isBusy)

        awaitCondition { !viewModel.state.locationRecovery.isBusy }
        assertEquals(
            "A deadline that expires must leave a retryable reason, not a spinner",
            LocationRecoveryState.Unavailable(LocationRecoveryReason.TIMED_OUT),
            viewModel.state.locationRecovery,
        )
        assertEquals(0, repository.calls)

        // Second attempt: the device produces a fix this time.
        gateway.pendingFix = liveFix()
        viewModel.recoverResourceLocation()

        awaitCondition { repository.calls == 1 }
        assertEquals(
            "A timeout must never permanently suppress a retry",
            LocationRecoveryState.Idle,
            viewModel.state.locationRecovery,
        )
    }

    /** A fresh but multi-kilometre-vague fix must not be searched around. */
    @Test
    fun aFreshButVeryInaccurateFix_isNotSearchedAround() {
        val gateway = PlatformDrivenGateway()
        val engine = ProtectionEngine(gateway)
        val repository = ResilienceRecordingRepository()
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
            countdownTickMillis = 60_000L,
        )
        gateway.setPlatform(permission = true, enabled = true)
        gateway.pendingFix = liveFix(accuracy = 20_000f)
        enterEmergency(viewModel)
        gateway.emitPendingFix()

        awaitCondition {
            viewModel.state.resourceDiscovery is ResourceDiscoveryState.NoUsableLocation
        }
        assertEquals(0, repository.calls)
        assertTrue("No imprecise position may reach the backend", repository.seen.isEmpty())
    }

    /**
     * A coarse-only grant produces a fix that may be a kilometre out. It is still
     * a real position, so it is used - and it is labelled approximate.
     */
    @Test
    fun aCoarseOnlyFix_isStillUsableAndLabelledApproximate() {
        val gateway = PlatformDrivenGateway()
        val engine = ProtectionEngine(gateway)
        val repository = ResilienceRecordingRepository()
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
            countdownTickMillis = 60_000L,
        )
        gateway.setPlatform(permission = true, enabled = true)
        gateway.pendingFix = liveFix(accuracy = 1_200f, coarse = true)
        enterEmergency(viewModel)
        // The fix arrives after the emergency opened, which is the case that used
        // to leave discovery blocked for the rest of the incident.
        gateway.emitPendingFix()

        awaitCondition { repository.calls == 1 }
        assertTrue(viewModel.state.emergencyLocation?.isCoarse == true)
    }

    /**
     * With Location off and nothing cached, ResQ must say so and must stay fully
     * usable: the no-response workflow, the contacts and the emergency number do
     * not depend on a position.
     */
    @Test
    fun locationOffWithNoCache_leavesTheWholeEmergencyUsable() {
        val gateway = PlatformDrivenGateway()
        val engine = ProtectionEngine(
            gateway,
            locationFixStore = InMemoryLocationFixStore(),
        )
        val repository = ResilienceRecordingRepository()
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
            countdownTickMillis = 60_000L,
        )
        gateway.setPlatform(permission = true, enabled = false)
        enterEmergency(viewModel)

        assertEquals(AppScreen.EMERGENCY, viewModel.state.screen)
        assertEquals(0, repository.calls)
        assertFalse(viewModel.state.locationRecovery.isBusy)
        assertFalse(viewModel.state.emergencyLocation?.hasCoordinates == true)
        // The screen is showing an explanation, not an empty state.
        assertEquals(
            ResourceDiscoveryState.NoUsableLocation,
            viewModel.state.resourceDiscovery,
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun enterEmergency(viewModel: ResQViewModel) {
        viewModel.requestEmergencyHelp()
        viewModel.advanceEmergency()
        viewModel.advanceEmergency()
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000L
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for state")
            Thread.sleep(5)
        }
    }

    private fun liveFix(
        latitude: Double = 11.9332,
        longitude: Double = 79.5241,
        accuracy: Float = 6f,
        coarse: Boolean = false,
    ) = LocationState(
        availability = LocationAvailability.READY,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        provider = "gps",
        timestampMillis = System.currentTimeMillis(),
        isCoarse = coarse,
    )
}

/**
 * A gateway whose platform answers are driven by the test.
 *
 * [pendingFix] is the fix a FORCED re-registration will hand back, modelling the
 * fact that turning Location on does not by itself produce a fix: ResQ has to
 * re-register first, and only then does the platform start delivering.
 */
private class PlatformDrivenGateway : SensorGateway {

    private var observer: SensorObserver? = null

    var permissionGranted = true
    var locationEnabled = true
    var pendingFix: LocationState? = null
    var forcedRefreshCount = 0

    override fun hasLocationPermission(): Boolean = permissionGranted

    override fun isLocationEnabled(): Boolean = locationEnabled

    override fun start(observer: SensorObserver) {
        this.observer = observer
    }

    override fun refresh() = Unit

    override fun refreshForcingRegistration() {
        forcedRefreshCount++
        when {
            !permissionGranted -> emitLocation(
                LocationState(availability = LocationAvailability.PERMISSION_DENIED),
            )

            !locationEnabled -> emitLocation(
                LocationState(availability = LocationAvailability.PROVIDERS_DISABLED),
            )

            else -> pendingFix?.let { emitLocation(it) }
        }
    }

    override fun stop() {
        observer = null
    }

    fun setPlatform(permission: Boolean, enabled: Boolean) {
        permissionGranted = permission
        locationEnabled = enabled
    }

    /** Hands over the queued fix immediately, as a device already tracking does. */
    fun emitPendingFix() {
        pendingFix?.let { emitLocation(it) }
    }

    fun emitLiveFix(latitude: Double, longitude: Double) {
        emitLocation(
            LocationState(
                availability = LocationAvailability.READY,
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = 5f,
                provider = "gps",
                timestampMillis = System.currentTimeMillis(),
            ),
        )
    }

    fun emitLocation(state: LocationState) {
        observer?.onLocation(state)
    }
}

/** A repository that records every request. */
private class ResilienceRecordingRepository(
    private val result: ResourceSearchResult = ResourceSearchResult.NoResults,
) : EmergencyResourceRepository {

    @Volatile var calls = 0
    val seen = mutableListOf<Pair<Double, Double>>()

    override suspend fun searchNearbyResources(
        latitude: Double,
        longitude: Double,
        incidentType: String,
    ): ResourceSearchResult {
        calls++
        synchronized(seen) { seen += latitude to longitude }
        return result
    }
}