package com.resq.app

import com.resq.app.data.remote.EmergencyResourceRepository
import com.resq.app.data.remote.ResourceSearchResult
import com.resq.app.data.sensors.InMemoryLocationFixStore
import com.resq.app.data.sensors.LocationFixStore
import com.resq.app.data.sensors.SensorGateway
import com.resq.app.data.sensors.SensorObserver
import com.resq.app.model.FixFreshness
import com.resq.app.model.LastKnownLocationFix
import com.resq.app.model.LocationAvailability
import com.resq.app.model.LocationFixAvailability
import com.resq.app.model.LocationFixCachePolicy
import com.resq.app.model.LocationRecoveryReason
import com.resq.app.model.LocationRecoveryState
import com.resq.app.model.LocationState
import com.resq.app.model.ResourceDiscoveryState
import com.resq.app.model.toCacheableFix
import com.resq.app.protection.ProtectionEngineHolder
import com.resq.app.protection.ProtectionSource
import com.resq.app.ui.screens.locationBasisValue
import com.resq.app.viewmodel.ResQViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * THE REPORTED DEVICE FAILURE, AS A DETERMINISTIC TEST.
 *
 * On the Vivo V2561 the emergency screen said "Last known location: Unknown /
 * Position basis: No position" even though a fix had been acquired and
 * persisted before Location was switched off - and it kept saying that after a
 * force-stop, which is the signature of a read path that is not connected to
 * the store rather than of a fix that was never written.
 *
 * The production wiring now lives in ProtectionEngineHolder.build, which is the
 * function every test below uses, so a regression of that wiring fails here
 * instead of on a phone.
 *
 * No real GPS, no real file and no real network call: the gateway, the store
 * and the repository are all fakes, and the clock is the test's.
 */
class LocationCachePersistenceRegressionTest {

    private val latitude = 11.9332
    private val longitude = 79.5241

    // ------------------------------------------------------------------
    // The root cause: the production wiring must hand the store to the READ side
    // ------------------------------------------------------------------

    /**
     * The exact omission that produced the report: the gateway was given the
     * store (so writes happened) while [com.resq.app.protection.ProtectionEngine]
     * was not (so the only read path in the app saw null and never offered the
     * cache). Everything else in the pipeline was working.
     */
    @Test
    fun theProductionWiring_givesTheEngineTheStoreTheGatewayWritesTo() {
        val store = InMemoryLocationFixStore(cachedFix(ageMillis = 60_000L))
        val engine = ProtectionEngineHolder.build(
            sensorGateway = DeviceGateway(store, locationEnabled = false),
            locationFixStore = store,
        )

        val snapshot = engine.emergencyLocationSnapshot(now())

        assertTrue(
            "The emergency read path must see the store the write path filled",
            snapshot.hasCoordinates,
        )
        assertEquals(FixFreshness.STALE, snapshot.freshness)
    }

    // ------------------------------------------------------------------
    // Process recreation
    // ------------------------------------------------------------------

    /**
     * The whole reported sequence: a fix is acquired and persisted, Location is
     * switched off (which drops the live reading), the process is force-stopped
     * and reopened, and the emergency flow is entered again.
     *
     * The reopened process has no live state at all - a brand new gateway and a
     * brand new engine - so the ONLY thing that can put a position on the screen
     * is the persisted store. That is the assertion that failed on the device.
     */
    @Test
    fun aPersistedFix_survivesProcessRecreationAndReachesTheEmergencyScreen() {
        // ---- process 1: the fix is acquired while Location is on ----
        val store = InMemoryLocationFixStore()
        val runningGateway = DeviceGateway(store, locationEnabled = true)
        val runningEngine = ProtectionEngineHolder.build(runningGateway, store)
        runningEngine.attachSource(ProtectionSource.DIAGNOSTICS_UI)

        runningGateway.emitFix(ageMillis = 5_000L)

        assertEquals(
            "A usable fix must be written to the private persistent store",
            1,
            store.writeCount,
        )
        assertNotNull(store.read())

        // The user switches the device Location OFF. The live channel loses the
        // reading - this is what AndroidSensorGateway's PROVIDERS_CHANGED
        // receiver publishes - and the persisted entry must survive it.
        runningGateway.locationEnabled = false
        runningGateway.emitProvidersDisabled()

        assertEquals(
            "Switching providers off must not discard the persisted fix",
            1,
            store.writeCount,
        )
        assertNotNull(store.read())

        // ---- force-stop and reopen: nothing is carried over except the store ----
        val reopenedGateway = DeviceGateway(store, locationEnabled = false)
        val reopenedEngine = ProtectionEngineHolder.build(reopenedGateway, store)
        val repository = CacheRecordingRepository()
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = reopenedEngine,
            resourceRepository = repository,
            countdownTickMillis = 60_000L,
        )

