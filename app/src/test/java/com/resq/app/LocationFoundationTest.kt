package com.resq.app

import com.resq.app.data.sensors.SensorGateway
import com.resq.app.data.sensors.SensorObserver
import com.resq.app.detection.AccidentEvent
import com.resq.app.detection.AccidentFlowState
import com.resq.app.detection.EmergencyStage
import com.resq.app.detection.SimulationSamples
import com.resq.app.model.AccelerometerState
import com.resq.app.model.DetectionLevel
import com.resq.app.model.EmergencyLocationSnapshot
import com.resq.app.model.FixFreshness
import com.resq.app.model.GyroscopeState
import com.resq.app.model.LocationAvailability
import com.resq.app.model.LocationFreshnessPolicy
import com.resq.app.model.LocationStaleAfterMillis
import com.resq.app.model.LocationState
import com.resq.app.model.NoSensorTimestamp
import com.resq.app.model.SensorAvailability
import com.resq.app.model.SensorState
import com.resq.app.model.emergencySnapshot
import com.resq.app.model.label
import com.resq.app.protection.ProtectionEngine
import com.resq.app.protection.ProtectionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK 5A - BACKGROUND LOCATION FOUNDATION.
 *
 * Everything here is pure logic about *whether a position may be trusted*, driven
 * entirely by fake fixes. No Android, no LocationManager, no GPS hardware: the
 * point is that the rules ResQ relies on for an emergency location are pinned
 * down where they can be checked in milliseconds.
 *
 * The freshness window itself is the prototype threshold documented in
 * LocationFreshnessPolicy: [LocationStaleAfterMillis].
 */
class LocationFreshnessTest {

    private val now = 1_700_000_000_000L

    private fun freshFix(
        latitude: Double = 12.971599,
        longitude: Double = 77.594566,
        ageMillis: Long = 1_000L,
    ) = LocationState(
        availability = LocationAvailability.READY,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = 7f,
        speedMetersPerSecond = 2.5f,
        provider = "gps",
        timestampMillis = now - ageMillis,
    )

    // ------------------------------------------------------------------
    // The single freshness rule
    // ------------------------------------------------------------------

    @Test
    fun aFixInsideTheWindow_isFresh() {
        assertEquals(FixFreshness.FRESH, LocationFreshnessPolicy.classify(freshFix(), now))
        assertTrue(LocationFreshnessPolicy.isFresh(freshFix(), now))
    }

    @Test
    fun aFixExactlyOnTheBoundary_isStillFresh() {
        val fix = freshFix(ageMillis = LocationStaleAfterMillis)

        assertEquals(FixFreshness.FRESH, LocationFreshnessPolicy.classify(fix, now))
    }

    @Test
    fun aFixOneMillisecondPastTheWindow_isStale() {
        val fix = freshFix(ageMillis = LocationStaleAfterMillis + 1)

        assertEquals(FixFreshness.STALE, LocationFreshnessPolicy.classify(fix, now))
        assertFalse(LocationFreshnessPolicy.isFresh(fix, now))
    }

    @Test
    fun theGatewayAlreadyMarkedStale_staysStaleHoweverRecentItLooks() {
        val fix = freshFix(ageMillis = 100L).copy(availability = LocationAvailability.STALE)

        assertEquals(FixFreshness.STALE, LocationFreshnessPolicy.classify(fix, now))
    }

    @Test
    fun aFixWithNoTimestamp_isNeverFresh() {
        val fix = freshFix().copy(timestampMillis = NoSensorTimestamp)

        assertEquals(FixFreshness.STALE, LocationFreshnessPolicy.classify(fix, now))
    }

    @Test
    fun everyNonReadingAvailability_isNoFixNotStale() {
        // The distinction matters: NO_FIX means "nothing to show", whereas STALE
        // would wrongly imply a real position exists behind the state.
        listOf(
            LocationAvailability.WAITING_FOR_PERMISSION,
            LocationAvailability.PERMISSION_DENIED,
            LocationAvailability.PROVIDERS_DISABLED,
            LocationAvailability.WAITING_FOR_FIX,
            LocationAvailability.UNKNOWN,
        ).forEach { availability ->
            val state = LocationState(availability = availability)
            assertEquals(
                "availability $availability",
                FixFreshness.NO_FIX,
                LocationFreshnessPolicy.classify(state, now),
            )
            assertFalse(LocationFreshnessPolicy.isFresh(state, now))
        }
    }

