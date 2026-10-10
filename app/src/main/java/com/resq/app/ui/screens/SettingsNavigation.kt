package com.resq.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Back-navigation rules for the Settings experience.
 *
 * These are plain types with no Composable in them on purpose. The navigation
 * bug this file exists to pin was caused by the settings back stack living only
 * in composable local variables, where nothing could assert anything about it:
 * the system back gesture was handled by an OUTER handler that could see only
 * "the screen is SETTINGS", so it closed Settings and jumped to Home - while
 * the in-app arrow called the local `pop()` and behaved correctly. Moving the
 * rule here means it can be unit-tested without a device, which is the only
 * reason this regression could be pinned in the first place.
 */

/**
 * The submenu back stack for Settings.
 *
 * A stack, not a single "previous destination" pointer: a single pointer makes
 * the second Back press on a three-level path (Main -> Emergency -> Alert
 * countdown) a no-op and strands the user inside the submenu.
 *
 * State is Compose snapshot state so the composable recomposes on a change, and
 * so this stays directly assertable from a plain JVM unit test.
 */
class SettingsBackStack(initial: SettingsDestination = SettingsDestination.Main) {

    private var entries: List<SettingsDestination> by mutableStateOf(emptyList())
        private set

    /** The settings submenu currently on screen. */
    var current: SettingsDestination by mutableStateOf(initial)
        private set

    /** How many submenu levels are stacked above the main menu. */
    val depth: Int get() = entries.size

    /** True when a submenu is open, i.e. Back should pop rather than close. */
    val canPop: Boolean get() = entries.isNotEmpty()

    /**
     * Opens [destination], recording where to return to.
     *
     * Re-entering the destination already showing is a no-op, so a repeated tap
     * on the same Settings row cannot stack a duplicate level the user would
     * then have to press Back through twice.
     */
    fun push(destination: SettingsDestination) {
        if (current == destination) return
        entries = entries + current
        current = destination
    }

    /**
     * Returns to the previous submenu, or to the main menu when nothing is
     * stacked. Never pops past Main.
     */
    fun popOrMain(): SettingsDestination {
        current = entries.lastOrNull() ?: SettingsDestination.Main
        entries = entries.dropLast(1)
        return current
    }

    /** Destinations on the stack, shallowest first. Test diagnostic only. */
    fun path(): List<SettingsDestination> = entries
}

/**
 * What a single system Back press must do from a settings destination.
 */
enum class SettingsBackAction {
    /** A submenu is open: pop one level and stay inside Settings. */
    POP_SUBMENU,

    /** The Settings main menu is showing: leave Settings and return to Home. */
    CLOSE_SETTINGS,

    /**
     * Settings is not the visible screen - Compose keeps the outgoing content
     * composed for the length of a Crossfade. Defer to whichever screen is
     * actually showing rather than swallowing the press.
     */
    DEFER,
}

/**
 * The single rule that maps a Back press to an action.
 *
 * [isActive] exists because "a submenu is open" is not by itself proof that
 * Settings is the screen the user is looking at. Without it, a settings submenu
 * left composed mid-animation could consume a Back press belonging to another
 * screen.
 *
 * [destination] must be the CURRENT destination, not the main menu: that is
 * the value whose being "not Main" is the whole signal that a submenu is open.
 */
fun settingsBackAction(
    destination: SettingsDestination,
    isActive: Boolean,
): SettingsBackAction = when {
    !isActive -> SettingsBackAction.DEFER
    destination == SettingsDestination.Main -> SettingsBackAction.CLOSE_SETTINGS
    else -> SettingsBackAction.POP_SUBMENU
}