package com.resq.app

import com.resq.app.model.EmergencyResource
import com.resq.app.model.ResourceType
import com.resq.app.model.SafetyState
import com.resq.app.ui.screens.directionsUrlFor
import com.resq.app.ui.screens.launchMapsThenFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Directions URL construction. The URL builder is pure Kotlin (no Android
 * types), so the whole ruleset is verifiable on the JVM without a device or a
 * Maps API key.
 *
 * No network call is made and no paid Maps API is involved: this is only the
 * intent URI that the ACTION_VIEW launch uses.
 */
class EmergencyIntentsTest {

    @Test
    fun validCoordinates_buildGoogleMapsDirectionsUrl() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=11.9332,79.5241",
            directionsUrlFor(11.9332, 79.5241),
        )
    }

    @Test
    fun negativeCoordinates_arePreserved() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=-33.8688,151.2093",
            directionsUrlFor(-33.8688, 151.2093),
        )
    }

    @Test
    fun missingCoordinate_producesNoUrl() {
        assertNull(directionsUrlFor(null, 79.5241))
        assertNull(directionsUrlFor(11.9332, null))
        assertNull(directionsUrlFor(null, null))
    }

    @Test
    fun nonFiniteCoordinate_producesNoUrl() {
        assertNull(directionsUrlFor(Double.NaN, 79.5241))
        assertNull(directionsUrlFor(11.9332, Double.POSITIVE_INFINITY))
        assertNull(directionsUrlFor(Double.NEGATIVE_INFINITY, 79.5241))
    }

    @Test
    fun outOfRangeCoordinate_producesNoUrl() {
        assertNull(directionsUrlFor(91.0, 79.5241))
        assertNull(directionsUrlFor(-91.0, 79.5241))
        assertNull(directionsUrlFor(11.9332, 181.0))
        assertNull(directionsUrlFor(11.9332, -181.0))
    }

    @Test
    fun nullIslandSentinel_isNotATarget() {
        assertNull(directionsUrlFor(0.0, 0.0))
    }

    // ------------------------------------------------------------------
    // Resource-level destination rules
    // ------------------------------------------------------------------

    private fun resource(latitude: Double?, longitude: Double?) = EmergencyResource(
        type = ResourceType.HOSPITAL,
        name = "Near Clinic",
        distanceKm = 0.4,
        detail = "Hospital",
        availability = SafetyState.CAUTION,
        availabilityLabel = "Not verified",
        phone = null,
        address = null,
        latitude = latitude,
        longitude = longitude,
        isMock = false,
    )

    @Test
    fun resourceWithCoordinates_exposesAUsableDestination() {
        assertEquals(11.9332 to 79.5241, resource(11.9332, 79.5241).destination())
    }

    @Test
    fun resourceWithoutCoordinates_hasNoDestination() {
        assertNull(resource(null, null).destination())
        assertNull(resource(11.9332, null).destination())
        assertNull(resource(null, 79.5241).destination())
    }

    @Test
    fun resourceWithImplausibleCoordinates_hasNoDestination() {
        assertNull(resource(999.0, 79.5241).destination())
        assertNull(resource(0.0, 0.0).destination())
    }

    @Test
    fun hasDestinationFrom_isFalseWhenTheResourceIsTheUsersOwnPosition() {
        val atUser = resource(11.9332, 79.5241)
        assertFalse(atUser.hasDestinationFrom(11.9332, 79.5241))
        assertTrue(atUser.hasDestinationFrom(11.9333, 79.5242))
        assertTrue(atUser.hasDestinationFrom(null, null))
    }

    @Test
    fun hasDestinationFrom_isFalseWithoutCoordinatesWhateverTheOrigin() {
        assertFalse(resource(null, null).hasDestinationFrom(11.9332, 79.5241))
    }

    // ------------------------------------------------------------------
    // Launch order: Google Maps first, browser fallback second
    // ------------------------------------------------------------------

    @Test
    fun launch_prefersGoogleMapsAndStopsWhenItWorks() {
        val attempted = mutableListOf<String?>()

        val launched = launchMapsThenFallback { packageName ->
            attempted += packageName
            true
        }

        assertTrue(launched)
        assertEquals(listOf<String?>("com.google.android.apps.maps"), attempted)
    }

    @Test
    fun launch_fallsBackToTheUnqualifiedViewerWhenMapsIsMissing() {
        val attempted = mutableListOf<String?>()

        val launched = launchMapsThenFallback { packageName ->
            attempted += packageName
            packageName == null
        }

        assertTrue(launched)
        assertEquals(listOf<String?>("com.google.android.apps.maps", null), attempted)
    }

    @Test
    fun launch_reportsFailureWhenNothingCanOpenTheLink() {
        val attempted = mutableListOf<String?>()

        val launched = launchMapsThenFallback { packageName ->
            attempted += packageName
            false
        }

        assertFalse(launched)
        assertEquals(listOf<String?>("com.google.android.apps.maps", null), attempted)
    }
}