        enterEmergency(viewModel)

        val location = viewModel.state.emergencyLocation
        assertNotNull(
            "A fix persisted before the process died must be offered to the emergency screen",
            location,
        )
        assertEquals(latitude, location?.latitude ?: 0.0, 0.0000001)
        assertEquals(longitude, location?.longitude ?: 0.0, 0.0000001)
        assertEquals(6f, location?.accuracyMeters ?: 0f, 0.0001f)
        assertTrue(
            "The age must describe when the fix was measured, not when it was read",
            (location?.ageMillis ?: 0L) >= 5_000L,
        )

        // HONEST LABELLING: historical, never current, and never searched around.
        assertEquals(FixFreshness.STALE, location?.freshness)
        assertFalse(location?.isFresh == true)
        assertEquals(
            "Last known position - not the current location",
            locationBasisValue(location),
        )
        assertEquals(ResourceDiscoveryState.NoUsableLocation, viewModel.state.resourceDiscovery)
        assertEquals("A stale position must never reach the search", 0, repository.calls)
    }

    // ------------------------------------------------------------------
    // Freshness: a cached fix is never a fresh fix
    // ------------------------------------------------------------------

    /**
     * THE INVARIANT THE WHOLE CACHE EXISTS TO PROTECT.
     *
     * The fix here is ONE SECOND old - far inside the 30 second live freshness
     * window - so any implementation that derived freshness from the clock alone
     * would report it as FRESH and search for hospitals around a position nobody
     * is standing at. It must still be STALE, because "cached" is what it is.
     */
    @Test
    fun aCachedFix_isNeverPresentedAsFreshAndIsNeverSearchedAround() {
        val store = InMemoryLocationFixStore(cachedFix(ageMillis = 1_000L))
        val engine = ProtectionEngineHolder.build(
            sensorGateway = DeviceGateway(store, locationEnabled = false),
            locationFixStore = store,
        )
        val repository = CacheRecordingRepository()
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
            countdownTickMillis = 60_000L,
        )

        enterEmergency(viewModel)

        val location = viewModel.state.emergencyLocation
        assertTrue(location?.hasCoordinates == true)
        assertEquals(FixFreshness.STALE, location?.freshness)
        assertFalse(location?.isFresh == true)
        assertTrue(
            "The cached timestamp must be carried through untouched",
            (location?.ageMillis ?: 0L) >= 1_000L,
        )
        assertEquals(
            "Last known position - not the current location",
            locationBasisValue(location),
        )
        assertEquals(ResourceDiscoveryState.NoUsableLocation, viewModel.state.resourceDiscovery)
        assertEquals(0, repository.calls)
        assertTrue(repository.seen.isEmpty())
    }

    /** The device says Location is off, so the reason is still reported as such. */
    @Test
    fun withLocationOff_theRecoveryAttemptStillReportsTheRealPlatformReason() {
        val store = InMemoryLocationFixStore(cachedFix(ageMillis = 60_000L))
        val engine = ProtectionEngineHolder.build(
            sensorGateway = DeviceGateway(store, locationEnabled = false),
            locationFixStore = store,
        )
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            countdownTickMillis = 60_000L,
            locationRecoveryTimeoutMillis = 60_000L,
        )

        enterEmergency(viewModel)
        viewModel.recoverResourceLocation()

        assertEquals(
            LocationRecoveryState.Unavailable(LocationRecoveryReason.LOCATION_OFF),
            viewModel.state.locationRecovery,
        )
        // Showing a cached position must not turn a settings problem into a
        // "waiting for a fix" spinner.
        assertFalse(viewModel.state.locationRecovery.isBusy)
    }

    // ------------------------------------------------------------------
    // Rejection: an expired, too-vague or malformed entry is not a position
    // ------------------------------------------------------------------

    @Test
    fun anExpiredCache_isRejectedRatherThanDisplayed() {
        val store = InMemoryLocationFixStore(
            cachedFix(ageMillis = LocationFixCachePolicy.RetentionMillis + 1_000L),
        )
        val engine = ProtectionEngineHolder.build(
            sensorGateway = DeviceGateway(store, locationEnabled = false),
            locationFixStore = store,
        )

        val snapshot = engine.emergencyLocationSnapshot(now())

        assertFalse(snapshot.hasCoordinates)
        assertNull(snapshot.latitude)
        assertNull(snapshot.longitude)
        assertEquals("No position", locationBasisValue(snapshot))
        assertTrue(
            "An entry past the retention window must be reported as unusable",
            LocationFixCachePolicy.availability(store.read(), now())
                is LocationFixAvailability.Unusable,
        )
    }

    @Test
    fun aCacheBeyondTheDisplayAccuracyThreshold_isRejectedRatherThanDisplayed() {
        val store = InMemoryLocationFixStore(
            cachedFix(
                ageMillis = 60_000L,
                accuracy = LocationFixCachePolicy.UsableAccuracyMeters + 1f,
            ),
        )
        val engine = ProtectionEngineHolder.build(
            sensorGateway = DeviceGateway(store, locationEnabled = false),
            locationFixStore = store,
        )

        val snapshot = engine.emergencyLocationSnapshot(now())

        assertFalse(
            "A fix too vague to show must not be shown",
            snapshot.hasCoordinates,
        )
        assertEquals("No position", locationBasisValue(snapshot))
    }

    @Test
    fun aPlaceholderCacheEntry_isRejectedRatherThanDisplayed() {
        val store = InMemoryLocationFixStore(cachedFix(latitude = 0.0, longitude = 0.0))
        val engine = ProtectionEngineHolder.build(
            sensorGateway = DeviceGateway(store, locationEnabled = false),
            locationFixStore = store,
        )

        val snapshot = engine.emergencyLocationSnapshot(now())

        assertFalse(snapshot.hasCoordinates)
        assertEquals("No position", locationBasisValue(snapshot))
    }

    // ------------------------------------------------------------------
    // Policy boundaries: 30 minutes, 500 m, 5 km
    // ------------------------------------------------------------------

    /**
     * The three published thresholds are asserted AT the boundary, because that
     * is where a ">=" vs ">" slip would silently change what a user sees. These
     * values are the contract: retention 30 minutes, display accuracy 500 m,
     * search accuracy ceiling 5 km.
     */
    @Test
    fun theRetentionAndAccuracyThresholds_keepTheirDocumentedSemantics() {
        val now = now()

        // Retention: 30 minutes exactly is still shown; one millisecond past is not.
        // Timestamps are pinned to `now` so the boundary cannot drift with the clock
        // while the test runs.
        assertEquals(30L * 60_000L, LocationFixCachePolicy.RetentionMillis)
        assertTrue(
            LocationFixCachePolicy.availability(
                cachedFix(timestampMillis = now - 30L * 60_000L),
                now,
            ) is LocationFixAvailability.Usable,
        )
        assertTrue(
            LocationFixCachePolicy.availability(
                cachedFix(timestampMillis = now - (30L * 60_000L + 1L)),
                now,
            ) is LocationFixAvailability.Unusable,
        )

        // Display accuracy: 500 m exactly is still shown; 500.1 m is not.
        assertEquals(500f, LocationFixCachePolicy.UsableAccuracyMeters, 0f)
        assertTrue(
            LocationFixCachePolicy.availability(cachedFix(accuracy = 500f), now)
                is LocationFixAvailability.Usable,
        )
        assertTrue(
            LocationFixCachePolicy.availability(cachedFix(accuracy = 500.1f), now)
                is LocationFixAvailability.Unusable,
        )

        // Search ceiling: 5 km exactly may still influence a search; more may not.
        assertEquals(5_000f, LocationFixCachePolicy.SearchAccuracyCeilingMeters, 0f)
        assertTrue(LocationFixCachePolicy.isAccurateEnoughForSearch(cachedFix(accuracy = 5_000f)))
        assertFalse(
            LocationFixCachePolicy.isAccurateEnoughForSearch(cachedFix(accuracy = 5_000.1f)),
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun now(): Long = System.currentTimeMillis()

    private fun cachedFix(
        ageMillis: Long = 60_000L,
        accuracy: Float? = 6f,
        latitude: Double = this.latitude,
        longitude: Double = this.longitude,
        timestampMillis: Long = now() - ageMillis,
    ) = LastKnownLocationFix(
        latitude = latitude,
        longitude = longitude,
        timestampMillis = timestampMillis,
        accuracyMeters = accuracy,
        isCoarse = false,
        provider = "gps",
    )

    private fun enterEmergency(viewModel: ResQViewModel) {
        viewModel.requestEmergencyHelp()
        viewModel.advanceEmergency()
        viewModel.advanceEmergency()
    }
}

/**
 * A gateway that behaves like [com.resq.app.data.sensors.AndroidSensorGateway]
 * for the two things this file cares about:
 *
 *  - the WRITE side: every usable fix goes through
 *    [LocationState.toCacheableFix] into the store, exactly as the real gateway
 *    does, so "was it persisted?" is a real question here;
 *  - the platform side: with Location off, starting the gateway publishes
 *    PROVIDERS_DISABLED and never a position, and a fix arrives only while the
 *    device actually has Location on.
 *
 * The platform answers are driven by the test, so no test needs a device, a
 * permission or a satellite.
 */
private class DeviceGateway(
    private val store: LocationFixStore,
    var locationEnabled: Boolean = true,
) : SensorGateway {

    private val latitude = 11.9332
    private val longitude = 79.5241

    private var observer: SensorObserver? = null

    override fun hasLocationPermission(): Boolean = true

    override fun isLocationEnabled(): Boolean = locationEnabled

    override fun start(observer: SensorObserver) {
        this.observer = observer
        if (!locationEnabled) emitProvidersDisabled()
    }

    override fun refresh() = Unit

    override fun refreshForcingRegistration() {
        if (!locationEnabled) emitProvidersDisabled()
    }

    override fun stop() {
        observer = null
    }

    /** The WRITE side of the real gateway: a usable fix is persisted first. */
    fun emitFix(ageMillis: Long = 0L, accuracy: Float? = 6f) {
        val fix = LocationState(
            availability = LocationAvailability.READY,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracy,
            provider = "gps",
            timestampMillis = System.currentTimeMillis() - ageMillis,
        )
        fix.toCacheableFix()?.let { store.write(it) }
        observer?.onLocation(fix)
    }

    /** What the real gateway publishes the moment the providers are switched off. */
    fun emitProvidersDisabled() {
        observer?.onLocation(LocationState(availability = LocationAvailability.PROVIDERS_DISABLED))
    }
}

/** A repository that records every request it was given. */
private class CacheRecordingRepository : EmergencyResourceRepository {

    @Volatile var calls = 0
    val seen = mutableListOf<Pair<Double, Double>>()

    override suspend fun searchNearbyResources(
        latitude: Double,
        longitude: Double,
        incidentType: String,
    ): ResourceSearchResult {
        calls++
        synchronized(seen) { seen += latitude to longitude }
        return ResourceSearchResult.NoResults
    }
}