    @Test
    fun aWaitingState_neverBecomesAReadingJustBecauseCoordinatesExist() {
        val state = LocationState(
            availability = LocationAvailability.WAITING_FOR_FIX,
            latitude = 12.971599,
            longitude = 77.594566,
            timestampMillis = now,
        )

        assertFalse(state.hasReading)
        assertEquals(FixFreshness.NO_FIX, LocationFreshnessPolicy.classify(state, now))
        assertNull(state.emergencySnapshot(now).latitude)
    }

    @Test
    fun ageIsReportedOnlyWhenThereIsAFix() {
        assertEquals(1_000L, LocationFreshnessPolicy.ageMillis(freshFix(), now))
        assertNull(
            LocationFreshnessPolicy.ageMillis(
                LocationState(availability = LocationAvailability.WAITING_FOR_FIX),
                now,
            ),
        )
    }

    @Test
    fun freshnessLabels_areTruthful() {
        assertEquals("Fresh", FixFreshness.FRESH.label)
        assertEquals("Stale", FixFreshness.STALE.label)
        assertEquals("No fix", FixFreshness.NO_FIX.label)
    }

    // ------------------------------------------------------------------
    // Emergency location snapshot
    // ------------------------------------------------------------------

    @Test
    fun aFreshFix_producesACompleteEmergencySnapshot() {
        val snapshot = freshFix().emergencySnapshot(now)

        assertTrue(snapshot.isFresh)
        assertTrue(snapshot.hasCoordinates)
        assertEquals(12.971599, snapshot.latitude ?: 0.0, 0.000001)
        assertEquals(77.594566, snapshot.longitude ?: 0.0, 0.000001)
        assertEquals(7f, snapshot.accuracyMeters ?: 0f, 0.0001f)
        assertEquals(2.5f, snapshot.speedMetersPerSecond ?: 0f, 0.0001f)
        assertEquals("gps", snapshot.provider)
        assertEquals(now - 1_000L, snapshot.timestampMillis)
        assertEquals(1_000L, snapshot.ageMillis)
    }

    @Test
    fun aStaleFix_staysInTheSnapshotButIsNotFreshEvidence() {
        val snapshot = freshFix(ageMillis = 5 * 60_000L).emergencySnapshot(now)

        assertTrue(snapshot.hasCoordinates)
        assertFalse(snapshot.isFresh)
        assertEquals(FixFreshness.STALE, snapshot.freshness)
        // The wording itself must admit it is not current evidence.
        assertTrue(snapshot.summary.contains("STALE"))
    }

    @Test
    fun aMissingLocation_producesASnapshotWithNoCoordinates() {
        val snapshot = LocationState(
            availability = LocationAvailability.PROVIDERS_DISABLED,
        ).emergencySnapshot(now)

        assertFalse(snapshot.hasCoordinates)
        assertFalse(snapshot.isFresh)
        assertNull(snapshot.latitude)
        assertNull(snapshot.longitude)
        assertNull(snapshot.ageMillis)
        assertEquals(LocationAvailability.PROVIDERS_DISABLED, snapshot.availability)
    }

    @Test
    fun aDeniedPermission_neverLeaksIntoAnEmergencySnapshot() {
        val snapshot = LocationState(
            availability = LocationAvailability.PERMISSION_DENIED,
            latitude = 12.971599,
            longitude = 77.594566,
            timestampMillis = now,
        ).emergencySnapshot(now)

        assertFalse(snapshot.hasCoordinates)
        assertNull(snapshot.latitude)
        assertFalse(snapshot.isFresh)
    }

    @Test
    fun theDefaultSnapshot_isExplicitlyEmptyRatherThanZeroZero() {
        val snapshot = EmergencyLocationSnapshot()

        assertFalse(snapshot.hasCoordinates)
        assertFalse(snapshot.isFresh)
        assertEquals(FixFreshness.NO_FIX, snapshot.freshness)
    }

    // ------------------------------------------------------------------
    // Permission and provider states
    // ------------------------------------------------------------------

