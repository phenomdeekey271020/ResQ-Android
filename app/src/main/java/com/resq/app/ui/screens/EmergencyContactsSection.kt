package com.resq.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.resq.app.model.EmergencyContact
import com.resq.app.model.PrimaryContactCall
import com.resq.app.model.SmsEscalation
import com.resq.app.ui.components.GlassRow
import com.resq.app.ui.components.GlassRowGroup
import com.resq.app.ui.components.RowDivider
import com.resq.app.ui.components.SectionLabel
import com.resq.app.ui.theme.LocalResQColors
import com.resq.app.ui.theme.ResQSpacing
import com.resq.app.ui.theme.ResQTextStyles

/**
 * One truthful sentence per ALERT TEXT outcome.
 *
 * The rule is stricter than for the call, because a message is even further out
 * of ResQ's reach: a carrier accepting a message is NOT delivery, and delivery
 * receipts are operator-dependent, so the copy says "handed to your carrier" at
 * most and never implies anyone read it. Each outcome also names the manual
 * fallback, which is the whole point of a refused or failed text.
 */
internal fun smsEscalationCopy(sms: SmsEscalation): String = when (sms) {
    SmsEscalation.NotAttempted ->
        "No alert text was sent."

    SmsEscalation.ConsentRequired ->
        "ResQ did not send an alert text — you have not agreed to it. You can " +
            "allow it in Settings. Tap a contact above to call them now."

    SmsEscalation.NoContactConfigured ->
        "No emergency contact is configured, so there was nobody to text."

    SmsEscalation.SmsPermissionRequired ->
        "ResQ needs the text-message permission to send an alert from this " +
            "phone. Allow it below, or tap a contact above to call them."

    SmsEscalation.NoSimAvailable ->
        "This phone has no SIM, so no alert text could be sent. Tap a contact " +
            "above to call them, or call $EmergencyNumber."

    is SmsEscalation.SentToCarrier ->
        "ResQ handed one alert text per contact to your carrier " +
            "(${sms.recipientCount} recipient${if (sms.recipientCount == 1) "" else "s"}). " +
            "Your carrier may charge for it. Delivery cannot be confirmed here, " +
            "so please also call if you are worried."

    is SmsEscalation.PartiallySent ->
        "ResQ handed alert texts to your carrier for " +
            "${sms.sentRecipientCount} contact${if (sms.sentRecipientCount == 1) "" else "s"}, " +
            "but some could not be sent. Delivery cannot be confirmed here — " +
            "please call those contacts directly."

    is SmsEscalation.CouldNotSend ->
        when (sms.reason) {
            SmsEscalation.SmsFailureReasonMirror.PERMISSION_DENIED ->
                "ResQ could not send an alert text — the text-message permission " +
                    "is missing. Tap a contact above to call them."

            SmsEscalation.SmsFailureReasonMirror.INVALID_RECIPIENT ->
                "ResQ could not send the alert text — a contact's number is not " +
                    "valid. Please call them directly."

            SmsEscalation.SmsFailureReasonMirror.NO_SIM ->
                "ResQ could not send an alert text — this phone has no SIM. " +
                    "Tap a contact above to call them."

            SmsEscalation.SmsFailureReasonMirror.SEND_FAILED ->
                "ResQ could not send the alert text — your phone could not reach " +
                    "the network. Please call the contacts directly."
        }
}

/**
 * The configured trusted contacts, shown on the emergency screens.
 *
 * Two jobs:
*  - it lists every contact with a call action, so a call is always reachable
 *     even when the automatic attempt did not run or could not start;
 *  - it reports what ResQ actually did about the call in one truthful sentence.
 *
 * It never claims a call connected. ResQ can ask Android to place a call, but it
 * has no way of knowing whether it rang, connected or was answered, so every
 * outcome is phrased as "ResQ asked your phone to…".
 */
