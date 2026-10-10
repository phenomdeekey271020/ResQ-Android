package com.resq.app.data.sensors

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.resq.app.model.AccelerometerState
import com.resq.app.model.GyroscopeState
import com.resq.app.model.LocationAvailability
import com.resq.app.model.LocationState
import com.resq.app.model.SensorAvailability
import com.resq.app.model.isStaleFix
import com.resq.app.model.toCacheableFix

/**
 * Platform implementation of [SensorGateway] built on the framework
 * SensorManager and LocationManager - no third-party location SDK.
 *
 * Scope is deliberately narrow: read the accelerometer, gyroscope and a position
 * fix while the caller is listening, and stop cleanly afterwards. There is no
 * accident interpretation here, and this class is the single source of truth for
 * location collection - the foreground service never touches LocationManager.
 *
 * The gateway has no opinion about whether ResQ is protected or merely being
 * previewed in Diagnostics. It keeps the location registration reconciled with
 * the platform's current permission and provider state, and it reports the last
 * known fix honestly: an aged fix is re-published as STALE rather than staying
 * LIVE because no new callback happened to arrive.
 *
 * It also owns the WRITE side of the pre-incident cache: every usable fix is
 * handed to the [LocationFixStore] so a position measured while protection was
 * running survives the incident that follows it. The READ side belongs to
 * [com.resq.app.protection.ProtectionEngine], so there is exactly one judgement of
 * what a cached fix is worth.
 */
