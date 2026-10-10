package com.resq.app.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/*
 * Typed client for POST /api/emergency-resources/search on the local ResQ
 * FastAPI backend.
 *
 * The SerpApi key lives only on the backend - nothing in this file (or anywhere
 * in the app) ever holds or transmits it. A request carries exactly three
 * fields: the emergency coordinates and the incident type. Requests and
 * responses are logged nowhere: there is no logging interceptor, and failures
 * surface as coarse categories instead of bodies or coordinates.
 */

/** Request body; the field names mirror the backend's snake_case schema. */
@Serializable
data class EmergencyResourcesSearchRequest(
    val latitude: Double,
    val longitude: Double,
    @SerialName("incident_type")
    val incidentType: String,
)

@Serializable
data class EmergencyResourceCoordinatesDto(
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * One resource exactly as the backend returns it. Optional fields carry
 * defaults so a missing key parses to null instead of failing the whole
 * response; unknown keys are ignored for forward compatibility.
 */
@Serializable
data class EmergencyResourceDto(
    val name: String? = null,
    val type: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val coordinates: EmergencyResourceCoordinatesDto? = null,
    val rating: Double? = null,
    val source: String? = null,
    @SerialName("distance_km")
    val distanceKm: Double? = null,
)

@Serializable
data class EmergencyResourcesSearchResponse(
    val status: String? = null,
    val resources: List<EmergencyResourceDto> = emptyList(),
    @SerialName("searched_at")
    val searchedAt: String? = null,
)

/** Outcome of one HTTP call, before domain mapping. Never carries a body. */
sealed interface EmergencyResourcesApiResult {

    data class Parsed(val response: EmergencyResourcesSearchResponse) : EmergencyResourcesApiResult

    data class HttpError(val code: Int) : EmergencyResourcesApiResult

    data object InvalidResponse : EmergencyResourcesApiResult

    data object Unreachable : EmergencyResourcesApiResult
}

/**
 * Minimal typed client: OkHttp for the wire, kotlinx.serialization for the
 * JSON. Injectable base URL and [OkHttpClient] so unit tests can point it at a
 * local MockWebServer without touching the real backend.
 */
class EmergencyResourcesApi(
    private val baseUrl: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val json: Json = defaultJson(),
) {

    /** Executes the search. Resolves to a typed outcome; never throws. */
    suspend fun search(
        request: EmergencyResourcesSearchRequest,
    ): EmergencyResourcesApiResult {
        // Joined once, here, so a missing or duplicated slash can never produce a
        // malformed path: the configured base is trimmed and the endpoint path is
        // appended verbatim.
        val httpRequest = Request.Builder()
            .url(baseUrl.trimEnd('/') + SearchPath)
            .post(encode(request).toRequestBody(JsonMediaType))
            .header("Accept", "application/json")
            .build()
        return withContext(Dispatchers.IO) {
            val call = httpClient.newCall(httpRequest)
            // If the caller's coroutine is cancelled (an explicit stand-down or a
            // superseded search) the obsolete socket is closed immediately instead
            // of being left to run into its timeout.
            val cancellationHandle = coroutineContext.job.invokeOnCompletion { call.cancel() }
            try {
                execute(call)
            } finally {
                cancellationHandle.dispose()
            }
        }
    }

    private fun encode(request: EmergencyResourcesSearchRequest): String =
        json.encodeToString(EmergencyResourcesSearchRequest.serializer(), request)

    private fun execute(call: Call): EmergencyResourcesApiResult = try {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                return@use EmergencyResourcesApiResult.HttpError(response.code)
            }
            val body = response.body?.string()
                ?: return@use EmergencyResourcesApiResult.InvalidResponse
            try {
                EmergencyResourcesApiResult.Parsed(
                    json.decodeFromString(EmergencyResourcesSearchResponse.serializer(), body),
                )
            } catch (_: SerializationException) {
                EmergencyResourcesApiResult.InvalidResponse
            } catch (_: IllegalArgumentException) {
                EmergencyResourcesApiResult.InvalidResponse
            }
        }
    } catch (_: IOException) {
        EmergencyResourcesApiResult.Unreachable
    }

    companion object {
        const val SearchPath = "/api/emergency-resources/search"

        private val JsonMediaType = "application/json; charset=utf-8".toMediaType()

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        /**
         * Every phase is bounded, and so is the call as a whole.
         *
         *  - connect 5 s: an unreachable backend (wrong LAN address, backend not
         *    listening) fails fast instead of leaving the UI spinning.
         *  - read 25 s: the backend runs up to three sequential upstream SerpApi
         *    searches before answering, so the older 20 s budget could expire on
         *    a slow-but-working backend and be misreported as unreachable.
         *  - call 30 s: a single hard ceiling over DNS + connect + write + read
         *    combined, so no configuration of the phases above can leave the
         *    search in flight forever.
         *
         * [retryOnConnectionFailure] is off on purpose: the emergency flow retries
         * only when the user explicitly asks. OkHttp must never re-send this POST
         * on its own, because a silent retry would duplicate upstream work the
         * user did not request.
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
