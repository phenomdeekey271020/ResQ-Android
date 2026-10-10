package com.resq.app

import com.resq.app.model.EmergencyResource
import com.resq.app.model.ResourceSearchFailure
import com.resq.app.model.ResourceType
import com.resq.app.model.SafetyState
import com.resq.app.ui.screens.formatDistance
import com.resq.app.ui.screens.failureCopy
import com.resq.app.ui.screens.nearestHospitalIndex
import com.resq.app.ui.screens.nearestRecommendationCopy
import com.resq.app.ui.screens.shortLabel
import com.resq.app.ui.screens.showsOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact wording the emergency screen shows for a failed search and for a
 * result's distance. These strings are user-facing and are asserted so a future
 * refactor cannot quietly start leaking exception text, a response body or a
 * coordinate into the UI.
 */
class EmergencyResourceCopyTest {

    @Test
    fun failureCopy_explainsEachCauseWithoutLeakingDetails() {
        assertEquals(
            "Could not reach the resource service. Check the connection and retry.",
            failureCopy(ResourceSearchFailure.NETWORK),
        )
        assertEquals(
            "The resource service had a problem. Retry in a moment.",
            failureCopy(ResourceSearchFailure.SERVER),
        )
        assertEquals(
            "The resource service sent an unexpected response. Retry.",
            failureCopy(ResourceSearchFailure.INVALID_RESPONSE),
        )
    }

    @Test
    fun failureCopy_neverMentionsCoordinatesOrRawErrors() {
        ResourceSearchFailure.entries.forEach { reason ->
            val copy = failureCopy(reason)
            assertFalse(copy.contains("Exception"))
            assertFalse(copy.contains("latitude"))
            assertFalse(copy.contains("longitude"))
            assertFalse(copy.contains("http"))
        }
    }

    @Test
    fun formatDistance_rendersOneDecimalOrSaysItIsUnknown() {
        assertEquals("2.4 km", formatDistance(2.4))
        assertEquals("0.0 km", formatDistance(0.04))
        assertEquals("Distance unknown", formatDistance(null))
    }

    @Test
    fun resourceTypeLabels_areHumanReadable() {
        assertEquals("Hospital", ResourceType.HOSPITAL.shortLabel())
        assertEquals("Emergency care", ResourceType.EMERGENCY_CARE.shortLabel())
        assertEquals("Ambulance", ResourceType.AMBULANCE.shortLabel())
        assertEquals("Blood bank", ResourceType.BLOOD_BANK.shortLabel())
    }

    // ------------------------------------------------------------------
    // Nearest-hospital recommendation (distance-based, never medical)
    // ------------------------------------------------------------------

    @Test
    fun theNearestHighlight_onlyEverPointsAtAHospitalWithARealDistance() {
        val hospital = hospital(distanceKm = 2.4)
        val hospitalWithoutDistance = hospital(distanceKm = null)
        val ambulance = resource(ResourceType.AMBULANCE, distanceKm = 1.1)

        // An ambulance closer than the hospital must not steal the highlight.
        assertEquals(1, nearestHospitalIndex(listOf(ambulance, hospital)))
        // A hospital with no distance can never be claimed "nearest".
        assertEquals(-1, nearestHospitalIndex(listOf(hospitalWithoutDistance)))
        assertEquals(-1, nearestHospitalIndex(emptyList()))
        assertEquals(0, nearestHospitalIndex(listOf(hospital, ambulance)))
    }

    @Test
    fun theRecommendationCopy_saysDistanceOnlyAndOnlyWhenEarned() {
        val withHospital = nearestRecommendationCopy(listOf(hospital(distanceKm = 2.4)))
        requireNotNull(withHospital)
        assertTrue(withHospital.contains("distance"))
        assertFalse(withHospital.contains("guarantee"))
        assertFalse(withHospital.contains("open now"))

        // No hospital with a distance: no recommendation at all rather than a
        // guess.
        assertEquals(null, nearestRecommendationCopy(emptyList()))
        assertEquals(
            null,
            nearestRecommendationCopy(listOf(hospital(distanceKm = null))),
        )
    }

    @Test
    fun ownershipIsOnlyShownForPlacesAndIsLabeledUnknownNotGuessed() {
        // The backend carries no ownership data, so every walk-in place shows
        // "Unknown" and non-place resources show no ownership claim at all.
        assertTrue(hospital(distanceKm = 1.0).showsOwnership)
        assertTrue(resource(ResourceType.EMERGENCY_CARE, 1.0).showsOwnership)
        assertFalse(resource(ResourceType.AMBULANCE, 1.0).showsOwnership)
        assertFalse(resource(ResourceType.BLOOD_BANK, 1.0).showsOwnership)
    }

    private fun hospital(distanceKm: Double?) = resource(ResourceType.HOSPITAL, distanceKm)

    private fun resource(type: ResourceType, distanceKm: Double?) = EmergencyResource(
        type = type,
        name = "City General Hospital",
        distanceKm = distanceKm,
        detail = "Hospital",
        availability = SafetyState.CAUTION,
        availabilityLabel = "Not verified",
        phone = null,
        address = null,
        isMock = false,
    )
}
