package com.resq.app

import com.resq.app.data.MockData
import com.resq.app.data.remote.EmergencyResourceDto
import com.resq.app.data.remote.EmergencyResourcesApi
import com.resq.app.data.remote.EmergencyResourcesSearchResponse
import com.resq.app.data.remote.IncidentTypePossibleAccident
import com.resq.app.data.remote.RemoteEmergencyResourceRepository
import com.resq.app.data.remote.ResourceSearchResult
import com.resq.app.data.remote.toEmergencyResource
import com.resq.app.data.remote.toSearchResult
import com.resq.app.model.ResourceSearchFailure
import com.resq.app.model.ResourceType
import com.resq.app.model.SafetyState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Repository mapping and outcome classification against the backend's actual
 * schema. HTTP is mocked; no live backend or SerpApi call is made.
 */
class EmergencyResourceRepositoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repository() = RemoteEmergencyResourceRepository(
        EmergencyResourcesApi(server.url("/").toString()),
    )

    @Test
    fun successfulSearch_returnsNearestFirstLiveResources(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "status": "success",
                      "resources": [
                        {"name": "Far Hospital", "type": "hospital", "distance_km": 3.6},
                        {"name": "Near Clinic", "type": "emergency_care", "distance_km": 0.4},
                        {
                          "name": "Mid Blood Bank",
                          "type": "blood_bank",
                          "address": "7 Lake Road",
                          "phone": "+91-413-555-0199",
                          "distance_km": 2.4
                        }
                      ],
                      "searched_at": "2026-10-09T00:00:00"
                    }
                    """.trimIndent(),
                ),
        )

        val result = repository().searchNearbyResources(
            latitude = 11.9332,
            longitude = 79.5241,
            incidentType = IncidentTypePossibleAccident,
        )

        assertTrue(result is ResourceSearchResult.Success)
        val success = result as ResourceSearchResult.Success
        assertEquals("2026-10-09T00:00:00", success.searchedAt)
        assertEquals(
            listOf("Near Clinic", "Mid Blood Bank", "Far Hospital"),
            success.resources.map { it.name },
        )
        assertTrue(success.resources.none { it.isMock })
        assertEquals(ResourceType.EMERGENCY_CARE, success.resources[0].type)
        assertEquals("Mid Blood Bank", success.resources[1].name)
        assertEquals("7 Lake Road", success.resources[1].address)
        assertEquals("+91-413-555-0199", success.resources[1].phone)
    }

    @Test
    fun coordinates_areCarriedThroughSoDirectionsHasADestination(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "status": "success",
                      "resources": [
                        {
                          "name": "City General Hospital",
                          "type": "hospital",
                          "coordinates": {"latitude": 11.9340, "longitude": 79.5250},
                          "distance_km": 1.23
                        },
                        {
                          "name": "No Coords Clinic",
                          "type": "hospital",
                          "distance_km": 2.0
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val result = repository().searchNearbyResources(11.9332, 79.5241)

        assertTrue(result is ResourceSearchResult.Success)
        val resources = (result as ResourceSearchResult.Success).resources

        val withCoords = resources.single { it.name == "City General Hospital" }
        assertEquals(11.9340, withCoords.latitude!!, 0.0)
        assertEquals(79.5250, withCoords.longitude!!, 0.0)
        assertEquals(11.9340 to 79.5250, withCoords.destination())
        assertTrue(withCoords.hasDestinationFrom(11.9332, 79.5241))

        // A result without coordinates must not gain a fabricated destination.
        val withoutCoords = resources.single { it.name == "No Coords Clinic" }
        assertNull(withoutCoords.latitude)
        assertNull(withoutCoords.longitude)
        assertNull(withoutCoords.destination())
    }

    @Test
    fun emptySearch_isNoResultsRatherThanAnError(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"no_results","resources":[],"searched_at":"2026-10-09T00:00:00"}"""),
        )

        val result = repository().searchNearbyResources(11.9332, 79.5241)

        assertEquals(ResourceSearchResult.NoResults, result)
    }

    @Test
    fun serverError_mapsToServerFailure(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502))

        val result = repository().searchNearbyResources(11.9332, 79.5241)

        assertEquals(
            ResourceSearchResult.Failure(ResourceSearchFailure.SERVER),
            result,
        )
    }

    @Test
    fun internalServerError_mapsToServerFailure(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = repository().searchNearbyResources(11.9332, 79.5241)

        assertEquals(
            ResourceSearchResult.Failure(ResourceSearchFailure.SERVER),
            result,
        )
    }

    /**
     * A backend that accepts the connection but never answers must still resolve
     * to a usable failure state, not hang the emergency screen. The read timeout
     * is shortened here so the assertion is fast; the production client uses the
     * same bounded-timeout builder documented in [EmergencyResourcesApi].
     */
    @Test
    fun slowBackend_timesOutIntoNetworkFailure(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"success\",\"resources\":[]}")
                .setHeadersDelay(2, TimeUnit.SECONDS),
        )
        val impatient = RemoteEmergencyResourceRepository(
            EmergencyResourcesApi(
                baseUrl = server.url("/").toString(),
                httpClient = OkHttpClient.Builder()
                    .connectTimeout(1, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.MILLISECONDS)
                    .callTimeout(2, TimeUnit.SECONDS)
                    .build(),
            ),
        )

        val result = impatient.searchNearbyResources(11.9332, 79.5241)

        assertEquals(
            ResourceSearchResult.Failure(ResourceSearchFailure.NETWORK),
            result,
        )
    }

    @Test
    fun invalidPayload_mapsToInvalidResponseFailure(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))

        val result = repository().searchNearbyResources(11.9332, 79.5241)

        assertEquals(
            ResourceSearchResult.Failure(ResourceSearchFailure.INVALID_RESPONSE),
            result,
        )
    }

    @Test
    fun unreachableBackend_mapsToNetworkFailure(): Unit = runBlocking {
        val unreachable = MockWebServer()
        unreachable.start()
        val repository = RemoteEmergencyResourceRepository(
            EmergencyResourcesApi(unreachable.url("/").toString()),
        )
        unreachable.shutdown()

        val result = repository.searchNearbyResources(11.9332, 79.5241)

        assertEquals(
            ResourceSearchResult.Failure(ResourceSearchFailure.NETWORK),
            result,
        )
    }

    @Test
    fun missingOptionalFields_degradeToNullInsteadOfPlaceholders() {
        val result = EmergencyResourcesSearchResponse(
            status = "success",
            resources = listOf(EmergencyResourceDto(name = "Riverside Clinic")),
            searchedAt = "2026-10-09T00:00:00",
        ).toSearchResult()

        assertTrue(result is ResourceSearchResult.Success)
        val resource = (result as ResourceSearchResult.Success).resources.single()
        assertEquals("Riverside Clinic", resource.name)
        assertEquals(ResourceType.HOSPITAL, resource.type)
        assertNull(resource.address)
        assertNull(resource.phone)
        assertNull(resource.distanceKm)
        assertEquals(SafetyState.CAUTION, resource.availability)
        assertEquals("Not verified", resource.availabilityLabel)
        assertFalse(resource.isMock)
    }

    @Test
    fun blankNamesAndContactDetails_fallBackToHonestDefaults() {
        val resource = EmergencyResourceDto(
            name = "   ",
            type = "hospital",
            address = "  ",
            phone = "",
        ).toEmergencyResource()

        assertEquals("Unnamed resource", resource.name)
        assertNull(resource.address)
        assertNull(resource.phone)
    }

    @Test
    fun resourcesWithoutDistances_sortAfterMeasuredDistances() {
        val result = EmergencyResourcesSearchResponse(
            status = "success",
            resources = listOf(
                EmergencyResourceDto(name = "Unmeasured", type = "hospital"),
                EmergencyResourceDto(name = "Near", type = "hospital", distanceKm = 0.2),
            ),
            searchedAt = null,
        ).toSearchResult()

        assertTrue(result is ResourceSearchResult.Success)
        assertEquals(
            listOf("Near", "Unmeasured"),
            (result as ResourceSearchResult.Success).resources.map { it.name },
        )
    }

    @Test
    fun mockFixtures_stayExplicitlyLabeled() {
        assertTrue(MockData.emergencyResources.isNotEmpty())
        assertTrue(MockData.emergencyResources.all { it.isMock })
    }
}
