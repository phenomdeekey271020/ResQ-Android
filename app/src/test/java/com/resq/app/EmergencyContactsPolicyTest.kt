package com.resq.app

import com.resq.app.contacts.EmergencyContactsPolicy
import com.resq.app.model.EmergencyContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the trusted-contact list.
 *
 * They are pure Kotlin, so the whole decision surface - what may be stored, how
 * many, which one is called, and what happens when there is nothing to call - is
 * verified without a device, a dialer or a network.
 */
class EmergencyContactsPolicyTest {

    private fun contact(
        id: String,
        name: String = "Contact $id",
        phone: String = "98765432$id",
        primary: Boolean = false,
    ) = EmergencyContact(id = id, name = name, phoneNumber = phone, isPrimary = primary)

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    fun aPlainNationalNumber_isAccepted() {
        assertTrue(EmergencyContactsPolicy.isValidPhone("9876543210"))
    }

    @Test
    fun anInternationalNumberWithPunctuation_isAccepted() {
        assertTrue(EmergencyContactsPolicy.isValidPhone("+91 98765-43210"))
        assertTrue(EmergencyContactsPolicy.isValidPhone("(044) 2345 6789"))
    }

    @Test
    fun emptyOrTooShortNumbers_areRejected() {
        assertFalse(EmergencyContactsPolicy.isValidPhone(""))
        assertFalse(EmergencyContactsPolicy.isValidPhone("   "))
        assertFalse(EmergencyContactsPolicy.isValidPhone("12345"))
        assertFalse(EmergencyContactsPolicy.isValidPhone("+1"))
    }

    @Test
    fun numbersOverTheE164Limit_areRejected() {
        assertFalse(EmergencyContactsPolicy.isValidPhone("1234567890123456"))
    }

    @Test
    fun nonDialableText_isRejectedEvenWhenItContainsDigits() {
        assertFalse(EmergencyContactsPolicy.isValidPhone("call mum 9876543210"))
        assertFalse(EmergencyContactsPolicy.isValidPhone("9876543210 ext 4"))
    }

    @Test
    fun aPlusMisplaced_isRejected() {
        assertFalse(EmergencyContactsPolicy.isValidPhone("98+76543210"))
        assertFalse(EmergencyContactsPolicy.isValidPhone("++919876543210"))
    }

    @Test
    fun aBlankName_isRejected() {
        assertFalse(EmergencyContactsPolicy.isValidName("   "))
        assertFalse(EmergencyContactsPolicy.isValid("   ", "9876543210"))
    }

    @Test
    fun aValidContact_passesBothChecks() {
        assertTrue(EmergencyContactsPolicy.isValid("Priya Nair", "+91 98765 43210"))
    }

    // ------------------------------------------------------------------
    // Duplicates
    // ------------------------------------------------------------------

    @Test
    fun theSameNumberInADifferentFormat_isADuplicate() {
        val existing = listOf(contact("1", phone = "+91 98765 43210"))

        assertTrue(EmergencyContactsPolicy.isDuplicate(existing, "(+91) 98765-43210"))
        assertTrue(EmergencyContactsPolicy.isDuplicate(existing, "+919876543210"))
    }

    @Test
    fun theSameNationalNumberWithPunctuation_isADuplicate() {
        val existing = listOf(contact("1", phone = "9876543210"))

        assertTrue(EmergencyContactsPolicy.isDuplicate(existing, "(98765) 43210"))
        assertTrue(EmergencyContactsPolicy.isDuplicate(existing, "98765-43210"))
    }

    @Test
    fun theSameDigitsWithADifferentCountryCode_areNotTreatedAsTheSamePerson() {
        val existing = listOf(contact("1", phone = "+91 98765 43210"))

        // Duplicate detection is digit-exact on purpose: guessing that a local
        // number and a country-coded one are the same person would be wrong just
        // as often as it is right, and a wrong guess here means a missing contact.
        assertFalse(EmergencyContactsPolicy.isDuplicate(existing, "9876543210"))
    }