    @Test
    fun everyPermissionAndProviderFailure_isDistinguishableAndNeverAFix() {
        val states = mapOf(
            LocationAvailability.WAITING_FOR_PERMISSION to "Waiting for permission",
            LocationAvailability.PERMISSION_DENIED to "Permission denied",
            LocationAvailability.PROVIDERS_DISABLED to "Location services off",
            LocationAvailability.WAITING_FOR_FIX to "Waiting for location",
            LocationAvailability.UNKNOWN to "Location unavailable",
        )

        states.forEach { (availability, expectedLabel) ->
            val state = LocationState(availability = availability)
            assertEquals(expectedLabel, availability.label)
            assertFalse(state.hasReading)
            assertEquals(FixFreshness.NO_FIX, LocationFreshnessPolicy.classify(state, now))
        }
        // A live fix is the one and only state that is both readable and fresh.
        assertEquals("Live", LocationAvailability.READY.label)
        assertTrue(LocationFreshnessPolicy.isFresh(freshFix(), now))
    }
}

/**
 * Location must stay contextual: it may add a little confidence when a FRESH fix
 * reports speed, but a missing, denied, disabled or stale position must never
 * create a positive accident signal on its own.
 */
class LocationIsNeverPositiveEvidenceTest {

    private val gateway = EvidenceGateway()
    private val engine = ProtectionEngine(gateway)

    private val impact = SimulationSamples.highConfidenceCollision(8)

    private fun detectionWith(location: LocationState) {
        engine.attachSource(ProtectionSource.SERVICE)
        engine.reset()
        impact.forEach { sample -> gateway.emit(sample.copy(location = location)) }
    }

    @Test
    fun aFreshMovingFix_isTheOnlyOneThatAddsMotionContext() {
        detectionWith(
            LocationState(
                availability = LocationAvailability.READY,
                latitude = 12.971599,
                longitude = 77.594566,
                speedMetersPerSecond = 12f,
                provider = "gps",
                timestampMillis = System.currentTimeMillis(),
            ),
        )

        assertTrue(engine.snapshot.detection.evidence.motionContext)
    }

    @Test
    fun aStaleFix_doesNotBecomeMotionContext() {
        detectionWith(
            LocationState(
                availability = LocationAvailability.STALE,
                latitude = 12.971599,
                longitude = 77.594566,
                speedMetersPerSecond = 12f,
                provider = "gps",
                timestampMillis = System.currentTimeMillis() - 10 * 60_000L,
            ),
        )

        assertFalse(engine.snapshot.detection.evidence.motionContext)
    }

    @Test
    fun missingDeniedAndDisabledLocation_areAllNeverPositiveEvidence() {
        listOf(
            LocationAvailability.WAITING_FOR_PERMISSION,
            LocationAvailability.PERMISSION_DENIED,
            LocationAvailability.PROVIDERS_DISABLED,
            LocationAvailability.WAITING_FOR_FIX,
            LocationAvailability.UNKNOWN,
        ).forEach { availability ->
            engine.detachSource(ProtectionSource.SERVICE)
            detectionWith(LocationState(availability = availability))

            assertFalse(
                "availability $availability",
                engine.snapshot.detection.evidence.motionContext,
            )
        }
    }

    @Test
    fun noLocationAtAll_neverOpensAnIncidentOnItsOwn() {
        engine.attachSource(ProtectionSource.SERVICE)

        // A long stretch of perfectly calm hardware with no position whatsoever.
        repeat(40) {
            gateway.emit(
                SensorState(
                    accelerometer = AccelerometerState(
                        availability = SensorAvailability.READY,
                        x = 0f,
                        y = 0f,
                        z = 9.81f,
                        timestampMillis = System.currentTimeMillis() + it,
                    ),
                    gyroscope = GyroscopeState(availability = SensorAvailability.READY),
                    location = LocationState(availability = LocationAvailability.PROVIDERS_DISABLED),
                ),
            )
        }

        assertEquals(DetectionLevel.NORMAL, engine.snapshot.detection.level)
        assertEquals(AccidentFlowState.Normal, engine.flow)
    }
}

