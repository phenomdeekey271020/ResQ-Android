package com.resq.app

import com.resq.app.data.sensors.SensorGateway
import com.resq.app.data.sensors.SensorObserver
import com.resq.app.detection.AccidentEvent
import com.resq.app.detection.AccidentFlowState
import com.resq.app.detection.EmergencyStage
import com.resq.app.detection.SimulationSamples
import com.resq.app.model.AccelerometerState
import com.resq.app.model.DetectionConfig
import com.resq.app.model.DetectionLevel
import com.resq.app.model.GyroscopeState
import com.resq.app.model.LocationAvailability
import com.resq.app.model.LocationState
import com.resq.app.model.SensorAvailability
import com.resq.app.model.SensorState
import com.resq.app.protection.CountdownOwner
import com.resq.app.protection.ProtectionEngine
import com.resq.app.protection.ProtectionEngineListener
import com.resq.app.protection.ProtectionSnapshot
import com.resq.app.protection.ProtectionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory gateway standing in for SensorManager and LocationManager, so the
 * service coordination can be verified on the JVM with no Android, no hardware
 * and - deliberately - no real crash.
 */
private class FakeProtectionGateway : SensorGateway {

    var startCount = 0
    var stopCount = 0
    var refreshCount = 0
    var forcedRefreshCount = 0
    var hasPermission = true
    var providersEnabled = true
    private var observer: SensorObserver? = null

    val isRunning: Boolean get() = observer != null

    override fun hasLocationPermission(): Boolean = hasPermission

    override fun isLocationEnabled(): Boolean = providersEnabled

    override fun start(observer: SensorObserver) {
        this.observer = observer
        startCount++
        observer.onAccelerometer(
            AccelerometerState(availability = SensorAvailability.WAITING),
        )
        observer.onGyroscope(GyroscopeState(availability = SensorAvailability.WAITING))
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

    /** Replays one snapshot exactly as the platform gateway would deliver it. */
    fun emit(sensorState: SensorState) {
        observer?.onAccelerometer(sensorState.accelerometer)
        observer?.onGyroscope(sensorState.gyroscope)
        observer?.onLocation(sensorState.location)
    }

    fun emitImpact(count: Int = 5) {
        SimulationSamples.possibleCollision(count).forEach(::emit)
    }

    /** One accelerometer-only delivery, so a window entry cannot be counted twice. */
    fun emitAccelerometer(accelerometerState: AccelerometerState) {
        observer?.onAccelerometer(accelerometerState)
    }

    fun emitGyroscope(gyroscopeState: GyroscopeState) {
        observer?.onGyroscope(gyroscopeState)
    }
}

/**
 * Behavioural tests for the shared protection pipeline the foreground service
 * drives: the hardware lifecycle, the hand-off to the existing detector and state
 * machine, and the ownership rules that keep exactly one set of listeners and one
 * countdown alive.
 */
class ProtectionEngineTest {

    private val config = DetectionConfig()
    private lateinit var gateway: FakeProtectionGateway
    private lateinit var engine: ProtectionEngine

    @Before
    fun setUp() {
        gateway = FakeProtectionGateway()
        engine = ProtectionEngine(gateway, config)
    }

    // ------------------------------------------------------------------
    // Sensor lifecycle: register once, unregister once
    // ------------------------------------------------------------------

    @Test
    fun firstConsumer_startsCollectionExactlyOnce() {
        engine.attachSource(ProtectionSource.SERVICE)

        assertEquals(1, gateway.startCount)
        assertTrue(gateway.isRunning)
        assertTrue(engine.isMonitoring)
        assertEquals(ProtectionSource.SERVICE, engine.snapshot.sources.single())
    }

    @Test
    fun attachingTheSameSourceTwice_doesNotRegisterTheSensorsTwice() {
        engine.attachSource(ProtectionSource.SERVICE)
        engine.attachSource(ProtectionSource.SERVICE)

        assertEquals(1, gateway.startCount)
        assertTrue(gateway.isRunning)
    }

    @Test
    fun aSecondConsumer_joinsTheRunningCollectionInsteadOfStartingANewOne() {
        engine.attachSource(ProtectionSource.SERVICE)
        engine.attachSource(ProtectionSource.DIAGNOSTICS_UI)

        assertEquals(1, gateway.startCount)
        assertEquals(2, engine.snapshot.sources.size)
    }

