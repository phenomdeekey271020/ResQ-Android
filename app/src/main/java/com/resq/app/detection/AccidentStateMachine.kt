package com.resq.app.detection

import com.resq.app.model.DetectionConfig
import com.resq.app.model.DetectionLevel
import com.resq.app.model.confidence
import com.resq.app.model.countdownSeconds

/*
 * The accident flow, kept separate from the detection engine on purpose.
 *
 *   Detection engine -> DetectionLevel -> this state machine -> emergency
 *
 * The engine decides how strong the evidence is; this decides what the app is
 * allowed to do next. Because the two are separate, EMERGENCY is unreachable
 * from a sensor value alone - it always needs either the countdown running out
 * without an answer or an explicit "I need help" from the user.
 *
 * Every transition is a pure function of (current state, event, now), so the
 * whole flow is unit testable without timers, Compose or Android.
 */

/** Stage of the local emergency preparation the flow has reached. */
enum class EmergencyStage {
    LOCATING,
    LINKING_CONTACTS,
    SEARCHING_RESOURCES,
    READY,
}

/** Everything that can move the accident flow forward. */
sealed interface AccidentEvent {

    /** The detector reported a level. NORMAL never opens an alert on its own. */
    data class Detected(val level: DetectionLevel) : AccidentEvent

    /** The user said they are fine. */
    data object UserOk : AccidentEvent

    /** The user asked for help, from anywhere in the flow. */
    data object NeedHelp : AccidentEvent

    /** The countdown ran out, or the user ignored the confirmation prompt. */
    data object NoResponse : AccidentEvent

    /**
     * Diagnostics-only hook: jump straight to [AccidentFlowState.Confirmation] so
     * the unconfirmed screen can be inspected without waiting out a countdown.
     * It is never produced by a sensor sample.
     */
    data object ForceConfirmation : AccidentEvent

    /** One second of the "are you okay?" countdown elapsed. */
    data object CountdownTick : AccidentEvent

    /** The local emergency preparation moved to its next stage. */
    data object EmergencyAdvanced : AccidentEvent

    /** Diagnostics reset - always returns to NORMAL and clears the cooldown. */
    data object Reset : AccidentEvent
}

/** Current position in the accident flow. */
sealed interface AccidentFlowState {

    /** Short name used for logging and tests. */
    val name: String

    /** Nothing is wrong. Also the cooldown state. */
    data object Normal : AccidentFlowState {
        override val name: String get() = "NORMAL"
    }

    /** An accident was reported and the "are you okay?" countdown is running. */
    data class PossibleAccident(
        val secondsRemaining: Int,
        val level: DetectionLevel,
    ) : AccidentFlowState {
        override val name: String get() = "POSSIBLE_ACCIDENT"
    }

    /** The window closed without an answer. Safety is unconfirmed. */
    data object Confirmation : AccidentFlowState {
        override val name: String get() = "CONFIRMATION"
    }

    /** The user asked for help, or nobody answered. */
    data class Emergency(val stage: EmergencyStage) : AccidentFlowState {
        override val name: String get() = "EMERGENCY"
    }
}

/**
 * Pure transition table for [AccidentFlowState].
 *
 * The cooldown means that once an incident is resolved the detector is ignored
 * briefly, so the tail of one crash cannot immediately raise a second alert.
 */
