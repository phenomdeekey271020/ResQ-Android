package com.resq.app

import com.resq.app.ui.screens.SettingsBackAction
import com.resq.app.ui.screens.SettingsBackStack
import com.resq.app.ui.screens.SettingsDestination
import com.resq.app.ui.screens.settingsBackAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the reported back-gesture bug.
 *
 *     Settings -> Permissions -> Android back gesture -> Home   (the bug)
 *     Settings -> Permissions -> Android back gesture -> Settings (expected)
 *
 * The cause was that the settings submenu back stack existed only as composable
 * locals, reachable by the in-app arrow but invisible to the single BackHandler
 * in ResQApp. ResQApp could see only `state.screen == SETTINGS`, so it closed
 * Settings. These tests pin the routing rule and the stack behaviour that makes
 * it correct.
 */
class SettingsNavigationRegressionTest {

    // ---- the reported bug ------------------------------------------------

    @Test
    fun `back from a settings submenu pops to the main menu instead of closing settings`() {
        val stack = SettingsBackStack()
        stack.push(SettingsDestination.Permissions)

        val action = settingsBackAction(stack.current, isActive = true)
        assertEquals(SettingsBackAction.POP_SUBMENU, action)

        assertEquals(SettingsDestination.Main, stack.popOrMain())
    }

    @Test
    fun `system back and the in-app arrow resolve to the same destination`() {
        // The two paths are the whole point: the arrow called pop() directly and
        // always worked, which is exactly why the gesture looked broken.
        val viaArrow = SettingsBackStack().apply { push(SettingsDestination.Permissions) }
        viaArrow.popOrMain()

        val viaSystemBack = SettingsBackStack().apply { push(SettingsDestination.Permissions) }
        if (settingsBackAction(viaSystemBack.current, true) == SettingsBackAction.POP_SUBMENU) {
            viaSystemBack.popOrMain()
        }

        assertEquals(viaArrow.current, viaSystemBack.current)
        assertEquals(SettingsDestination.Main, viaSystemBack.current)
    }

    @Test
    fun `every settings submenu pops rather than closing settings`() {
        val destinations = listOf(
            SettingsDestination.Appearance,
            SettingsDestination.Protection,
            SettingsDestination.Emergency,
            SettingsDestination.AlertCountdown,
            SettingsDestination.EmergencyContacts,
            SettingsDestination.Permissions,
            SettingsDestination.Developer,
        )
        for (destination in destinations) {
            val stack = SettingsBackStack()
            stack.push(destination)
            assertEquals(
                "$destination must pop, not close Settings",
                SettingsBackAction.POP_SUBMENU,
                settingsBackAction(stack.current, isActive = true),
            )
        }
    }

    // ---- the main menu is the only place Back leaves Settings -------------

    @Test
    fun `back on the settings main menu closes settings`() {
        val stack = SettingsBackStack()
        assertEquals(SettingsDestination.Main, stack.current)
        assertEquals(SettingsBackAction.CLOSE_SETTINGS, settingsBackAction(stack.current, true))
    }

    @Test
    fun `back after fully unwinding the stack closes settings`() {
        val stack = SettingsBackStack()
        stack.push(SettingsDestination.Emergency)
        stack.push(SettingsDestination.AlertCountdown)

        assertEquals(SettingsDestination.Emergency, stack.popOrMain())
        assertEquals(SettingsDestination.Main, stack.popOrMain())

        // Having reached Main, Back must hand control back to the app-level
        // handler rather than trying to pop an empty stack.
        assertEquals(SettingsBackAction.CLOSE_SETTINGS, settingsBackAction(stack.current, true))
        assertFalse(stack.canPop)
    }

    // ---- nested depth ------------------------------------------------------

