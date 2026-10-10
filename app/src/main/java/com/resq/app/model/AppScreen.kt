package com.resq.app.model

/**
 * The four ResQ destinations. Navigation is a property of [ResQUiState], so a
 * state transition and the screen it reveals can never drift apart.
 */
enum class AppScreen {
    HOME,
    INCIDENT,
    EMERGENCY,
    DIAGNOSTICS,
    SETTINGS,
}