class AccidentStateMachine(
    private val config: DetectionConfig = DetectionConfig(),
) {

    var state: AccidentFlowState = AccidentFlowState.Normal
        private set

    private var cooldownUntilMillis: Long = Long.MIN_VALUE

    /** True while a recently resolved incident is still being ignored. */
    fun isCoolingDown(nowMillis: Long): Boolean =
        state == AccidentFlowState.Normal && nowMillis < cooldownUntilMillis

    /** Clears the flow and the cooldown. */
    fun reset() {
        state = AccidentFlowState.Normal
        cooldownUntilMillis = Long.MIN_VALUE
    }

    /**
     * Sets a flat alert-countdown override (null → per-level values). Applied
     * the next time a PossibleAccident is created.
     */
    fun setAlertCountdownSeconds(seconds: Int?) {
        config.alertCountdownSeconds = seconds
    }

    /** Applies [event] at [nowMillis] and returns the new state. */
    fun reduce(event: AccidentEvent, nowMillis: Long): AccidentFlowState {
        val next = transition(state, event, nowMillis)
        state = next
        return next
    }

    private fun transition(
        current: AccidentFlowState,
        event: AccidentEvent,
        nowMillis: Long,
    ): AccidentFlowState {
        if (event == AccidentEvent.Reset) {
            cooldownUntilMillis = Long.MIN_VALUE
            return AccidentFlowState.Normal
        }
        if (event == AccidentEvent.NeedHelp) {
            // Idempotent once the emergency is open. The emergency screen shares
            // its "I need help" affordance with the alert screen during the
            // crossfade, and an unconditional restart would rewind an advanced
            // stage back to LOCATING - cancelling the stage timers with it and
            // leaving the screen stuck on "Preparing emergency".
            return if (current is AccidentFlowState.Emergency) {
                current
            } else {
                AccidentFlowState.Emergency(EmergencyStage.LOCATING)
            }
        }
        if (event == AccidentEvent.EmergencyAdvanced) return advanceEmergency(current)

        return when (current) {
            AccidentFlowState.Normal -> when (event) {
                is AccidentEvent.Detected ->
                    if (event.level == DetectionLevel.NORMAL || nowMillis < cooldownUntilMillis) {
                        AccidentFlowState.Normal
                    } else {
                        AccidentFlowState.PossibleAccident(
                            secondsRemaining = config.alertCountdownSeconds
                                ?: event.level.confidence?.countdownSeconds ?: 0,
                            level = event.level,
                        )
                    }

                AccidentEvent.ForceConfirmation -> AccidentFlowState.Confirmation

                // Nothing to confirm and nothing to escalate - the app stays calm.
                else -> AccidentFlowState.Normal
            }

            is AccidentFlowState.PossibleAccident -> when (event) {
                is AccidentEvent.CountdownTick ->
                    if (current.secondsRemaining <= 1) {
                        AccidentFlowState.Confirmation
                    } else {
                        current.copy(secondsRemaining = current.secondsRemaining - 1)
                    }

                AccidentEvent.UserOk -> coolingDown(nowMillis)
                AccidentEvent.NoResponse -> AccidentFlowState.Confirmation
                AccidentEvent.ForceConfirmation -> AccidentFlowState.Confirmation

                // A second detection while the alert is already up changes nothing,
                // so the countdown cannot be restarted by ongoing samples.
                is AccidentEvent.Detected -> current
                else -> current
            }

            AccidentFlowState.Confirmation -> when (event) {
                AccidentEvent.UserOk -> coolingDown(nowMillis)

                // The unconfirmed window itself closed without an answer: this is
                // the only route from CONFIRMATION to EMERGENCY.
                AccidentEvent.NoResponse, AccidentEvent.ForceConfirmation ->
                    AccidentFlowState.Emergency(EmergencyStage.LOCATING)

                is AccidentEvent.Detected -> current
                else -> current
            }

            is AccidentFlowState.Emergency -> current
        }
    }

    private fun advanceEmergency(current: AccidentFlowState): AccidentFlowState {
        val stage = (current as? AccidentFlowState.Emergency)?.stage ?: return current
        return AccidentFlowState.Emergency(
            when (stage) {
                EmergencyStage.LOCATING -> EmergencyStage.LINKING_CONTACTS
                EmergencyStage.LINKING_CONTACTS -> EmergencyStage.SEARCHING_RESOURCES
                EmergencyStage.SEARCHING_RESOURCES -> EmergencyStage.READY
                EmergencyStage.READY -> EmergencyStage.READY
            },
        )
    }

    private fun coolingDown(nowMillis: Long): AccidentFlowState {
        cooldownUntilMillis = nowMillis + config.cooldownMillis
        return AccidentFlowState.Normal
    }
}

/**
 * PROTOTYPE HEURISTICS - how long the "are you okay?" window is per level.
 *
 * Deliberately not re-invented here: the countdown length already lives with the
 * incident model, so the detection levels and the UI cannot drift apart.
 */
private val DetectionLevel.confidenceSeconds: Int
    get() = confidence?.countdownSeconds ?: 0