    @Test
    fun leavingOneConsumer_keepsProtectionRunning() {
        engine.attachSource(ProtectionSource.SERVICE)
        engine.attachSource(ProtectionSource.DIAGNOSTICS_UI)

        engine.detachSource(ProtectionSource.DIAGNOSTICS_UI)

        assertEquals(0, gateway.stopCount)
        assertTrue(gateway.isRunning)
        assertTrue(engine.isMonitoring)
    }

    @Test
    fun theLastConsumerLeaving_stopsTheSensorsAndClearsTheAssessment() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitImpact(3)
        assertTrue(engine.snapshot.detection.evidence.abnormalSamples > 0)

        engine.detachSource(ProtectionSource.SERVICE)

        assertEquals(1, gateway.stopCount)
        assertFalse(gateway.isRunning)
        assertFalse(engine.isMonitoring)
        // Window and assessment are both cleared, so evidence collected before the
        // pause can never be combined with samples that arrive after it.
        assertEquals(0, engine.snapshot.detection.evidence.abnormalSamples)
        assertEquals(DetectionLevel.NORMAL, engine.snapshot.detection.level)
        // An alert that is already open is deliberately NOT dismissed: losing the
        // hardware must never quietly close an incident the user still has to
        // answer. The UI refuses to stop protection while an alert is open.
        assertTrue(engine.flow is AccidentFlowState.PossibleAccident)
    }

    @Test
    fun restartingProtectionAfterAPause_startsFromAnEmptyWindow() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitImpact(3)
        engine.detachSource(ProtectionSource.SERVICE)

        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitAccelerometer(
            SimulationSamples.possibleCollision(1).first().accelerometer,
        )

