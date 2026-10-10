package com.resq.app

import com.resq.app.ui.components.MaxSideBySideRowValueLength
import com.resq.app.ui.components.shouldStackRowValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [shouldStackRowValue] rule against the real strings the app passes to
 * [com.resq.app.ui.components.GlassRow].
 *
 * The layout defect this encodes: on the narrowest supported phone the label and
 * value share roughly 251dp, so a long value beside a long label forces BOTH to
 * wrap. That is what produced "Motion sensors" over two lines next to "Waiting
 * for sensor data" over two lines, with descriptions squeezed to five lines.
 */
class GlassRowValueLayoutTest {

    @Test
    fun `an empty value stays beside the label and costs it nothing`() {
        assertFalse(shouldStackRowValue(""))
    }

    @Test
    fun `short status values stay beside their label`() {
        val beside = listOf(
            "Off",
            "On",
            "System",
            "Light",
            "Dark",
            "Not set",
            "1 contact",
            "3 contacts",
            "Granted",
            "Not granted",
            "Unknown",
            "Primary",
            "12 samples",
            "Precise",
        )
        for (value in beside) {
            assertFalse("'$value' should sit beside the label", shouldStackRowValue(value))
        }
    }

    @Test
    fun `long explanatory values move under the label`() {
        val stacked = listOf(
            "Waiting for permission",
            "Waiting for sensor data",
            "Place a real call",
            "Nothing — permission missing",
            "Nothing — you have not agreed",
            "Nothing — no contact saved",
            "Weighted heuristic",
            "Per-level (10/5)",
        )
        for (value in stacked) {
            assertTrue("'$value' should stack under the label", shouldStackRowValue(value))
        }
    }

    @Test
    fun `the boundary is exactly the documented length`() {
        val boundary = "x".repeat(MaxSideBySideRowValueLength)
        assertFalse("a value of exactly the limit still fits beside", shouldStackRowValue(boundary))
        assertTrue(
            "one character past the limit must stack",
            shouldStackRowValue("x".repeat(MaxSideBySideRowValueLength + 1)),
        )
    }

    @Test
    fun `the rule keys on length not on words or spacing`() {
        // A long unbroken token and a short multi-word value take opposite sides
        // of the rule; the layout must not depend on either.
        assertTrue(shouldStackRowValue("Waitingforsensorpermission"))
        assertFalse(shouldStackRowValue("Off"))
    }

    @Test
    fun `no value currently used in the app would overflow the row`() {
        // Every real status string, so a future copy change cannot silently
        // reintroduce the two-line-label / two-line-value layout.
        val realValues = listOf(
            "Off", "On", "System", "Not set", "1 contact", "2 contacts",
            "Granted", "Not granted", "Primary", "Unknown", "Precise",
            "Approximate", "Standby", "12 samples", "1.0 (prototype)",
            "Waiting for permission", "Waiting for sensor data",
            "Place a real call", "Open your dialer", "Weighted heuristic",
            "Nothing — permission missing", "Nothing — you have not agreed",
            "Nothing — no contact saved", "Send one alert text",
            "Send one alert text to contacts", "Per-level (10/5)", "10 s",
        )
        for (value in realValues) {
            // Side-by-side only when it genuinely fits the budget the comment on
            // ValueColumnMaxWidth documents (~251dp shared by label and value).
            val besideIsViable = value.length <= MaxSideBySideRowValueLength
            assertEquals(
                "'$value' laid out incorrectly",
                !besideIsViable,
                shouldStackRowValue(value),
            )
        }
    }
}