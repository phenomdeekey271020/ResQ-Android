import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Production FastAPI backend (Render). Built-in fallback so a build with no
// resq.backend.url configured still talks to production - never to an address
// only an emulator or one LAN can reach.
val ProductionBackendUrl = "https://resq-backend-s55p.onrender.com"

/*
 * RELEASE SIGNING CREDENTIALS - READ FROM OUTSIDE THIS REPOSITORY.
 *
 * The release keystore and its passwords are NEVER committed and never appear in
 * this file. They are resolved, in order, from:
 *
 *   1. environment variables          RESQ_RELEASE_STORE_FILE
 *                                     RESQ_RELEASE_STORE_PASSWORD
 *                                     RESQ_RELEASE_KEY_ALIAS
 *                                     RESQ_RELEASE_KEY_PASSWORD
 *   2. a private keystore.properties at the PROJECT ROOT, which .gitignore
 *      excludes. Keys: storeFile, storePassword, keyAlias, keyPassword
 *
 * storeFile may be absolute or relative to the project root.
 *
 * If neither source provides every value, a release build FAILS with an explicit
 * message instead of falling back to the debug key. Falling back silently would
 * produce an APK signed with the public, shared Android debug certificate - an
 * artifact that is not merely wrong but actively dangerous to publish, because
 * anyone can forge a "same signer" update for it. Debug, test and lint builds
 * are unaffected and need none of this.
 */
/*
 * The properties file may live entirely outside the repository. RESQ_KEYSTORE_PROPERTIES
 * takes an absolute path and wins when set; otherwise keystore.properties at the
 * project root is used. Both are outside version control either way.
 */
