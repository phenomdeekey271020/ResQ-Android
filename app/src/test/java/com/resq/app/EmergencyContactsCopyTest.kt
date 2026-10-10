package com.resq.app

import com.resq.app.model.EmergencyContact
import com.resq.app.model.PrimaryContactCall
import com.resq.app.ui.screens.primaryCallCopy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the emergency screen says about the contact call.
 *
 * This is the sentence a frightened user reads, so it is asserted in both
 * directions: it must explain what happened, and it must never imply that a call
 * connected, that anyone answered, or that emergency services were notified.
 */
class EmergencyContactsCopyTest {

    private val contact = EmergencyContact(
        id = "a",
        name = "Priya Nair",
        phoneNumber = "9876543210",
        isPrimary = true,
    )

    private fun copy(outcome: PrimaryContactCall, contacts: List<EmergencyContact> = listOf(contact)) =
        primaryCallCopy(outcome, contacts)

    @Test
    fun aLaunchedDialer_isDescribedAsADialerAndNeverAsAConnectedCall() {
        val text = copy(PrimaryContactCall.DialerOpened(contact.name))

        assertTrue(text.contains(contact.name))
        assertTrue(text.contains("dialer", ignoreCase = true))
        val lower = text.lowercase()
        assertFalse(lower.contains("connected"))
        assertFalse(lower.contains("answered"))
        assertFalse(lower.contains("is calling"))
        assertFalse(lower.contains("notified"))
    }

    @Test
    fun aLaunchedDialer_tellsTheLockedScreenUserHowToFinishTheCall() {
        // Android may suppress the dialer launch when ResQ is backgrounded with
        // the screen off (background-activity-start restriction), so the copy
        // must help the user finish instead of implying the phone is already
        // ringing.
        val text = copy(PrimaryContactCall.DialerOpened(contact.name))

        assertTrue(text.contains("lock", ignoreCase = true))
        assertTrue(text.contains("press call", ignoreCase = true))
        assertTrue(text.contains("cannot confirm", ignoreCase = true))
    }

    @Test
    fun aMissingConsent_explainsThatNobodyWasCalledAndStillOffersTheAction() {
        val text = copy(PrimaryContactCall.ConsentRequired)

        assertTrue(text.contains("consent", ignoreCase = true))
        assertTrue(text.contains("did not call", ignoreCase = true))
        assertTrue(text.contains("Tap a contact", ignoreCase = true))
    }

    @Test
    fun noConfiguredContact_pointsAtTheEmergencyNumberInstead() {
        val text = copy(PrimaryContactCall.NoContactConfigured, contacts = emptyList())

        assertTrue(text.contains("No emergency contact", ignoreCase = true))
        assertTrue(text.contains("emergency number", ignoreCase = true))
    }

    @Test
    fun aFailedLaunch_offersTheExplicitFallback() {
        val text = copy(PrimaryContactCall.CouldNotStart)

        assertTrue(text.contains("could not be opened", ignoreCase = true))
        assertTrue(text.contains("Tap a contact", ignoreCase = true))
    }

    @Test
    fun theUntouchedCase_stillTellsTheUserHowToCallSomebody() {
        val withContacts = copy(PrimaryContactCall.NotAttempted)
        val withoutContacts = copy(PrimaryContactCall.NotAttempted, contacts = emptyList())

        assertTrue(withContacts.contains("Tap a contact", ignoreCase = true))
        assertTrue(withoutContacts.contains("Settings", ignoreCase = true))
    }

    @Test
    fun noOutcomeEverClaimsTheEmergencyServicesWereContacted() {
        val outcomes = listOf(
            PrimaryContactCall.NotAttempted,
            PrimaryContactCall.ConsentRequired,
            PrimaryContactCall.NoContactConfigured,
            PrimaryContactCall.DialerOpened(contact.name),
            PrimaryContactCall.CouldNotStart,
        )

        outcomes.forEach { outcome ->
            val lower = copy(outcome).lowercase()
            assertFalse(outcome.toString(), lower.contains("ambulance"))
            assertFalse(outcome.toString(), lower.contains("dispatched"))
            assertFalse(outcome.toString(), lower.contains("hospital was"))
        }
    }
}