class AndroidSensorGateway(
    context: Context,
    private val locationFixStore: LocationFixStore? = null,
) : SensorGateway {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var observer: SensorObserver? = null
    private var locationUpdatesRegistered = false

    /** True once location permission has been seen granted, even if later lost. */
    private var locationPermissionEverGranted = false

    /**
     * Last fix actually received from a provider. Kept so [refresh] can re-publish
     * it with a fresh age instead of the availability it happened to have when it
     * arrived - otherwise a fix received while the app was in the foreground
     * could still be labelled LIVE long after it stopped being current.
     */
    private var lastFix: LocationState? = null

    /**
     * The device-wide location toggle is a protected broadcast, so it cannot be
     * declared in the manifest and has to be registered while collection runs.
     * It is what lets ResQ notice "user turned location off" and "user turned
     * location back on" while the screen is off, instead of only finding out the
     * next time Diagnostics is opened.
     */
    private var providerChangeReceiver: BroadcastReceiver? = null

    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sink = observer ?: return
            val values = event.values
            val x = values?.getOrNull(0) ?: 0f
            val y = values?.getOrNull(1) ?: 0f
            val z = values?.getOrNull(2) ?: 0f
            val timestampMillis = System.currentTimeMillis()
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> sink.onAccelerometer(
                    AccelerometerState(
                        availability = SensorAvailability.READY,
                        x = x,
                        y = y,
                        z = z,
                        timestampMillis = timestampMillis,
                    ),
                )

                Sensor.TYPE_GYROSCOPE -> sink.onGyroscope(
                    GyroscopeState(
                        availability = SensorAvailability.READY,
                        x = x,
                        y = y,
                        z = z,
                        timestampMillis = timestampMillis,
                    ),
                )
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Location.getTime() is wall-clock UTC milliseconds, which is what the
            // staleness rule and every test in the app already assume.
            val fix = location.toLocationState()
                .copy(isCoarse = !hasFineLocationPermission())
            acceptFix(fix)
        }

        override fun onProviderDisabled(provider: String) {
            publishProviderState()
        }

        override fun onProviderEnabled(provider: String) {
            publishProviderState()
        }
    }

    /**
     * The ONE entry point for a fix ResQ has decided to hold on to.
     *
     * Order matters: the fix is written to the pre-incident cache BEFORE it is
     * published, so an incident raised by the very next sensor tick already has a
     * position in hand, and before [lastFix] is updated so the cached entry can
     * never lag the fix the rest of the pipeline believes in.
     *
     * Shared by the live provider callback and by [emitLastKnownFix] on purpose.
     * A seeded platform fix is published to the UI as a position exactly like a
     * live one, so it has to be persisted exactly like a live one too. It used to
     * bypass the cache entirely, which meant a device whose only position came
     * from the platform cache lost it the moment Location was switched off: the
     * seed was never in [locationFixStore], and [lastFix] is dropped on the
     * toggle.
     */
    private fun acceptFix(fix: LocationState) {
        cacheIfUsable(fix)
        lastFix = fix
        publishState(fix)
    }

    override fun hasLocationPermission(): Boolean =
        hasFineLocationPermission() || hasCoarseLocationPermission()

    /**
     * Coarse-only is a real, usable grant - it just yields a less precise fix.
     *
     * ResQ refuses to hide it: the fix is taken, tagged [LocationState.isCoarse]
     * and the UI labels it approximate. Refusing to use it would leave a user who
     * deliberately chose "approximate" with no nearby resources at all, which is
     * worse in an emergency than a clearly labelled ~1 km fix.
     */
    private fun hasCoarseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /** GPS_PROVIDER itself requires the fine grant; coarse cannot substitute. */
    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    override fun isLocationEnabled(): Boolean {
        val manager = locationManager ?: return false
        return usableProviders(manager).isNotEmpty()
    }

    /**
     * Providers ResQ can actually register with, in the order it prefers them.
     *
     * GPS is only offered when the FINE grant is held: registering
     * GPS_PROVIDER with a coarse-only grant throws SecurityException on Android
     * 12+, and an exception that aborts the whole loop would leave a
     * coarse-only user with no provider at all. NETWORK_PROVIDER works with
     * either grant, so the coarse-only case is fully served by it.
     */
    private fun usableProviders(manager: LocationManager): List<String> {
        val enabled = manager.enabledProviders()
        val preferred = if (hasFineLocationPermission()) {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER)
        }
        return preferred.filter { it in enabled }
    }

    /** Providers currently switched on, resolved without the deprecated getter. */
    private fun LocationManager.enabledProviders(): List<String> =
        runCatching { getProviders(true).toList() }.getOrDefault(emptyList())

    override fun start(observer: SensorObserver) {
        stop()
        this.observer = observer
        startMotionSensors(observer)
        startProviderChangeReceiver()
        reconcileLocation()
    }

    override fun stop() {
        sensorManager?.unregisterListener(motionListener)
        stopLocationUpdates()
        stopProviderChangeReceiver()
        lastFix = null
        observer = null
    }

    /**
     * Reconciles the location registration with the platform's current state.
     *
     * This is what turns "detected only when Diagnostics was reopened" into
     * "detected while ResQ is protecting": the caller polls it on a slow cadence
     * and the provider-change broadcast fires it immediately on a toggle, so a
     * revoked permission or a switched-off location is noticed either way.
     *
     * A FORCED variant exists for the explicit user "Refresh location" action.
     * That one re-requests updates even when a registration already exists, which
     * is what lets a user recover after turning the device Location back ON: the
     * platform drops a provider's registration when it is switched off, so a
     * merely-idempotent reconcile would leave ResQ waiting on a listener the
     * system had already discarded.
     */
    override fun refresh() {
        reconcileLocation()
    }

    /**
     * The explicit retry: tears down any existing registration and starts a fresh
     * one, so a device that had Location switched off can recover without the
     * user having to restart protection or reopen ResQ.
     *
     * Repeated calls are still safe - each one replaces the previous registration
     * rather than adding a second listener, so an impatient user tapping Refresh
     * five times ends up with exactly one active callback.
     */
    override fun refreshForcingRegistration() {
        val sink = observer ?: return
        reconcileLocation(force = true)
        // Re-age the fix we already hold. Without this, a fix that arrived while
        // the app was visible would keep reporting READY after it stopped being
        // current, simply because no new callback arrived to overwrite it.
        lastFix?.let { sink.onLocation(it.aged(System.currentTimeMillis())) }
    }

    // ------------------------------------------------------------------
    // Motion
    // ------------------------------------------------------------------

    private fun startMotionSensors(observer: SensorObserver) {
        val manager = sensorManager
        if (manager == null) {
            observer.onAccelerometer(AccelerometerState(SensorAvailability.UNAVAILABLE))
            observer.onGyroscope(GyroscopeState(SensorAvailability.UNAVAILABLE))
            return
        }
        val accelerometer = registerMotionSensor(manager, Sensor.TYPE_ACCELEROMETER)
        observer.onAccelerometer(
            AccelerometerState(if (accelerometer == null) {
                SensorAvailability.UNAVAILABLE
            } else {
                SensorAvailability.WAITING
            }),
        )
        val gyroscope = registerMotionSensor(manager, Sensor.TYPE_GYROSCOPE)
        observer.onGyroscope(
            GyroscopeState(if (gyroscope == null) {
                SensorAvailability.UNAVAILABLE
            } else {
                SensorAvailability.WAITING
            }),
        )
    }

    /** Returns the registered sensor, or null when the device lacks it. */
    private fun registerMotionSensor(manager: SensorManager, type: Int): Sensor? =
        manager.getDefaultSensor(type)?.takeIf { sensor ->
            manager.registerListener(motionListener, sensor, SensorDelay)
        }

    // ------------------------------------------------------------------
    // Location
    // ------------------------------------------------------------------

    /**
     * Brings the location registration in line with the platform and publishes the
     * honest current state.
     *
     * Location is delivered by LocationManager callbacks, never by polling: a fix
     * is published when the platform delivers one, and the availability is
     * published only when it actually changes.
     *
     * [force] is what makes Refresh Location actually work. Without it this
     * method returned early whenever a registration already existed, so a user
     * who had just turned Location back ON in system settings got nothing: the
     * listener was still nominally registered but the platform had dropped it
     * when the provider went away, and no re-request was ever issued. A forced
     * reconciliation re-requests updates from scratch and re-seeds the platform
     * cache, which is exactly what a deliberate retry should do.
     */
    private fun reconcileLocation(force: Boolean = false) {
        val sink = observer ?: return

        if (!hasLocationPermission()) {
            // A permission that was granted and then taken away is a different
            // situation from one that was never asked for, and the UI has to be
            // able to say so.
            val state = if (locationPermissionEverGranted) {
                LocationState(availability = LocationAvailability.PERMISSION_DENIED)
            } else {
                LocationState(availability = LocationAvailability.WAITING_FOR_PERMISSION)
            }
            stopLocationUpdates()
            lastFix = null
            publishState(state)
            return
        }
        locationPermissionEverGranted = true

        val manager = locationManager
        if (manager == null || !isLocationEnabled()) {
            stopLocationUpdates()
            // CHANGED BEHAVIOUR, deliberately.
            //
            // This used to null lastFix and publish a bare PROVIDERS_DISABLED, which
            // threw away a real measurement the moment the user switched Location
            // off. That is exactly the situation an accident produces - Location
            // off, phone damaged, ResQ with nothing at all to offer.
            //
            // The fix is now KEPT and published as STALE, which is what it is: a
            // genuine earlier measurement with a real timestamp, explicitly not the
            // current position. The availability stays PROVIDERS_DISABLED, so the
            // reason shown to the user remains honest - "Location is switched off" -
            // while the position survives as labelled history.
            // Forced STALE rather than re-aged: with providers off there is no live
            // fix at all, so even a two-second-old reading must not keep claiming to
            // be LIVE.
            val historical = lastFix?.copy(availability = LocationAvailability.STALE)
            if (historical != null) {
                publishState(historical)
            } else {
                publishState(LocationState(availability = LocationAvailability.PROVIDERS_DISABLED))
            }
            return
        }

        // Only a forced refresh re-registers. The periodic reconciliation must stay
        // idempotent so the service's 5 second tick cannot churn the registration.
        if (locationUpdatesRegistered && !force) return

        val coarseOnly = !hasFineLocationPermission()

        if (force) stopLocationUpdates()

        // Seeded BEFORE the live request on purpose: the platform cache answers
        // immediately with a real position, while a GNSS fix can take tens of
        // seconds indoors. Even when the cached fix is too old to search around,
        // publishing it lets the UI show an honest, timestamped "last known"
        // instead of an empty screen while the user waits.
        val seeded = emitLastKnownFix(manager)

        val usable = usableProviders(manager)
        var registered = false
        var permissionDenied = false
        for (provider in usable) {
            try {
                manager.requestLocationUpdates(
                    provider,
                    LocationUpdateIntervalMillis,
                    LocationUpdateMinDistanceMeters,
                    locationListener,
                    Looper.getMainLooper(),
                )
            } catch (denied: SecurityException) {
                // One unusable provider must not abandon the others: on some OEM
                // builds a coarse-only grant rejects GPS_PROVIDER, and returning
                // here used to leave such a user with no provider at all.
                Log.w(LogTag, "Provider rejected the location request", denied)
                permissionDenied = true
                continue
            }
            registered = true
        }
        locationUpdatesRegistered = registered

        when {
            !registered && permissionDenied ->
                publishState(LocationState(availability = LocationAvailability.PERMISSION_DENIED))

            !registered ->
                publishState(LocationState(availability = LocationAvailability.UNKNOWN))

            // A coarse grant is announced so the UI never presents an approximate
            // fix with the authority of a precise one.
            coarseOnly && !seeded ->
                publishState(
                    LocationState(availability = LocationAvailability.WAITING_FOR_FIX, isCoarse = true),
                )

            !seeded ->
                publishState(LocationState(availability = LocationAvailability.WAITING_FOR_FIX))
        }
    }

    /**
     * Publishes a location state.
     *
     * Deliberately unguarded: repeating a state is free, because
     * [com.resq.app.protection.ProtectionEngine] already drops an identical
     * snapshot before notifying anyone. Keeping one dedup point - the engine -
     * beats keeping two that can disagree.
     */
    private fun publishState(state: LocationState) {
        observer?.onLocation(state)
    }

    /**
     * Writes [state] to the pre-incident cache when it is a real position.
     *
     * The WRITE side only: validity is decided by
     * [com.resq.app.model.LocationFixCachePolicy.isCacheable] here, and how long
     * the entry stays worth anything is decided by the policy on the read side.
     * A placeholder or malformed fix is simply not written, so it can never
     * overwrite a good measurement.
     */
    private fun cacheIfUsable(state: LocationState) {
        val store = locationFixStore ?: return
        val fix = state.toCacheableFix() ?: return
        runCatching { store.write(fix) }
    }

    /** Reports the current provider situation after a provider enable/disable. */
    private fun publishProviderState() {
        if (!hasLocationPermission()) {
            stopLocationUpdates()
            lastFix = null
            publishState(LocationState(availability = LocationAvailability.PERMISSION_DENIED))
            return
        }
        if (!isLocationEnabled()) {
            stopLocationUpdates()
            lastFix = null
            publishState(LocationState(availability = LocationAvailability.PROVIDERS_DISABLED))
            return
        }
        // Location came back on. FORCED, because the platform has already discarded
        // the old registration along with the provider: an idempotent reconcile
        // would see the stale flag, do nothing, and ResQ would wait forever for a
        // callback that can no longer arrive. This is the automatic half of the
        // "turn Location on, come back, it just works" recovery.
        reconcileLocation(force = true)
        if (!locationUpdatesRegistered) {
            publishState(LocationState(availability = LocationAvailability.WAITING_FOR_FIX))
        }
    }

    // ------------------------------------------------------------------
    // Device-wide location toggle
    // ------------------------------------------------------------------

    private fun startProviderChangeReceiver() {
        if (providerChangeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != LocationManager.PROVIDERS_CHANGED_ACTION) return
                runCatching { publishProviderState() }
            }
        }
        // PROVIDERS_CHANGED_ACTION is a protected broadcast: only the system may
        // send it, and a runtime registration is the only way to hear it.
        val registered = runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
        if (registered) providerChangeReceiver = receiver
    }

    private fun stopProviderChangeReceiver() {
        providerChangeReceiver?.let { receiver ->
            runCatching { appContext.unregisterReceiver(receiver) }
        }
        providerChangeReceiver = null
    }

    /**
     * Seeds the fix from the platform cache.
     *
     * Returns true when a cached fix was actually found. The cache is a genuine
     * platform measurement, so publishing it is honest - the freshness policy
     * decides whether it may be SEARCHED around, while the UI can still show it
     * as a timestamped last-known position. It is handed to [acceptFix] rather
     * than published directly, so a seed the UI displays is also persisted to the
     * pre-incident store.
     */
    private fun emitLastKnownFix(manager: LocationManager): Boolean {
        val coarseOnly = !hasFineLocationPermission()
        val lastKnown = usableProviders(manager)
            .mapNotNull { provider ->
                try {
                    manager.getLastKnownLocation(provider)
                } catch (denied: SecurityException) {
                    // Permission was revoked between the check and this call.
                    null
                } catch (missing: IllegalArgumentException) {
                    // Provider is not known to this device.
                    null
                }
            }
            // Never let a cached fix of (0, 0) - the classic "no position"
            // placeholder - be mistaken for a real measurement of the Gulf of
            // Guinea. It is dropped outright rather than published and aged.
            .filterNot { it.latitude == 0.0 && it.longitude == 0.0 }
            .maxByOrNull { location -> location.time }
            ?: return false
        val fix = lastKnown.toLocationState().copy(isCoarse = coarseOnly)
        acceptFix(fix)
        return true
    }

    private fun stopLocationUpdates() {
        val manager = locationManager
        if (manager != null && locationUpdatesRegistered) {
            runCatching { manager.removeUpdates(locationListener) }
        }
        locationUpdatesRegistered = false
    }

    private fun Location.toLocationState(): LocationState {
        val stale = isStaleFix(time, System.currentTimeMillis())
        return LocationState(
            availability = if (stale) LocationAvailability.STALE else LocationAvailability.READY,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            speedMetersPerSecond = if (hasSpeed()) speed else null,
            provider = provider,
            timestampMillis = time,
        )
    }

    /**
     * Re-applies the staleness rule to an already received fix, so an old fix is
     * downgraded to STALE as time passes instead of staying READY until a new
     * callback happens to replace it.
     */
    private fun LocationState.aged(nowMillis: Long): LocationState {
        val stillFresh = availability == LocationAvailability.READY &&
            !isStaleFix(timestampMillis, nowMillis)
        if (stillFresh) return this
        return copy(availability = LocationAvailability.STALE)
    }

    private companion object {
        const val SensorDelay = SensorManager.SENSOR_DELAY_UI

        /**
         * BATTERY-CONSCIOUS LOCATION CADENCE - CHANGED FROM 1000 ms / 0 m.
         *
         * The prototype asked for a fix every second with no distance filter, which
         * keeps the GNSS chip hot continuously and was by a wide margin the largest
         * battery cost in ResQ. That is not an acceptable trade for a 30 second
         * freshness window: the window only needs a fix every 30 s, so asking for
         * one 30x more often buys nothing an emergency needs and costs the user a
         * meaningful slice of a day's charge.
         *
         * The new cadence is chosen against the freshness window, not by habit:
         *
         *  - 5 s interval: inside the 30 s staleness window with room to spare, so
         *    a fix is never missed for longer than the policy already tolerates;
         *  - 10 m minimum distance: fixes that do not move the phone by at least
         *    10 m are not useful for finding the nearest hospital, and a stationary
         *    phone therefore stops generating callbacks entirely. This is the part
         *    that matters most in practice - the expensive case is a phone sitting
         *    still, which is exactly the case a stationary user is in.
         *
         * NOT CLAIMED: this is still a continuous request while protection is
         * active, and it is still the app's biggest power draw. A shipping design
         * would go further - passive updates plus significant-change monitoring
         * while stationary, and a bounded duty cycle - but that trades away the
         * guarantee that a fix is already in hand when the accident happens, which
         * is the whole point of maintaining the cache. The honest summary is that
         * ResQ spends battery to be ready, and the user is told protection is
         * active for as long as it is spending it.
         */
        const val LocationUpdateIntervalMillis = 5_000L
        const val LocationUpdateMinDistanceMeters = 10f

        const val LogTag = "ResQLocation"
    }
}