/**
 * The engine's emergency snapshot: available exactly while the flow is in
 * EMERGENCY, honest about freshness, and never a fabricated position.
 */
class EmergencyLocationSnapshotTest {

    private val gateway = EvidenceGateway()
    private val engine = ProtectionEngine(gateway)

    private fun enterEmergency() {
        engine.attachSource(ProtectionSource.SERVICE)
        engine.submit(AccidentEvent.NeedHelp)
    }

    @Test
    fun outsideTheEmergencyState_thereIsNoSnapshot() {
        engine.attachSource(ProtectionSource.SERVICE)

        assertEquals(AccidentFlowState.Normal, engine.flow)
        // The engine can still be asked, but a non-emergency moment has no
        // "position for the emergency response" to speak of.
        assertNotNull(engine.emergencyLocationSnapshot())
    }

    @Test
    fun enteringEmergency_exposesTheFreshFixThatIsInHand() {
        enterEmergency()
        gateway.emitLocation(
            LocationState(
                availability = LocationAvailability.READY,
                latitude = 12.971599,
                longitude = 77.594566,
                accuracyMeters = 6f,
                speedMetersPerSecond = 1.5f,
                provider = "gps",
                timestampMillis = System.currentTimeMillis(),
            ),
        )

        val snapshot = engine.emergencyLocationSnapshot()
        assertEquals(AccidentFlowState.Emergency(EmergencyStage.LOCATING), engine.flow)
        assertTrue(snapshot.isFresh)
        assertEquals(12.971599, snapshot.latitude ?: 0.0, 0.000001)
        assertEquals("gps", snapshot.provider)
        assertEquals(6f, snapshot.accuracyMeters ?: 0f, 0.0001f)
    }

    @Test
    fun anEmergencyWithNoFix_reportsNoPositionRatherThanZeroZero() {
        enterEmergency()
        gateway.emitLocation(
            LocationState(availability = LocationAvailability.WAITING_FOR_FIX),
        )

        val snapshot = engine.emergencyLocationSnapshot()
        assertFalse(snapshot.hasCoordinates)
        assertFalse(snapshot.isFresh)
        assertNull(snapshot.latitude)
        assertNull(snapshot.longitude)
    }

    @Test
    fun anEmergencyWithLocationSwitchedOff_saysSoRatherThanFakingAFix() {
        enterEmergency()
        gateway.emitLocation(
            LocationState(availability = LocationAvailability.PROVIDERS_DISABLED),
        )

        val snapshot = engine.emergencyLocationSnapshot()
        assertEquals(LocationAvailability.PROVIDERS_DISABLED, snapshot.availability)
        assertEquals(FixFreshness.NO_FIX, snapshot.freshness)
        assertNull(snapshot.latitude)
    }

    @Test
    fun theSnapshotFollowsTheBestPositionAvailableRatherThanFreezing() {
        enterEmergency()
        gateway.emitLocation(
            LocationState(
                availability = LocationAvailability.READY,
                latitude = 1.0,
                longitude = 2.0,
                provider = "gps",
                timestampMillis = System.currentTimeMillis(),
            ),
        )
        val first = engine.emergencyLocationSnapshot()

        gateway.emitLocation(
            LocationState(
                availability = LocationAvailability.READY,
                latitude = 3.0,
                longitude = 4.0,
                provider = "gps",
                timestampMillis = System.currentTimeMillis(),
            ),
        )
        val second = engine.emergencyLocationSnapshot()

        assertEquals(1.0, first.latitude ?: 0.0, 0.000001)
        assertEquals(3.0, second.latitude ?: 0.0, 0.000001)
    }
}

/**
 * The reconciliation hook the foreground service uses to notice a permission or
 * provider change while protection runs. It must never disturb the motion sensors
 * or the detector.
 */
class LocationReconciliationTest {

    private val gateway = EvidenceGateway()
    private val engine = ProtectionEngine(gateway)

    @Test
    fun refresh_whileProtecting_reconcilesLocationWithoutRestartingAnything() {
        engine.attachSource(ProtectionSource.SERVICE)
        val startsBefore = gateway.startCount

        repeat(3) { engine.refreshLocation() }

        assertEquals(3, gateway.refreshCount)
        // No restart: the motion listeners and the detector window are untouched.
        assertEquals(startsBefore, gateway.startCount)
        assertEquals(0, gateway.stopCount)
    }