val KeystorePropertiesFile = System.getenv("RESQ_KEYSTORE_PROPERTIES")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { file(it) }
    ?: rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (KeystorePropertiesFile.exists()) {
        KeystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(environmentName: String, propertyName: String): String? =
    System.getenv(environmentName)?.trim()?.takeIf { it.isNotEmpty() }
        ?: keystoreProperties.getProperty(propertyName)?.trim()?.takeIf { it.isNotEmpty() }

val releaseStoreFilePath = signingValue("RESQ_RELEASE_STORE_FILE", "storeFile")
val releaseStorePassword = signingValue("RESQ_RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("RESQ_RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("RESQ_RELEASE_KEY_PASSWORD", "keyPassword")

val missingSigningValues = buildList {
    if (releaseStoreFilePath == null) add("storeFile")
    if (releaseStorePassword == null) add("storePassword")
    if (releaseKeyAlias == null) add("keyAlias")
    if (releaseKeyPassword == null) add("keyPassword")
}
val hasReleaseSigning = missingSigningValues.isEmpty()

/*
 * Fail fast, and only for a build that actually needs the release key.
 *
 * Checked here rather than inside the release build type because Gradle
 * configures every build type alike: a check that threw unconditionally would
 * break assembleDebug, testDebugUnitTest and lintDebug for no reason.
 *
 * Matched on the task PREFIX as well as the build type, because "Release" alone
 * is far too broad: lintRelease, lintVitalRelease, compileReleaseKotlin and
 * testReleaseUnitTest all mention the release variant and none of them produces
 * a signed artifact. Requiring an artifact-producing prefix means the guard
 * fires exactly for assembleRelease, bundleRelease, packageRelease,
 * installRelease and publish tasks - and lets every analysis task run without
 * credentials, which is what keeps them usable in CI and on a fresh checkout.
 */
val releaseArtifactTaskPrefixes = listOf("assemble", "bundle", "package", "install", "publish")
val requestedReleaseBuild = gradle.startParameter.taskNames.any { requested ->
    val taskName = requested.substringAfterLast(':')
    requested.contains("release", ignoreCase = true) &&
        releaseArtifactTaskPrefixes.any { taskName.startsWith(it, ignoreCase = true) }
}
if (requestedReleaseBuild && !hasReleaseSigning) {
    throw GradleException(
        "Release signing credentials are missing (${missingSigningValues.joinToString(", ")}). " +
            "Set the RESQ_RELEASE_* environment variables, or create a private " +
            "keystore.properties at the project root (excluded by .gitignore). " +
            "Refusing to build a release APK without the release key: a release " +
            "build must never silently fall back to the debug certificate.",
    )
}

android {
    namespace = "com.resq.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.resq.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Backend base URL for emergency resource discovery. Lookup order - an
        // explicit override always beats the committed default, whichever
        // spelling it uses:
        //   1. resq_backend_url   (-Presq_backend_url,
        //                         ORG_GRADLE_PROJECT_resq_backend_url)
        //   2. RESQ_BACKEND_URL   (environment)
        //   3. resq.backend.url   (-Presq.backend.url, and the gradle.properties
        //                         default)
        //   4. the production backend, https://resq-backend-s55p.onrender.com
        //
        // The underscore spellings are checked FIRST because a POSIX environment
        // variable cannot contain a dot: ORG_GRADLE_PROJECT_resq_backend_url sets
        // the project property `resq_backend_url`, not `resq.backend.url`. Asking
        // for `resq.backend.url` first would let the committed default in
        // gradle.properties shadow that documented environment override, and the
        // override would be ignored without a word.
        //
        // The production address lives in gradle.properties rather than only
        // in an environment variable because an exported ORG_GRADLE_PROJECT_*
        // variable exists only in the shell that exported it: Android Studio's
        // Run/Build (and any fresh terminal) then built with no URL at all and fell
        // back to 10.0.2.2 - the emulator's alias for the developer machine, which
        // a phone cannot route to. Every build path reads gradle.properties.
        //
        // Only http/https URLs belong here. The production backend is https, so
        // no cleartext allowance is needed; a local http backend works only with
        // an explicit debug cleartext allowance.
        val explicitlyConfiguredBackendUrl: String? =
            sequenceOf(
                project.findProperty("resq_backend_url") as? String,
                System.getenv("RESQ_BACKEND_URL"),
                project.findProperty("resq.backend.url") as? String,
            )
                .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                .firstOrNull()

        // "Nothing was configured" must never silently mean an address a phone
        // cannot reach: the fallback is the production backend, and the flag
        // below records whether the effective URL is a deliberate local-dev
        // override rather than that production default. BackendConfigurationTest
        // asserts the production default stays intact.
        val normalizedBackendUrl: String = (explicitlyConfiguredBackendUrl ?: ProductionBackendUrl)
            .let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
            .trimEnd('/')
        val isLocalDevOverride = normalizedBackendUrl != ProductionBackendUrl
        logger.lifecycle(
            "ResQ backend base URL: $normalizedBackendUrl/ (" +
                if (isLocalDevOverride) {
                    "LOCAL DEV OVERRIDE - not the production backend"
                } else {
                    "production default"
                } +
                ")",
        )
        buildConfigField(
            "String",
            "BACKEND_BASE_URL",
            "\"${normalizedBackendUrl.replace("\\", "\\\\").replace("\"", "\\\"")}/\"",
        )
        buildConfigField(
            "boolean",
            "BACKEND_URL_IS_LOCAL_DEV_OVERRIDE",
            isLocalDevOverride.toString(),
        )
    }

    signingConfigs {
        /*
         * Created only when every credential resolved. When it is absent the
         * release build type has NO signing config at all, so AGP produces an
         * explicitly unsigned APK rather than quietly reusing the debug key.
         * The configuration-time check above turns that case into a clear error
         * before packaging is ever reached.
         */
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // minSdk is 24, so the API 18-era v1 (JAR) scheme is never
                // needed. v2 and v3 are both requested: v2 is what every
                // Android 7+ device verifies, and v3 additionally carries the
                // signing-key-rotation proof, so a future key change stays a
                // normal upgrade instead of forcing a reinstall. v4 exists only
                // to speed up adb incremental installs and is not shipped.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
                // The key location is useful in a build log; the passwords and
                // any key material never are, so only the alias and path are
                // reported.
                logger.lifecycle(
                    "ResQ release signing: alias '$releaseKeyAlias' from " +
                        "'${releaseStoreFilePath}'",
                )
            }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // The ViewModel logs accident flow transitions with android.util.Log.
            // Unit tests only need those calls to be harmless, not to return values.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}