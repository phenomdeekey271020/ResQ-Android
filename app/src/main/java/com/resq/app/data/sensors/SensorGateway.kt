package com.resq.app.data.sensors

import com.resq.app.model.AccelerometerState
import com.resq.app.model.GyroscopeState
import com.resq.app.model.LocationState

/*
 * The only surface the ViewModel is allowed to see. Everything Android specific
 * (SensorManager, LocationManager, runtime permissions) lives behind it, which
 * keeps the UI free of platform calls and makes the collection lifecycle
 * testable with a fake gateway.
 */

/** Sink for real hardware samples delivered by a [SensorGateway]. */
interface SensorObserver {
    fun onAccelerometer(accelerometerState: AccelerometerState)
    fun onGyroscope(gyroscopeState: GyroscopeState)
    fun onLocation(locationState: LocationState)
}

/**
 * Read-only access to the device motion sensors and location providers.
 *
 * [start] always publishes an opening state for every channel before any sample
 * arrives, so callers can distinguish "not started yet" from "this device has no
 * accelerometer" without polling the hardware.
 */
interface SensorGateway {
    /** True when fine or coarse location has already been granted at runtime. */
    fun hasLocationPermission(): Boolean

    /** True when at least one location provider is switched on device-wide. */
    fun isLocationEnabled(): Boolean

    /** Registers listeners and reports the opening state of every channel. */
    fun start(observer: SensorObserver)

    /**
     * Re-evaluates everything the platform can change behind our back while we
     * are already collecting - a location permission that was just granted or
     * revoked, and the device-wide location toggle.
     *
     * Motion sensors are never re-registered by this: they cannot appear or
     * disappear at runtime. Only the location registration is reconciled, and it
     * is idempotent, so calling it repeatedly never produces duplicate listeners.
     */
    fun refresh()

    /**
     * The explicit user-driven retry, as opposed to the periodic [refresh].
     *
     * The difference matters and is the reason this is a separate method: [refresh]
     * reconciles idempotently, so when a registration already exists it does
     * nothing. That is correct for a background ticker but wrong for a user who
     * has just turned Location back ON and pressed Refresh - the platform has
     * already discarded the old registration, so the retry has to re-request it.
     *
     * Implementations must remain safe to call repeatedly: each call replaces the
     * previous registration rather than stacking a second listener.
     */
    fun refreshForcingRegistration()

    /** Unregisters every listener. Safe to call when collection is not running. */
    fun stop()
}