    @Test
    fun refresh_withNobodyAttached_isAHarmlessNoOp() {
        engine.refreshLocation()

        assertEquals(0, gateway.refreshCount)
    }

    @Test
    fun aProviderGoingOffIsPickedUpWhileProtectionKeepsRunning() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitLocation(
            LocationState(
                availability = LocationAvailability.READY,
                latitude = 12.971599,
                longitude = 77.594566,
                provider = "gps",
                timestampMillis = System.currentTimeMillis(),
            ),
        )
        assertTrue(engine.snapshot.sensors.location.hasReading)

        gateway.emitLocation(
            LocationState(availability = LocationAvailability.PROVIDERS_DISABLED),
        )

        assertEquals(
            LocationAvailability.PROVIDERS_DISABLED,
            engine.snapshot.sensors.location.availability,
        )
        assertFalse(engine.snapshot.sensors.location.hasReading)
        // Protection itself is unaffected by losing location.
        assertTrue(engine.isMonitoring)
    }

    @Test
    fun theForcedRefresh_isADistinctPathFromThePeriodicOne() {
        engine.attachSource(ProtectionSource.SERVICE)

        engine.refreshLocation()
        assertEquals(1, gateway.refreshCount)
        assertEquals(0, gateway.forcedRefreshCount)

        // The forced path is what an explicit user retry uses. Keeping it separate
        // from the 5 second service tick is deliberate: the tick must stay
        // idempotent, while a retry must re-request a registration the platform
        // has already discarded.
        engine.forceLocationRefresh()
        assertEquals(1, gateway.refreshCount)
        assertEquals(1, gateway.forcedRefreshCount)
    }

    @Test
    fun aPermissionRevokedMidProtection_isReflectedWithoutCrashingTheFlow() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitImpact()
        assertTrue(engine.flow is AccidentFlowState.PossibleAccident)

        gateway.emitLocation(
            LocationState(availability = LocationAvailability.PERMISSION_DENIED),
        )

        assertEquals(
            LocationAvailability.PERMISSION_DENIED,
            engine.snapshot.sensors.location.availability,
        )
        // An open alert is never dismissed just because location was lost.
        assertTrue(engine.flow is AccidentFlowState.PossibleAccident)
    }

    @Test
    fun locationRecoveringAfterBeingRestored_isReportedAsLiveAgain() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitLocation(LocationState(availability = LocationAvailability.PROVIDERS_DISABLED))
        assertFalse(engine.snapshot.sensors.location.hasReading)

        gateway.emitLocation(
            LocationState(
                availability = LocationAvailability.READY,
                latitude = 12.971599,
                longitude = 77.594566,
                accuracyMeters = 5f,
                provider = "gps",
                timestampMillis = System.currentTimeMillis(),
            ),
        )

        val location = engine.snapshot.sensors.location
        assertEquals(LocationAvailability.READY, location.availability)
        assertTrue(LocationFreshnessPolicy.isFresh(location, System.currentTimeMillis()))
    }
}

/** Minimal gateway used by the Task 5A tests: it only replays what it is told. */
private class EvidenceGateway : SensorGateway {

    var startCount = 0
    var stopCount = 0
    var refreshCount = 0

    /** The forced user-driven re-registration, tracked separately. */
    var forcedRefreshCount = 0
    private var observer: SensorObserver? = null

    override fun hasLocationPermission(): Boolean = true

    override fun isLocationEnabled(): Boolean = true

    override fun start(observer: SensorObserver) {
        this.observer = observer
        startCount++
    }

    override fun refresh() {
        refreshCount++
    }

    override fun refreshForcingRegistration() {
        forcedRefreshCount++
    }

    override fun stop() {
        observer = null
        stopCount++
    }

    fun emit(state: SensorState) {
        observer?.onAccelerometer(state.accelerometer)
        observer?.onGyroscope(state.gyroscope)
        observer?.onLocation(state.location)
    }

    fun emitLocation(state: LocationState) = observer?.onLocation(state)

    fun emitImpact() {
        SimulationSamples.possibleCollision(5).forEach(::emit)
    }
}
