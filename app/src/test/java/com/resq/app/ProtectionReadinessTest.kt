package com.resq.app

import com.resq.app.contacts.EmergencyContactsStore
import com.resq.app.model.AppScreen
import com.resq.app.model.EmergencyContact
import com.resq.app.model.ProtectionReadiness
import com.resq.app.model.ProtectionState
import com.resq.app.viewmodel.ResQViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The protection-readiness model behind first-launch contact setup.
 *
 * Three distinct situations, never one Boolean:
 *  - no contacts at all (the state the onboarding step offers to fix, and the
 *    state that earns the persistent Home warning after a skip),
 *  - contacts without escalation consent (escalation stays silent by design),
 *  - contacts AND consent (the only state where an incident may attempt the
 *    automatic primary-contact call).
 *
 * Plus the two guarantees the skip path depends on: valid contacts survive a
 * restart, and missing contacts can never block protection or the emergency
 * screen.
 */
class ProtectionReadinessTest {

    @Test
    fun aFreshInstallation_reportsContactSetupIncomplete() {
        val viewModel = ResQViewModel(contactsStore = FakeStore())

        assertEquals(
            ProtectionReadiness.CONTACT_SETUP_INCOMPLETE,
            viewModel.state.protectionReadiness,
        )
    }

    @Test
    fun savedContactsWithoutConsent_reportConsentDisabled() {
        val viewModel = ResQViewModel(contactsStore = FakeStore())
        viewModel.saveContact(null, "Priya Nair", "9876543210", true)

        assertEquals(
            ProtectionReadiness.CONSENT_DISABLED,
            viewModel.state.protectionReadiness,
        )
    }

    @Test
    fun savedContactsWithConsent_reportReady() {
        val viewModel = ResQViewModel(contactsStore = FakeStore())
        viewModel.saveContact(null, "Priya Nair", "9876543210", true)
        viewModel.setEscalationConsent(true)

        assertEquals(ProtectionReadiness.READY, viewModel.state.protectionReadiness)
    }

    @Test
    fun consentIsOffByDefaultUntilTheUserEnablesIt() {
        val store = FakeStore(consent = false)
        val viewModel = ResQViewModel(contactsStore = store)

        viewModel.saveContact(null, "Priya Nair", "9876543210", true)

        assertTrue(!viewModel.state.escalationConsent)
        assertTrue(!store.isEscalationConsentGranted())
    }

    @Test
    fun removingTheLastContact_returnsToContactSetupIncomplete() {
        val viewModel = ResQViewModel(contactsStore = FakeStore())
        val saved = requireNotNull(viewModel.saveContact(null, "Priya", "9876543210", true))
        viewModel.setEscalationConsent(true)

        viewModel.removeContact(saved.id)

        assertEquals(
            ProtectionReadiness.CONTACT_SETUP_INCOMPLETE,
            viewModel.state.protectionReadiness,
        )
    }

    @Test
    fun validContactsPersistAcrossARestart() {
        val store = FakeStore()
        val first = ResQViewModel(contactsStore = store)
        val saved = requireNotNull(first.saveContact(null, "Priya Nair", "9876543210", true))

        // "Restart": a brand-new ViewModel over the same on-disk store.
        val second = ResQViewModel(contactsStore = store)

        assertEquals(1, second.state.contacts.size)
        val loaded = second.state.contacts.single()
        assertEquals(saved.id, loaded.id)
        assertEquals("Priya Nair", loaded.name)
        assertTrue(loaded.isPrimary)
        assertEquals(ProtectionReadiness.CONSENT_DISABLED, second.state.protectionReadiness)
    }

    @Test
    fun skippingContactSetup_neverBlocksProtectionOrTheApp() {
        // The skip path: no contacts, no onboarding state in the ViewModel at
        // all. Protection and the screens must work regardless.
        val viewModel = ResQViewModel(contactsStore = FakeStore())
        viewModel.stopProtection()
        viewModel.startProtection()

        assertEquals(ProtectionState.ACTIVE, viewModel.state.protection)
        assertEquals(AppScreen.HOME, viewModel.state.screen)

        // And the user can finish setup later, which flips the readiness.
        viewModel.saveContact(null, "Priya Nair", "9876543210", true)
        viewModel.setEscalationConsent(true)
        assertEquals(ProtectionReadiness.READY, viewModel.state.protectionReadiness)
    }
}

/** In-memory storage; nothing here ever touches Android. */
private class FakeStore(
    private var contacts: List<EmergencyContact> = emptyList(),
    private var consent: Boolean = false,
    private var smsConsent: Boolean = false,
) : EmergencyContactsStore {

    override fun contacts(): List<EmergencyContact> = contacts

    override fun saveContacts(contacts: List<EmergencyContact>) {
        this.contacts = contacts
    }

    override fun isEscalationConsentGranted(): Boolean = consent

    override fun setEscalationConsentGranted(granted: Boolean) {
        consent = granted
    }

    override fun isSmsEscalationConsentGranted(): Boolean = smsConsent

    override fun setSmsEscalationConsentGranted(granted: Boolean) {
        smsConsent = granted
    }
}