    @Test
    fun `three levels deep unwind one level at a time in order`() {
        val stack = SettingsBackStack()
        stack.push(SettingsDestination.Emergency)
        stack.push(SettingsDestination.AlertCountdown)

        assertEquals(2, stack.depth)
        assertEquals(SettingsDestination.Emergency, stack.popOrMain())
        assertEquals(SettingsDestination.Main, stack.popOrMain())
        assertEquals(0, stack.depth)
    }

    @Test
    fun `the emergency contacts deep link still pops to the main menu`() {
        // First-launch contact setup opens Settings straight on this destination.
        val stack = SettingsBackStack(SettingsDestination.EmergencyContacts)
        assertEquals(SettingsDestination.EmergencyContacts, stack.current)
        assertEquals(
            SettingsBackAction.POP_SUBMENU,
            settingsBackAction(stack.current, isActive = true),
        )
        assertEquals(SettingsDestination.Main, stack.popOrMain())
    }

    @Test
    fun `pop never walks past the main menu however many times it is called`() {
        val stack = SettingsBackStack()
        repeat(5) { stack.popOrMain() }
        assertEquals(SettingsDestination.Main, stack.current)
        assertEquals(SettingsBackAction.CLOSE_SETTINGS, settingsBackAction(stack.current, true))
    }

    // ---- repeated navigation must not create duplicate levels --------------

    @Test
    fun `pushing the destination already showing does not stack a duplicate`() {
        val stack = SettingsBackStack()
        stack.push(SettingsDestination.Permissions)
        stack.push(SettingsDestination.Permissions)
        stack.push(SettingsDestination.Permissions)

        assertEquals(1, stack.depth)
        assertEquals(SettingsDestination.Main, stack.popOrMain())
    }

    @Test
    fun `re-entering a submenu repeatedly never needs more than one back press`() {
        val stack = SettingsBackStack()
        repeat(4) {
            stack.push(SettingsDestination.Permissions)
            stack.popOrMain()
        }
        assertEquals(0, stack.depth)
        assertEquals(SettingsDestination.Main, stack.current)
    }

    @Test
    fun `sibling submenus each stack and unwind correctly`() {
        val stack = SettingsBackStack()
        stack.push(SettingsDestination.Emergency)
        stack.push(SettingsDestination.EmergencyContacts)
        assertEquals(listOf(SettingsDestination.Main, SettingsDestination.Emergency), stack.path())

        assertEquals(SettingsDestination.Emergency, stack.popOrMain())
        assertEquals(SettingsDestination.Main, stack.popOrMain())
    }

    // ---- the Crossfade gate ------------------------------------------------

    @Test
    fun `a settings submenu left composed by the crossfade defers instead of stealing back`() {
        // Crossfade keeps the outgoing screen composed while it animates away. A
        // submenu left composed must not consume a press meant for the screen
        // that is replacing it.
        val stack = SettingsBackStack()
        stack.push(SettingsDestination.Permissions)

        assertEquals(
            SettingsBackAction.DEFER,
            settingsBackAction(stack.current, isActive = false),
        )
    }

    @Test
    fun `an inactive settings main menu defers too`() {
        assertEquals(
            SettingsBackAction.DEFER,
            settingsBackAction(SettingsDestination.Main, isActive = false),
        )
    }

    // ---- stack integrity ---------------------------------------------------

    @Test
    fun `the stack records the destination being left, not the one entered`() {
        val stack = SettingsBackStack()
        stack.push(SettingsDestination.Emergency)
        stack.push(SettingsDestination.EmergencyContacts)

        // The bug shape this guards: recording the destination just entered
        // would make Back a no-op on the first press from a two-deep path.
        assertEquals(SettingsDestination.Main, stack.path().first())
        assertEquals(SettingsDestination.Emergency, stack.path().last())
    }

    @Test
    fun `canPop tracks whether a submenu is open`() {
        val stack = SettingsBackStack()
        assertFalse(stack.canPop)

        stack.push(SettingsDestination.Permissions)
        assertTrue(stack.canPop)

        stack.popOrMain()
        assertFalse(stack.canPop)
    }
}