    @Test
    fun aContactDoesNotDuplicateItselfWhenEdited() {
        val existing = listOf(contact("1", phone = "9876543210"))

        assertFalse(EmergencyContactsPolicy.isDuplicate(existing, "9876543210", excludingId = "1"))
    }

    @Test
    fun aDifferentNumber_isNotADuplicate() {
        val existing = listOf(contact("1", phone = "9876543210"))

        assertFalse(EmergencyContactsPolicy.isDuplicate(existing, "9876543299"))
    }

    // ------------------------------------------------------------------
    // Cap and ordering
    // ------------------------------------------------------------------

    @Test
    fun upToThreeContactsAreKept() {
        val list = listOf(contact("1"), contact("2"), contact("3"))

        assertEquals(3, EmergencyContactsPolicy.upsert(list, contact("4")).size)
        assertEquals(3, EmergencyContactsPolicy.ensureSinglePrimary(list).size)
    }

    @Test
    fun aFourthContact_isRefusedAndNothingChanges() {
        val list = EmergencyContactsPolicy.ensureSinglePrimary(
            listOf(contact("1"), contact("2"), contact("3")),
        )

        val after = EmergencyContactsPolicy.upsert(list, contact("4"))

        assertEquals(list, after)
        assertTrue(after.none { it.id == "4" })
    }

    @Test
    fun updatingAnExistingContact_isAlwaysAllowed() {
        val list = EmergencyContactsPolicy.ensureSinglePrimary(
            listOf(contact("1"), contact("2"), contact("3")),
        )

        val after = EmergencyContactsPolicy.upsert(list, contact("2", name = "Renamed"))

        assertEquals(3, after.size)
        assertEquals("Renamed", after.first { it.id == "2" }.name)
    }

    // ------------------------------------------------------------------
    // Primary invariant and the safe fallback
    // ------------------------------------------------------------------

    @Test
    fun theFirstContactBecomesPrimaryWhenNobodyChose() {
        val list = EmergencyContactsPolicy.ensureSinglePrimary(
            listOf(contact("1"), contact("2")),
        )

        assertTrue(list.first { it.id == "1" }.isPrimary)
        assertFalse(list.first { it.id == "2" }.isPrimary)
    }

    @Test
    fun twoPrimaries_areReducedToOne() {
        val list = EmergencyContactsPolicy.ensureSinglePrimary(
            listOf(contact("1", primary = true), contact("2", primary = true)),
        )

        assertEquals(1, list.count { it.isPrimary })
        assertTrue(list.first { it.id == "1" }.isPrimary)
    }

    @Test
    fun makingSomebodyPrimary_demotesTheOthers() {
        val list = EmergencyContactsPolicy.withPrimary(
            listOf(contact("1", primary = true), contact("2")),
            "2",
        )

        assertEquals(1, list.count { it.isPrimary })
        assertTrue(list.first { it.id == "2" }.isPrimary)
    }

    @Test
    fun choosingAnUnknownContact_changesNothing() {
        val list = EmergencyContactsPolicy.ensureSinglePrimary(listOf(contact("1")))

        assertEquals(list, EmergencyContactsPolicy.withPrimary(list, "nope"))
    }

    @Test
    fun removingThePrimary_promotesTheNextContact() {
        val list = EmergencyContactsPolicy.remove(
            listOf(contact("1", primary = true), contact("2")),
            "1",
        )

        assertEquals(1, list.size)
        assertTrue(list.single().isPrimary)
    }

    @Test
    fun thePrimaryIsTheChosenContact() {
        val list = listOf(contact("1"), contact("2", primary = true))

        assertEquals("2", EmergencyContactsPolicy.primary(list)?.id)
    }

    @Test
    fun withNoChosenPrimary_theFirstContactIsTheSafeFallback() {
        val list = listOf(contact("1"), contact("2"))

        assertEquals("1", EmergencyContactsPolicy.primary(list)?.id)
    }

    @Test
    fun noContactsMeansNothingToCall() {
        assertNull(EmergencyContactsPolicy.primary(emptyList()))
        assertTrue(EmergencyContactsPolicy.ensureSinglePrimary(emptyList()).isEmpty())
    }
}
