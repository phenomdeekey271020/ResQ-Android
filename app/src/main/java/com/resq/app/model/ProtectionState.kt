package com.resq.app.model

/**
 * Protection lifecycle. Monitoring is either engaged or deliberately paused by
 * the user. A typed state instead of scattered `isPaused` flags.
 */
enum class ProtectionState {
    ACTIVE,
    PAUSED,
}
