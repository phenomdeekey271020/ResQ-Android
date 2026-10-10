package com.resq.app.data.sensors

import android.content.Context
import com.resq.app.model.LastKnownLocationFix
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * WHERE THE PRE-INCIDENT LOCATION CACHE LIVES.
 *
 * Follows the project's existing convention exactly - a private SharedPreferences
 * file, like EmergencyContactsStore, AppearancePreferences and
 * ProtectionPreferences. No database and no new library were added for one row of
 * data.
 *
 * PRIVACY (deliberate, and the reason the design is this simple):
 *   - the file lives in the app's private storage and is never exported;
 *   - NO coordinate, accuracy or provider value is logged, at any level. The only
 *     thing ever written to Logcat is whether an entry existed;
 *   - the cache holds exactly ONE fix. A history of where the user has been is a
 *     far more sensitive artefact than "the last place this phone was", and an
 *     emergency app has no need for the former.
 *
 * The entry is written opportunistically on every good fix and re-judged on every
 * read by LocationFixCachePolicy, so a stale entry can never be presented as a
 * current position - and is removed once it stops being worth keeping at all.
 */
interface LocationFixStore {

    /** The stored fix, or null when there is none (or it is unreadable). */
    fun read(): LastKnownLocationFix?

    /**
     * Stores [fix], or clears the cache when it is null.
     *
     * Implementations must be safe to call from the main thread at fix cadence:
     * SharedPreferences.apply() is asynchronous and is the implementation used.
     */
    fun write(fix: LastKnownLocationFix?)

    /** Removes the entry. Called when it stops being worth showing at all. */
    fun clear()
}

/** SharedPreferences-backed cache used by the running app. */
class SharedPreferencesLocationFixStore(context: Context) : LocationFixStore {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): LastKnownLocationFix? {
        val encoded = preferences.getString(KEY_FIX, null) ?: return null
        // A corrupt or partially written entry degrades to "no cache" rather than
        // crashing the emergency flow, and the cache is cleared so it cannot keep
        // failing. No coordinate value is included in the message.
        val decoded = runCatching { json.decodeFromString<StoredFix>(encoded) }.getOrNull()
        if (decoded == null) {
            preferences.edit().remove(KEY_FIX).apply()
            return null
        }
        return decoded.toFix()
    }

    override fun write(fix: LastKnownLocationFix?) {
        if (fix == null) {
            clear()
            return
        }
        preferences.edit()
            .putString(KEY_FIX, json.encodeToString(StoredFix.from(fix)))
            .apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_FIX).apply()
    }

    /**
     * Serialised form, private to this file so the storage shape cannot leak into
     * the domain model and change the policy that judges it.
     */
    @Serializable
    private data class StoredFix(
        val latitude: Double,
        val longitude: Double,
        val timestampMillis: Long,
        val accuracyMeters: Float? = null,
        val coarse: Boolean = false,
        val provider: String? = null,
    ) {
        fun toFix(): LastKnownLocationFix = LastKnownLocationFix(
            latitude = latitude,
            longitude = longitude,
            timestampMillis = timestampMillis,
            accuracyMeters = accuracyMeters,
            isCoarse = coarse,
            provider = provider,
        )

        companion object {
            fun from(fix: LastKnownLocationFix): StoredFix = StoredFix(
                latitude = fix.latitude,
                longitude = fix.longitude,
                timestampMillis = fix.timestampMillis,
                accuracyMeters = fix.accuracyMeters,
                coarse = fix.isCoarse,
                provider = fix.provider,
            )
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "resq_location_cache"
        const val KEY_FIX = "last_known_fix"

        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * In-memory cache, and the one every automated test uses.
 *
 * It exists so no test can ever touch a real file or a real device, and so the
 * caching rules can be tested against a clock the test controls.
 */
class InMemoryLocationFixStore(initial: LastKnownLocationFix? = null) : LocationFixStore {

    private var stored: LastKnownLocationFix? = initial

    var writeCount: Int = 0
        private set

    override fun read(): LastKnownLocationFix? = stored

    override fun write(fix: LastKnownLocationFix?) {
        writeCount++
        stored = fix
    }

    override fun clear() {
        stored = null
    }
}