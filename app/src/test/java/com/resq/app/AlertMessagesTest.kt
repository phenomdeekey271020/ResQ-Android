package com.resq.app

import com.resq.app.alert.AlertCue
import com.resq.app.alert.AlertMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spoken warnings.
 *
 * They are fixed strings in code on purpose - no network request, no backend and
 * no contact details - so this is the whole contract: each cue has a message, the
 * two cues differ, and neither promises anything ResQ cannot deliver.
 */
class AlertMessagesTest {

    @Test
    fun everyCueHasASpokenMessage() {
        AlertCue.entries.forEach { cue ->
            assertTrue(
                "Cue $cue has no spoken message",
                AlertMessages.forCue(cue).isNotBlank(),
            )
        }
    }

    @Test
    fun theTwoWarningsAreDifferentMessages() {
        assertFalse(
            AlertMessages.POSSIBLE_ACCIDENT == AlertMessages.NO_RESPONSE,
        )
    }

    @Test
    fun theFirstWarning_asksTheUserAndOffersBothAnswers() {
        val message = AlertMessages.forCue(AlertCue.POSSIBLE_ACCIDENT)

        assertTrue(message.contains("Possible accident", ignoreCase = true))
        assertTrue(message.contains("okay", ignoreCase = true))
        assertTrue(message.contains("I'm Okay", ignoreCase = true))
        assertTrue(message.contains("I Need Help", ignoreCase = true))
    }

    @Test
    fun theSecondWarning_explainsThatTheResponseIsStarting() {
        val message = AlertMessages.forCue(AlertCue.NO_RESPONSE)

        assertTrue(message.contains("No response", ignoreCase = true))
        assertTrue(message.contains("emergency response", ignoreCase = true))
    }

    @Test
    fun noSpokenMessage_claimsAConfirmedCrashOrThatAnyoneWasContacted() {
        AlertCue.entries.forEach { cue ->
            val wording = AlertMessages.forCue(cue).lowercase()
            assertFalse(cue.name, wording.contains("crash"))
            assertFalse(cue.name, wording.contains("called the"))
            assertFalse(cue.name, wording.contains("contacted"))
            assertFalse(cue.name, wording.contains("ambulance"))
        }
    }

    @Test
    fun theMessagesAreStableSoATestCannotSilentlyRewriteThem() {
        assertEquals(AlertMessages.POSSIBLE_ACCIDENT, AlertMessages.forCue(AlertCue.POSSIBLE_ACCIDENT))
        assertEquals(AlertMessages.NO_RESPONSE, AlertMessages.forCue(AlertCue.NO_RESPONSE))
    }
}
