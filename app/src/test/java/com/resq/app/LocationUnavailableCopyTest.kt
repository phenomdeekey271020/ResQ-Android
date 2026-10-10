package com.resq.app

import com.resq.app.model.LocationAvailability
import com.resq.app.ui.screens.locationUnavailableReason
import org.junit.Test

/**
 * The exact reason the location-unavailable emergency screen shows. The screen
 * promises to explain the ACTUAL problem, so every platform state must map to
 * a distinct, non-empty, jargon-free sentence - and never to coordinates, an
 * exception or a stack trace.
 */
class LocationUnavailableCopyTest {

    @Test
    fun everyLocationState_hasANonEmptyExplanation() {
        LocationAvailability.entries.forEach { availability ->
            val copy = locationUnavailableReason(availability)
            assert(copy.isNotBlank()) { "No copy for $availability" }
        }
    }

    @Test
    fun aMissingSnapshot_isStillExplainedRatherThanLeftBlank() {
        val copy = locationUnavailableReason(null)
        assert(copy.isNotBlank())
    }

    @Test
    fun eachDistinctCause_saysSomethingDistinct() {
        val reasons = (LocationAvailability.entries + null)
            .map { availability -> locationUnavailableReason(availability) }
            .toSet()
        // One sentence per state (null included), so the screen can never
        // blame the wrong subsystem for the missing position.
        assert(reasons.size == LocationAvailability.entries.size + 1) {
            "Expected a unique sentence per state, got ${reasons.size}"
        }
    }

    @Test
    fun theCopy_neverLeaksCoordinatesErrorsOrBackendDetails() {
        (LocationAvailability.entries + null).forEach { availability ->
            val copy = locationUnavailableReason(availability)
            assert(!copy.contains("Exception"))
            assert(!copy.contains("latitude"))
            assert(!copy.contains("longitude"))
            assert(!copy.contains("http"))
        }
    }

    @Test
    fun permissionAndProviderFailures_sayWhichOneItIs() {
        assert(locationUnavailableReason(LocationAvailability.PERMISSION_DENIED).contains("denied"))
        assert(
            locationUnavailableReason(LocationAvailability.PROVIDERS_DISABLED)
                .contains("switched off"),
        )
        assert(
            locationUnavailableReason(LocationAvailability.WAITING_FOR_PERMISSION)
                .contains("not been allowed"),
        )
    }
}
