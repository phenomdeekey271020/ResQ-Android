package com.resq.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

/**
 * Locks in the backend address baked into the debug APK.
 *
 * The live failure this guards against: the APK installed on the phone embedded
 * `http://10.0.2.2:8010` - the emulator's alias for the developer machine, which
 * no physical device can route to - because the build had no backend URL
 * configured and the Gradle script fell back to it silently. Every resource
 * search then failed as NETWORK, so the emergency screen showed "Could not reach
 * the resource service" and the Directions button stayed disabled.
 *
 * The packaged default is now the deployed Render HTTPS backend. These
 * assertions run against the debug variant's generated [BuildConfig], which is
 * the variant side-loaded onto the phone.
 */
class BackendConfigurationTest {

    private companion object {
        const val ProductionBackendBaseUrl = "https://resq-backend-s55p.onrender.com/"
    }

    /**
     * The regression test. Without a deliberate local override, the packaged
     * base must be exactly the production deployment - so no silent fallback
     * (the old emulator loopback, a stale LAN IP) can ever reach an installed
     * APK again.
     *
     * A deliberate local-dev override, e.g.
     * `-Presq.backend.url=http://10.0.2.2:8010` for an emulator-only run,
     * skips this test; the well-formedness test below still applies.
     */
    @Test
    fun debugBuildUsesTheProductionBackendByDefault() {
        assumeFalse(
            "Local development override is configured; production default not in use.",
            BuildConfig.BACKEND_URL_IS_LOCAL_DEV_OVERRIDE,
        )
        assertEquals(
            "BuildConfig.BACKEND_BASE_URL is not the production Render backend. " +
                "Set resq.backend.url in gradle.properties to " +
                "https://resq-backend-s55p.onrender.com and rebuild.",
            ProductionBackendBaseUrl,
            BuildConfig.BACKEND_BASE_URL,
        )
    }

    /**
     * The packaged base must be a single, well-formed absolute URL: OkHttp is
     * handed `base.trimEnd('/') + "/api/emergency-resources/search"`, so a base
     * without a scheme, or with a placeholder host, would produce a request that
     * can never succeed.
     */
    @Test
    fun debugBackendBaseUrlIsAWellFormedAbsoluteUrl() {
        val url = BuildConfig.BACKEND_BASE_URL
        assertTrue(
            "Not an absolute http(s) backend URL: '$url'",
            url.startsWith("http://") || url.startsWith("https://"),
        )
        assertTrue(
            "Expected exactly one trailing slash: '$url'",
            url.endsWith("/") && !url.endsWith("//"),
        )
        assertTrue(
            "The base URL still contains an unsubstituted placeholder: '$url'",
            !url.contains("YOUR_PC_LAN_IP") && !url.contains("<"),
        )
    }
}
