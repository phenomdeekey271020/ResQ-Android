package com.resq.app

import com.resq.app.data.remote.EmergencyResourceRepository
import com.resq.app.data.remote.IncidentTypePossibleAccident
import com.resq.app.data.remote.ResourceSearchResult
import com.resq.app.data.sensors.SensorGateway
import com.resq.app.data.sensors.SensorObserver
import com.resq.app.model.AppScreen
import com.resq.app.model.EmergencyProgress
import com.resq.app.model.EmergencyResource
import com.resq.app.model.EmergencyState
import com.resq.app.model.LocationAvailability
import com.resq.app.model.LocationState
import com.resq.app.model.ResourceDiscoveryState
import com.resq.app.model.ResourceSearchFailure
import com.resq.app.model.ResourceType
import com.resq.app.model.SafetyState
import com.resq.app.protection.ProtectionEngine
import com.resq.app.protection.ProtectionSource
import com.resq.app.viewmodel.ResQViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A gateway whose platform answers can be changed mid-test, so the exact
 * reported scenario (Location off -> on in system settings -> Refresh) can be
 * reproduced without a device.
 */
private class DiscoverySensorGateway : SensorGateway {

    private var observer: SensorObserver? = null
    var refreshCount = 0
    var forcedRefreshCount = 0

    /** Mirrors what the platform would report; driven per test. */
    var permissionGranted: Boolean = true
    var locationEnabled: Boolean = true

    override fun hasLocationPermission(): Boolean = permissionGranted

    override fun isLocationEnabled(): Boolean = locationEnabled

    override fun start(observer: SensorObserver) {
        this.observer = observer
    }

    override fun refresh() {
        refreshCount++
    }

    override fun refreshForcingRegistration() {
        forcedRefreshCount++
    }

    override fun stop() {
        observer = null
    }

    fun emitLocation(location: LocationState) {
        observer?.onLocation(location)
    }
}

private class ControllableResourceRepository(
    @Volatile var result: ResourceSearchResult,
    gateOpen: Boolean = false,
) : EmergencyResourceRepository {

    @Volatile var calls = 0
    @Volatile var lastLatitude: Double? = null
    @Volatile var lastLongitude: Double? = null
    @Volatile var lastIncidentType: String? = null
    private val entered = CountDownLatch(1)
    private val completed = CountDownLatch(1)
    private val gate = if (gateOpen) CountDownLatch(0) else CountDownLatch(1)

    override suspend fun searchNearbyResources(
        latitude: Double,
        longitude: Double,
        incidentType: String,
    ): ResourceSearchResult {
        calls++
        lastLatitude = latitude
        lastLongitude = longitude
        lastIncidentType = incidentType
        entered.countDown()
        gate.await(5, TimeUnit.SECONDS)
        try {
            return result
        } finally {
            completed.countDown()
        }
    }

    fun awaitEntered() {
        assertTrue("resource search was not started", entered.await(2, TimeUnit.SECONDS))
    }

    fun awaitCompleted() {
        assertTrue("resource search did not finish", completed.await(2, TimeUnit.SECONDS))
    }

    fun release() {
        gate.countDown()
    }
}

/**
 * Resource discovery is owned by the emergency flow, not by the UI layer.
 * Searches may start when the flow enters resource discovery or when the user
 * explicitly retries/refreshes; repeated snapshots and stage traffic must not
 * start additional requests.
 */
class ResourceDiscoveryViewModelTest {

    private lateinit var gateway: DiscoverySensorGateway
    private lateinit var engine: ProtectionEngine

    @Before
    fun setUp() {
        gateway = DiscoverySensorGateway()
        engine = ProtectionEngine(gateway)
    }

    @Test
    fun successfulSearch_usesFreshLocationOnceAndShowsNearestFirst() {
        val repository = ControllableResourceRepository(
            ResourceSearchResult.Success(
                resources = listOf(
                    EmergencyResource(
                        type = ResourceType.HOSPITAL,
                        name = "Near Clinic",
                        distanceKm = 0.4,
                        detail = "Emergency care",
                        availability = SafetyState.CAUTION,
                        availabilityLabel = "Not verified",
                        phone = "+91-413-555-0100",
                        address = "12 River Street",
                        isMock = false,
                    ),
                ),
                searchedAt = "2026-10-09T00:00:00",
            ),
        )
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
        )

        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitLocation(freshLocation())
        driveToSearching(viewModel)