@Composable
fun EmergencyContactsSection(
    contacts: List<EmergencyContact>,
    primaryCall: PrimaryContactCall,
    onCallContact: (EmergencyContact) -> Unit,
    modifier: Modifier = Modifier,
    smsEscalation: SmsEscalation = SmsEscalation.NotAttempted,
) {
    val colors = LocalResQColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm),
    ) {
        SectionLabel("Emergency contacts")
        GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
            if (contacts.isEmpty()) {
                Text(
                    text = "No trusted contacts configured.",
                    style = ResQTextStyles.row,
                    color = colors.textMuted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                contacts.forEachIndexed { index, contact ->
                    GlassRow(
                        label = contact.name,
                        value = if (contact.isPrimary) "Primary · Call" else "Call",
                        showDot = contact.isPrimary,
                        onClick = { onCallContact(contact) },
                    )
                    if (index != contacts.lastIndex) RowDivider()
                }
            }
        }
        Text(
            text = primaryCallCopy(primaryCall, contacts),
            style = ResQTextStyles.caption,
            color = colors.textMuted,
            modifier = Modifier.fillMaxWidth(),
        )
        // The alert text is a SECOND escalation action, so it gets its own
        // truthful sentence rather than being folded into the call status. It is
        // always rendered - including "no alert text was sent" - so the user is
        // never left wondering whether something was quietly attempted.
        Text(
            text = smsEscalationCopy(smsEscalation),
            style = ResQTextStyles.caption,
            color = colors.textMuted,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * One truthful sentence per call outcome. Pure function, so the wording is
 * reviewable in one place and testable without rendering anything.
 *
 * The single rule this copy obeys: a dispatched Intent is evidence that ResQ
 * ASKED the phone to do something, and never evidence that a person was reached.
 * [PrimaryContactCall.CallPlaced] is the sharpest case of that - a real call was
 * requested, and the sentence still must not imply anyone picked up.
 */
internal fun primaryCallCopy(
    primaryCall: PrimaryContactCall,
    contacts: List<EmergencyContact>,
): String = when (primaryCall) {
    PrimaryContactCall.NotAttempted ->
        if (contacts.isEmpty()) {
            "Add trusted contacts in Settings before an emergency."
        } else {
            "Tap a contact to call them. ResQ opens your dialer; you still press call."
        }

    PrimaryContactCall.ConsentRequired ->
        "ResQ did not call anyone automatically — escalation consent is off. " +
            "Tap a contact to call them, or enable it in Settings."

    PrimaryContactCall.NoContactConfigured ->
        "No emergency contact is configured. Calling the emergency number " +
            "does not need one."

    is PrimaryContactCall.CallPlaced ->
        // The strongest claim the platform actually supports, and no stronger.
        //
        // Deliberately avoids the words "connected", "answered" and "reached"
        // even in a negation: a frightened user skimming a red screen reads the
        // nouns, not the "cannot". What happened is stated positively and
        // exactly - ResQ asked Android to place the call - and the limit of what
        // ResQ knows is stated without reusing those words.
        "ResQ asked your phone to place a call to ${primaryCall.contactName}. " +
            "ResQ has no way to know what happened next. Tap a contact below " +
            "to call them yourself."

    is PrimaryContactCall.DialerOpened ->
        // Worded for the strongest honest claim: the platform accepted the
        // dial intent. On Android 10+ the launch itself can be suppressed when
        // the app is in the background with the screen off, so the sentence
        // also tells the user how to finish the call instead of pretending it
        // is already ringing.
        "ResQ asked your phone to open the dialer for ${primaryCall.contactName}. " +
            "If the screen was locked, unlock it and press call — ResQ cannot " +
            "confirm the call."

    PrimaryContactCall.CouldNotStart ->
        "The dialer could not be opened automatically. Tap a contact below to call them."

    PrimaryContactCall.CallPermissionRequired ->
        "ResQ needs the phone-call permission to place a call for you. Allow it " +
            "below, or tap a contact to open the dialer and press call yourself."

    PrimaryContactCall.ManualCallRequired ->
        "ResQ could not place the call automatically — Android blocks it while " +
            "the screen is locked or the app is in the background. Unlock the " +
            "phone and open ResQ, or tap a contact below to call them."
}