        assertEquals(1, engine.snapshot.detection.evidence.abnormalSamples)
    }

    @Test
    fun detachingAnUnknownSource_isHarmless() {
        engine.detachSource(ProtectionSource.DIAGNOSTICS_UI)

        assertEquals(0, gateway.stopCount)
        assertFalse(engine.isMonitoring)
    }

    @Test
    fun aRestartRequest_reRegistersSoANewPermissionTakesEffect() {
        engine.attachSource(ProtectionSource.DIAGNOSTICS_UI)
        engine.attachSource(ProtectionSource.DIAGNOSTICS_UI, restart = true)

        assertEquals(2, gateway.startCount)
        assertTrue(gateway.isRunning)
    }

    // ------------------------------------------------------------------
    // Samples -> detector -> assessment
    // ------------------------------------------------------------------

    @Test
    fun realSamples_areScoredAndPublished() {
        engine.attachSource(ProtectionSource.SERVICE)

        gateway.emitImpact()

        val snapshot = engine.snapshot
        assertEquals(DetectionLevel.POSSIBLE, snapshot.detection.level)
        assertTrue(snapshot.detection.hasSensorData)
        assertTrue(snapshot.detection.confidence > 0)
        assertTrue(snapshot.sensors.accelerometer.hasReading)
        assertTrue(snapshot.sensors.gyroscope.hasReading)
    }

    @Test
    fun noConsumer_meansNoSamplesAndNoAssessment() {
        gateway.emitImpact()

        assertFalse(engine.snapshot.sensors.accelerometer.hasReading)
        assertEquals(DetectionLevel.NORMAL, engine.snapshot.detection.level)
    }

    @Test
    fun pausedProtection_publishesSensorsButScoresNothing() {
        engine.attachSource(ProtectionSource.SERVICE)
        engine.detectionEnabled = false

        gateway.emitImpact()

        assertTrue(engine.snapshot.sensors.accelerometer.hasReading)
        assertEquals(DetectionLevel.NORMAL, engine.snapshot.detection.level)
        assertEquals(AccidentFlowState.Normal, engine.flow)
    }

    @Test
    fun aLostAccelerometer_failsSafe() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitImpact()

        gateway.emit(
            SensorState(accelerometer = AccelerometerState(SensorAvailability.UNAVAILABLE)),
        )

        assertEquals(DetectionLevel.NORMAL, engine.snapshot.detection.level)
        assertFalse(engine.snapshot.detection.hasSensorData)
    }

    @Test
    fun missingLocation_isNeverPositiveEvidence() {
        engine.attachSource(ProtectionSource.SERVICE)

        gateway.emit(
            SimulationSamples.highConfidenceCollision(8).last().copy(
                location = LocationState(availability = LocationAvailability.WAITING_FOR_PERMISSION),
            ),
        )

        assertFalse(engine.snapshot.detection.evidence.motionContext)
    }

    // ------------------------------------------------------------------
    // Samples -> state machine (only continuous protection escalates)
    // ------------------------------------------------------------------

    @Test
    fun continuousProtection_opensTheConfirmationWindowOnRealSamples() {
        engine.attachSource(ProtectionSource.SERVICE)

        gateway.emitImpact()

        val flow = engine.flow
        assertTrue(flow is AccidentFlowState.PossibleAccident)
        assertEquals(DetectionLevel.POSSIBLE, (flow as AccidentFlowState.PossibleAccident).level)
        assertEquals(10, flow.secondsRemaining)
    }

    @Test
    fun aViolentRealImpact_usesTheShorterWindow() {
        engine.attachSource(ProtectionSource.SERVICE)

        SimulationSamples.highConfidenceCollision(8).forEach(gateway::emit)

        val flow = engine.flow as AccidentFlowState.PossibleAccident
        assertEquals(DetectionLevel.HIGH_CONFIDENCE, flow.level)
        assertEquals(5, flow.secondsRemaining)
    }

    @Test
    fun aDiagnosticsPreview_scoresButNeverOpensAnAlert() {
        engine.attachSource(ProtectionSource.DIAGNOSTICS_UI)

        gateway.emitImpact()

        assertEquals(DetectionLevel.POSSIBLE, engine.snapshot.detection.level)
        assertEquals(AccidentFlowState.Normal, engine.flow)
    }

    @Test
    fun hardBraking_neverEscalatesEvenWhileProtecting() {
        engine.attachSource(ProtectionSource.SERVICE)

        SimulationSamples.hardBraking(12).forEach(gateway::emit)

        assertEquals(DetectionLevel.NORMAL, engine.snapshot.detection.level)
        assertEquals(AccidentFlowState.Normal, engine.flow)
    }

    @Test
    fun ongoingSamples_neverRestartARunningCountdown() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitImpact()
        engine.submit(AccidentEvent.CountdownTick)
        val secondsAfterFirstTick = (engine.flow as AccidentFlowState.PossibleAccident).secondsRemaining

        gateway.emitImpact()

        assertEquals(
            secondsAfterFirstTick,
            (engine.flow as AccidentFlowState.PossibleAccident).secondsRemaining,
        )
    }

    @Test
    fun theResolvedCooldown_stopsTheSameEvidenceFromReOpeningTheAlert() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitImpact()
        engine.submit(AccidentEvent.UserOk)
        assertEquals(AccidentFlowState.Normal, engine.flow)

        gateway.emitImpact()

        assertEquals(AccidentFlowState.Normal, engine.flow)
    }

    // ------------------------------------------------------------------
    // The shared state machine
    // ------------------------------------------------------------------

    @Test
    fun anExhaustedCountdown_reachesConfirmation() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitImpact()

        repeat(10) { engine.submit(AccidentEvent.CountdownTick) }

        assertEquals(AccidentFlowState.Confirmation, engine.flow)
    }

    @Test
    fun confirmationPlusNoResponse_reachesTheEmergencyState() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitImpact()
        repeat(10) { engine.submit(AccidentEvent.CountdownTick) }

        engine.submit(AccidentEvent.NoResponse)

        assertEquals(AccidentFlowState.Emergency(EmergencyStage.LOCATING), engine.flow)
    }

    @Test
    fun emergencyProgressesThroughItsStagesAndStopsAtReady() {
        engine.submit(AccidentEvent.NeedHelp)
        engine.submit(AccidentEvent.EmergencyAdvanced)
        engine.submit(AccidentEvent.EmergencyAdvanced)
        engine.submit(AccidentEvent.EmergencyAdvanced)

        assertEquals(AccidentFlowState.Emergency(EmergencyStage.READY), engine.flow)
    }

    @Test
    fun reset_returnsTheWholePipelineToNormal() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitImpact()
        engine.submit(AccidentEvent.NeedHelp)

        engine.reset()

        val snapshot = engine.snapshot
        assertEquals(AccidentFlowState.Normal, snapshot.flow)
        assertEquals(DetectionLevel.NORMAL, snapshot.detection.level)
        assertEquals(0, snapshot.detection.confidence)
    }

    // ------------------------------------------------------------------
    // Countdown ownership: exactly one ticker, whoever is in front
    // ------------------------------------------------------------------

    @Test
    fun anUnownedCountdown_isClaimedByWhicheverSideAsksFirst() {
        assertTrue(engine.requestCountdown(CountdownOwner.SERVICE))
        assertEquals(CountdownOwner.SERVICE, engine.countdownOwner)
        assertFalse(engine.requestCountdown(CountdownOwner.NONE))
    }

    @Test
    fun aVisibleUi_outranksTheBackgroundService() {
        engine.requestCountdown(CountdownOwner.SERVICE)

        assertTrue(engine.requestCountdown(CountdownOwner.APP_UI))

        assertEquals(CountdownOwner.APP_UI, engine.countdownOwner)
    }

    @Test
    fun theService_cannotStealTheCountdownBackFromTheUi() {
        engine.requestCountdown(CountdownOwner.APP_UI)

        assertFalse(engine.requestCountdown(CountdownOwner.SERVICE))
    }

    @Test
    fun releasingTheCountdown_letsTheServiceTakeOverAgain() {
        engine.requestCountdown(CountdownOwner.APP_UI)
        engine.releaseCountdown(CountdownOwner.APP_UI)

        assertEquals(CountdownOwner.NONE, engine.countdownOwner)
        assertTrue(engine.requestCountdown(CountdownOwner.SERVICE))
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    @Test
    fun aNewListener_isGivenTheCurrentSnapshotImmediately() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitImpact()
        val received = mutableListOf<ProtectionSnapshot>()

        engine.addListener(ProtectionEngineListener { received += it })

        assertEquals(1, received.size)
        assertEquals(ProtectionSource.SERVICE, received.single().sources.single())
    }

    @Test
    fun aRemovedListener_hearsNothingMore() {
        val received = mutableListOf<ProtectionSnapshot>()
        val listener = ProtectionEngineListener { received += it }
        engine.addListener(listener)

        engine.attachSource(ProtectionSource.SERVICE)
        val countAfterAttach = received.size
        engine.removeListener(listener)

        gateway.emitImpact()

        assertEquals(countAfterAttach, received.size)
    }

    @Test
    fun anUnchangedSnapshot_isNotRepublished() {
        val received = mutableListOf<ProtectionSnapshot>()
        engine.addListener(ProtectionEngineListener { received += it })

        engine.attachSource(ProtectionSource.SERVICE)
        val afterAttach = received.size

        // Re-attaching a source that is already attached changes nothing, so no
        // needless recomposition is triggered anywhere downstream.
        engine.attachSource(ProtectionSource.SERVICE)

        assertEquals(afterAttach, received.size)
    }

    @Test
    fun aSingleViolentSample_neverLooksLikeSeveralAgreeingOnes() {
        engine.attachSource(ProtectionSource.SERVICE)
        val impact = SimulationSamples.highConfidenceCollision(1).first()

        // One violent accelerometer sample, followed by gyroscope callbacks on
        // their own: they describe the same instant, not new evidence.
        gateway.emitAccelerometer(impact.accelerometer)
        repeat(4) { gateway.emitGyroscope(impact.gyroscope) }

        val snapshot = engine.snapshot
        assertEquals(1, snapshot.detection.evidence.abnormalSamples)
        assertEquals(1, snapshot.detection.evidence.windowSamples)
        assertEquals(DetectionLevel.NORMAL, snapshot.detection.level)
        assertEquals(AccidentFlowState.Normal, engine.flow)
    }

    @Test
    fun rawSamples_areCoalescedButNothingTheUserMustAnswerIs() {
        val coalescing = ProtectionEngine(gateway, config, samplePublishIntervalMillis = 60_000L)
        val received = mutableListOf<ProtectionSnapshot>()
        coalescing.addListener(ProtectionEngineListener { received += it })
        coalescing.attachSource(ProtectionSource.SERVICE)
        val afterAttach = received.size

        // Calm samples are dropped for the UI for a whole interval ...
        SimulationSamples.normal(8).forEach { gateway.emit(it) }
        assertEquals(afterAttach, received.size)
        // ... but the sensor values themselves are still current in the snapshot.
        assertTrue(coalescing.snapshot.sensors.accelerometer.hasReading)

        // An impact is never delayed: the flow change is delivered immediately.
        // Timestamps are pushed forward because both synthetic sequences start at 0.
        SimulationSamples.possibleCollision(5).forEach { sample ->
            gateway.emit(
                sample.copy(
                    accelerometer = sample.accelerometer.copy(
                        timestampMillis = sample.accelerometer.timestampMillis + 1_000L,
                    ),
                ),
            )
        }

        assertTrue(received.size > afterAttach)
        assertTrue(received.last().flow is AccidentFlowState.PossibleAccident)
        assertEquals(DetectionLevel.POSSIBLE, received.last().detection.level)
    }
}