        assertEquals(ResourceDiscoveryState.Loading, viewModel.state.resourceDiscovery)
        repository.awaitEntered()

        // While the first request is in flight, none of these may start another.
        gateway.emitLocation(freshLocation(latitude = 11.9340, longitude = 79.5250))
        viewModel.retryResourceDiscovery()
        viewModel.recoverResourceLocation()
        viewModel.advanceEmergency()
        gateway.emitLocation(freshLocation())
        assertEquals(1, repository.calls)

        repository.release()
        val loaded = awaitLoaded(viewModel)
        repository.awaitCompleted()

        assertEquals(11.9332, repository.lastLatitude ?: 0.0, 0.0)
        assertEquals(79.5241, repository.lastLongitude ?: 0.0, 0.0)
        assertEquals(IncidentTypePossibleAccident, repository.lastIncidentType)
        assertEquals(1, loaded.resources.size)
        assertEquals("Near Clinic", loaded.resources.single().name)
        assertEquals("12 River Street", loaded.resources.single().address)
        assertEquals("+91-413-555-0100", loaded.resources.single().phone)

        val ready = viewModel.state.emergency as EmergencyState.Ready
        assertEquals(loaded.resources, ready.resources)

        // After completion, ordinary snapshot traffic still does not poll.
        gateway.emitLocation(freshLocation())
        viewModel.advanceEmergency()
        assertEquals(1, repository.calls)
    }

    @Test
    fun failedSearch_supportsOneExplicitRetry() {
        val repository = ControllableResourceRepository(
            result = ResourceSearchResult.Failure(ResourceSearchFailure.SERVER),
            gateOpen = true,
        )
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
        )

        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitLocation(freshLocation())
        driveToSearching(viewModel)
        awaitFailed(viewModel, ResourceSearchFailure.SERVER)
        assertEquals(1, repository.calls)

        repository.result = ResourceSearchResult.Success(
            resources = listOf(
                EmergencyResource(
                    type = ResourceType.BLOOD_BANK,
                    name = "Mid Blood Bank",
                    distanceKm = 2.4,
                    detail = "Blood bank",
                    availability = SafetyState.CAUTION,
                    availabilityLabel = "Not verified",
                    phone = null,
                    address = null,
                    isMock = false,
                ),
            ),
            searchedAt = null,
        )
        viewModel.retryResourceDiscovery()

        val loaded = awaitLoaded(viewModel)
        assertEquals(2, repository.calls)
        assertEquals("Mid Blood Bank", loaded.resources.single().name)
    }

    @Test
    fun emptySearch_isLoadedWithoutResourcesRatherThanAnError() {
        val repository = ControllableResourceRepository(
            result = ResourceSearchResult.NoResults,
            gateOpen = true,
        )
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
        )

        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitLocation(freshLocation())
        driveToSearching(viewModel)

        val loaded = awaitLoaded(viewModel)
        assertTrue(loaded.resources.isEmpty())
        assertEquals(1, repository.calls)

        viewModel.advanceEmergency()
        val ready = viewModel.state.emergency as EmergencyState.Ready
        assertTrue(ready.resources.isEmpty())
        assertEquals(1, repository.calls)
    }

    @Test
    fun staleLocation_blocksSearchUntilAnExplicitRefreshSeesAFreshFix() {
        val repository = ControllableResourceRepository(
            result = ResourceSearchResult.NoResults,
            gateOpen = true,
        )
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
        )

        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitLocation(staleLocation())
        driveToSearching(viewModel)

        assertEquals(ResourceDiscoveryState.NoUsableLocation, viewModel.state.resourceDiscovery)
        assertEquals(0, repository.calls)

        gateway.emitLocation(freshLocation())
        viewModel.recoverResourceLocation()

        val loaded = awaitLoaded(viewModel)
        assertTrue(loaded.resources.isEmpty())
        assertEquals(1, repository.calls)
        // An explicit recovery re-checks the platform. It now takes the FORCED
        // path, because a merely idempotent refresh cannot recover a provider
        // registration the system already discarded - see the location recovery
        // regression tests, which assert that distinction directly.
        assertTrue(gateway.forcedRefreshCount + gateway.refreshCount >= 1)
    }

    @Test
    fun locationDisabled_blocksSearchUntilAFreshFixExistsAndKeepsTheEmergencyUsable() {
        val repository = ControllableResourceRepository(
            result = ResourceSearchResult.NoResults,
            gateOpen = true,
        )
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
        )

        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitLocation(LocationState(availability = LocationAvailability.PROVIDERS_DISABLED))
        driveToSearching(viewModel)

        // Location is off: no request may go out around a position ResQ does
        // not have, and the emergency itself stays fully available.
        assertEquals(ResourceDiscoveryState.NoUsableLocation, viewModel.state.resourceDiscovery)
        assertEquals(0, repository.calls)
        assertEquals(AppScreen.EMERGENCY, viewModel.state.screen)

        // An explicit recovery attempt while Location is STILL off re-checks
        // the platform and still makes no request - never around coordinates
        // ResQ cannot vouch for.
        viewModel.recoverResourceLocation()
        assertEquals(0, repository.calls)
        assertEquals(ResourceDiscoveryState.NoUsableLocation, viewModel.state.resourceDiscovery)
        assertEquals(AppScreen.EMERGENCY, viewModel.state.screen)

        // Once a genuinely fresh fix exists, the explicit retry searches.
        gateway.emitLocation(freshLocation())
        viewModel.recoverResourceLocation()

        awaitLoaded(viewModel)
        assertEquals(1, repository.calls)
        // See the note in staleLocation_blocksSearchUntilAnExplicitRefreshSeesAFreshFix.
        assertTrue(gateway.forcedRefreshCount + gateway.refreshCount >= 1)
    }

    @Test
    fun missingLocation_makesNoRequest() {
        val repository = ControllableResourceRepository(
            result = ResourceSearchResult.NoResults,
            gateOpen = true,
        )
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
        )

        driveToSearching(viewModel)

        assertEquals(ResourceDiscoveryState.NoUsableLocation, viewModel.state.resourceDiscovery)
        assertEquals(0, repository.calls)
        assertEquals(
            EmergencyState.Preparing(EmergencyProgress.SEARCHING_RESOURCES),
            viewModel.state.emergency,
        )
        assertEquals(AppScreen.EMERGENCY, viewModel.state.screen)
    }

    @Test
    fun noRepositoryWired_reportsRetryableFailureInsteadOfMockData() {
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = null,
        )

        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitLocation(freshLocation())
        driveToSearching(viewModel)

        assertEquals(
            EmergencyState.Preparing(EmergencyProgress.SEARCHING_RESOURCES),
            viewModel.state.emergency,
        )
        assertEquals(
            ResourceDiscoveryState.Failed(ResourceSearchFailure.NETWORK),
            viewModel.state.resourceDiscovery,
        )

        // Never silently filled with the local mock fixtures.
        viewModel.advanceEmergency()
        val ready = viewModel.state.emergency as EmergencyState.Ready
        assertTrue(ready.resources.isEmpty())
    }

    @Test
    fun grantingLocationPermissionLater_startsExactlyOneSearch() {
        val repository = ControllableResourceRepository(
            result = ResourceSearchResult.NoResults,
            gateOpen = true,
        )
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
        )

        // Runtime permission granted after the emergency opened: the first search
        // was blocked, and the explicit permission result starts exactly one.
        engine.attachSource(ProtectionSource.SERVICE)
        driveToSearching(viewModel)
        assertEquals(ResourceDiscoveryState.NoUsableLocation, viewModel.state.resourceDiscovery)
        assertEquals(0, repository.calls)

        gateway.emitLocation(freshLocation())
        viewModel.onLocationPermissionResult(granted = true)

        val loaded = awaitLoaded(viewModel)
        assertTrue(loaded.resources.isEmpty())
        assertEquals(1, repository.calls)

        // Repeating the permission result must not start another request.
        viewModel.onLocationPermissionResult(granted = true)
        assertEquals(1, repository.calls)
    }

    @Test
    fun repeatedSnapshotsAfterReady_neverReturnToIdleOrLoading() {
        val repository = ControllableResourceRepository(
            result = ResourceSearchResult.Success(
                resources = listOf(
                    EmergencyResource(
                        type = ResourceType.HOSPITAL,
                        name = "Near Clinic",
                        distanceKm = 0.4,
                        detail = "Hospital",
                        availability = SafetyState.CAUTION,
                        availabilityLabel = "Not verified",
                        phone = null,
                        address = null,
                        latitude = 11.9340,
                        longitude = 79.5250,
                        isMock = false,
                    ),
                ),
                searchedAt = null,
            ),
            gateOpen = true,
        )
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
        )

        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitLocation(freshLocation())
        driveToSearching(viewModel)
        val loaded = awaitLoaded(viewModel)
        assertEquals(1, repository.calls)

        viewModel.advanceEmergency()
        assertEquals(EmergencyState.Ready(loaded.resources), viewModel.state.emergency)

        // Rotation / recomposition / sensor traffic all re-publish snapshots. None
        // of that may reset the search or fire another request.
        repeat(5) {
            gateway.emitLocation(freshLocation())
            viewModel.advanceEmergency()
        }

        assertEquals(ResourceDiscoveryState.Loaded(loaded.resources), viewModel.state.resourceDiscovery)
        assertTrue(viewModel.state.resourceDiscovery !is ResourceDiscoveryState.Loading)
        assertEquals(1, repository.calls)
    }

    @Test
    fun standingDownAfterReady_returnsToIdleAndIgnoresFurtherSnapshots() {
        val repository = ControllableResourceRepository(
            ResourceSearchResult.NoResults,
        )
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
        )

        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitLocation(freshLocation())
        driveToSearching(viewModel)
        repository.awaitEntered()
        viewModel.advanceEmergency()
        repository.release()
        repository.awaitCompleted()

        viewModel.standDown()
        gateway.emitLocation(freshLocation())
        viewModel.advanceEmergency()

        assertEquals(AppScreen.HOME, viewModel.state.screen)
        assertEquals(EmergencyState.Inactive, viewModel.state.emergency)
        assertEquals(ResourceDiscoveryState.Idle, viewModel.state.resourceDiscovery)
        assertEquals(1, repository.calls)
    }

    @Test
    fun standingDownDuringSearch_dropsTheLateResult() {
        val repository = ControllableResourceRepository(
            ResourceSearchResult.NoResults,
        )
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            resourceRepository = repository,
        )

        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitLocation(freshLocation())
        driveToSearching(viewModel)
        repository.awaitEntered()

        viewModel.standDown()
        repository.release()
        repository.awaitCompleted()

        assertEquals(1, repository.calls)
        assertEquals(AppScreen.HOME, viewModel.state.screen)
        assertEquals(EmergencyState.Inactive, viewModel.state.emergency)
        assertEquals(ResourceDiscoveryState.Idle, viewModel.state.resourceDiscovery)
    }

    private fun freshLocation(
        latitude: Double = 11.9332,
        longitude: Double = 79.5241,
    ) = LocationState(
        availability = LocationAvailability.READY,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = 6f,
        provider = "gps",
        timestampMillis = System.currentTimeMillis(),
    )

    private fun staleLocation() = LocationState(
        availability = LocationAvailability.STALE,
        latitude = 11.9332,
        longitude = 79.5241,
        accuracyMeters = 25f,
        provider = "gps",
        timestampMillis = System.currentTimeMillis() - 5 * 60_000L,
    )

    private fun driveToSearching(viewModel: ResQViewModel) {
        viewModel.requestEmergencyHelp()
        viewModel.advanceEmergency()
        viewModel.advanceEmergency()
    }

    private fun awaitLoaded(viewModel: ResQViewModel): ResourceDiscoveryState.Loaded {
        awaitCondition { viewModel.state.resourceDiscovery is ResourceDiscoveryState.Loaded }
        return viewModel.state.resourceDiscovery as ResourceDiscoveryState.Loaded
    }

    private fun awaitFailed(
        viewModel: ResQViewModel,
        reason: ResourceSearchFailure,
    ) {
        awaitCondition { viewModel.state.resourceDiscovery == ResourceDiscoveryState.Failed(reason) }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000L
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for resource discovery state")
            }
            Thread.sleep(5)
        }
    }
}
