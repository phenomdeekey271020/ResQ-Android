package com.resq.app

import com.resq.app.data.remote.EmergencyResourcesApi
import com.resq.app.data.remote.EmergencyResourcesApiResult
import com.resq.app.data.remote.EmergencyResourcesSearchRequest
import com.resq.app.data.remote.IncidentTypePossibleAccident
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Backend contract tests using an in-process HTTP server. No SerpApi or live
 * backend is involved: every response below is canned, and URLs stay on
 * localhost.
 */
class EmergencyResourcesApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: EmergencyResourcesApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = EmergencyResourcesApi(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun search_postsSnakeCaseBodyToSearchEndpoint(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"success","resources":[],"searched_at":"2026-10-09T00:00:00"}"""),
        )

        val result = api.search(
            EmergencyResourcesSearchRequest(
                latitude = 11.9332,
                longitude = 79.5241,
                incidentType = IncidentTypePossibleAccident,
            ),
        )

        assertTrue(result is EmergencyResourcesApiResult.Parsed)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        recorded!!
        assertEquals("POST", recorded.method)
        assertEquals(EmergencyResourcesApi.SearchPath, recorded.path)
        assertTrue(
            (recorded.getHeader("Content-Type") ?: "").startsWith("application/json"),
        )

        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(11.9332, body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(79.5241, body.getValue("longitude").jsonPrimitive.double, 0.0)
        assertEquals("possible_accident", body.getValue("incident_type").jsonPrimitive.content)
        assertFalse(body.containsKey("incidentType"))
    }

    @Test
    fun search_joinsTheEndpointWithoutDuplicatingOrDroppingSlashes(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"success","resources":[]}"""),
        )
        // A base URL with no trailing slash is exactly what a contributor is
        // likely to paste into the Gradle property.
        val apiWithoutTrailingSlash = EmergencyResourcesApi(
            server.url("/").toString().trimEnd('/'),
        )

        val result = apiWithoutTrailingSlash.search(
            EmergencyResourcesSearchRequest(11.9332, 79.5241, IncidentTypePossibleAccident),
        )

        assertTrue(result is EmergencyResourcesApiResult.Parsed)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals(EmergencyResourcesApi.SearchPath, recorded!!.path)
    }

    @Test
    fun search_parsesSuccessfulResponseWithAllFields(): Unit = runBlocking {
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
                          "address": "12 River Street",
                          "phone": "+91-413-555-0100",
                          "coordinates": {"latitude": 11.9340, "longitude": 79.5250},
                          "rating": 4.3,
                          "source": "Google Maps",
                          "distance_km": 1.23,
                          "website": "https://example.invalid/ignored"
                        },
                        {
                          "name": "Central Blood Bank",
                          "type": "blood_bank"
                        }
                      ],
                      "searched_at": "2026-10-09T00:00:00"
                    }
                    """.trimIndent(),
                ),
        )

        val result = api.search(
            EmergencyResourcesSearchRequest(11.9332, 79.5241, IncidentTypePossibleAccident),
        )

        assertTrue(result is EmergencyResourcesApiResult.Parsed)
        val response = (result as EmergencyResourcesApiResult.Parsed).response
        assertEquals("success", response.status)
        assertEquals("2026-10-09T00:00:00", response.searchedAt)
        assertEquals(2, response.resources.size)

        val hospital = response.resources[0]
        assertEquals("City General Hospital", hospital.name)
        assertEquals("hospital", hospital.type)
        assertEquals("12 River Street", hospital.address)
        assertEquals("+91-413-555-0100", hospital.phone)
        assertEquals(11.9340, hospital.coordinates?.latitude ?: 0.0, 0.0)
        assertEquals(79.5250, hospital.coordinates?.longitude ?: 0.0, 0.0)
        assertEquals(4.3, hospital.rating ?: 0.0, 0.0)
        assertEquals("Google Maps", hospital.source)
        assertEquals(1.23, hospital.distanceKm ?: 0.0, 0.0)

        val minimal = response.resources[1]
        assertEquals("Central Blood Bank", minimal.name)
        assertEquals("blood_bank", minimal.type)
        assertNull(minimal.address)
        assertNull(minimal.phone)
        assertNull(minimal.coordinates)
        assertNull(minimal.rating)
        assertNull(minimal.source)
        assertNull(minimal.distanceKm)
    }

    @Test
    fun search_acceptsEmptyResourceList(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"no_results","resources":[],"searched_at":"2026-10-09T00:00:00"}"""),
        )

        val result = api.search(
            EmergencyResourcesSearchRequest(11.9332, 79.5241, IncidentTypePossibleAccident),
        )

        assertTrue(result is EmergencyResourcesApiResult.Parsed)
        val response = (result as EmergencyResourcesApiResult.Parsed).response
        assertEquals("no_results", response.status)
        assertTrue(response.resources.isEmpty())
    }

    @Test
    fun search_reportsServerErrorsWithoutResponseBodies(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"detail":"upstream failed"}"""))

        val result = api.search(
            EmergencyResourcesSearchRequest(11.9332, 79.5241, IncidentTypePossibleAccident),
        )

        assertEquals(EmergencyResourcesApiResult.HttpError(502), result)
    }

    @Test
    fun search_reportsUnusablePayloadsAsInvalidResponses(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))

        val result = api.search(
            EmergencyResourcesSearchRequest(11.9332, 79.5241, IncidentTypePossibleAccident),
        )

        assertEquals(EmergencyResourcesApiResult.InvalidResponse, result)
    }

    @Test
    fun search_reportsUnreachableBackendAsUnreachable(): Unit = runBlocking {
        val unreachable = MockWebServer()
        unreachable.start()
        val baseUrl = unreachable.url("/").toString()
        unreachable.shutdown()

        val result = EmergencyResourcesApi(baseUrl).search(
            EmergencyResourcesSearchRequest(11.9332, 79.5241, IncidentTypePossibleAccident),
        )

        assertEquals(EmergencyResourcesApiResult.Unreachable, result)
    }
}
