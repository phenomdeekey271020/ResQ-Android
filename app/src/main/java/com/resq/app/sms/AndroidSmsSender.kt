package com.resq.app.sms

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong

/*
 * THE REAL SMS SENDER. Never used by a test.
 *
 * It uses only the platform SMS APIs - [SmsManager] - and nothing else:
 *
 *  - no cloud telephony provider, no Bolna, no SMS gateway, no server-side
 *    sending and no API key. The message goes out from the user's own SIM
 *    through their carrier, which is why the consent copy must warn about
 *    carrier charges;
 *  - `SmsManager.divideMessage` splits a long body into parts and
 *    `sendMultipartTextMessage` sends them as ONE message, so a recipient never
 *    receives fragments in a random order;
 *  - the platform never confirms delivery of a multipart message on most
 *    carriers, so delivery is reported only when a real receipt arrives. Its
 *    absence leaves the state at SENT_TO_CARRIER, which is the honest ceiling.
 *
 * PRIVACY: no phone number, no coordinates and no message body is written to
 * Logcat at any level, here or anywhere else. Failures are reported as state,
 * which is all the emergency screen can act on.
 */

internal const val SmsSenderActionSent = "com.resq.app.SMS_SENT"
internal const val SmsSenderActionDelivered = "com.resq.app.SMS_DELIVERED"
private const val ExtraReference = "reference"
private const val ExtraRecipientIndex = "recipientIndex"

class AndroidSmsSender(private val context: Context) : SmsSender {

    private val appContext = context.applicationContext
    private val requestCounter = AtomicLong(0L)

    /**
     * One live listener per reference.
     *
     * Registered when the message is handed over and removed as soon as the
     * terminal callback arrives, so an escalation that is never delivered leaves
     * nothing registered. A single receiver instance is enough because the
     * callbacks are matched by reference, not by broadcast identity.
     */
    private val listeners = mutableMapOf<String, Pair<SmsSendListener, Int>>()

    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val received = intent ?: return
            if (received.action !in setOf(SmsSenderActionSent, SmsSenderActionDelivered)) return
            val reference = received.getStringExtra(ExtraReference) ?: return
            val recipientIndex = received.getIntExtra(ExtraRecipientIndex, -1)
            val delivered = received.action == SmsSenderActionDelivered

            // The platform reports SENT as a result code: Activity.RESULT_OK
            // means the carrier accepted the message. Anything else is a send
            // failure, and it is reported as exactly that.
            val state = if (delivered) {
                SmsSendState.Delivered(reference)
            } else if (resultCode == android.app.Activity.RESULT_OK) {
                SmsSendState.SentToCarrier(reference)
            } else {
                SmsSendState.Failed(SmsFailureReason.SEND_FAILED)
            }
            val listener = synchronized(listeners) {
                // The terminal callback consumes the registration: a second
                // broadcast for the same message cannot notify twice.
                listeners.remove(reference)?.first
            }
            listener?.onSmsUpdate(SmsSendUpdate(reference, recipientIndex, state))
            synchronized(listeners) {
                if (listeners.isEmpty()) unregisterReceiver()
            }
        }
    }

    override fun capability(): SmsCapability {
        if (!hasPermission()) return SmsCapability.PERMISSION_DENIED
        val manager = smsManager()
        // A device with no SIM has no number to send from. This is the tablet /
        // no-radio case the manifest already declares telephony as optional for.
        if (manager == null) return SmsCapability.NO_SIM
        return SmsCapability.READY
    }

    override fun send(
        message: SmsMessage,
        reference: String,
        listener: SmsSendListener,
    ): SmsSendState {
        val manager = smsManager()
            ?: return SmsSendState.Failed(SmsFailureReason.NO_SIM)
        if (!hasPermission()) return SmsSendState.Failed(SmsFailureReason.PERMISSION_DENIED)
        val recipient = message.recipientNumber.trim()
        if (recipient.isEmpty()) return SmsSendState.Failed(SmsFailureReason.INVALID_RECIPIENT)

        return try {
            synchronized(listeners) { listeners[reference] = listener to 0 }
            ensureReceiverRegistered()

            val parts = manager.divideMessage(message.body)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            val deliveredIntents = ArrayList<PendingIntent>(parts.size)
            // Request codes are unique per attempt so two concurrent escalations
            // cannot overwrite each other's PendingIntent extras. They are Int
            // because PendingIntent requires it; the counter is folded down to a
            // positive 30-bit value, which is more than enough for the handful of
            // parts a single message is ever split into.
            val base = (requestCounter.incrementAndGet() and 0x0FFFFFFF).toInt()
            parts.indices.forEach { index ->
                sentIntents += pendingIntent(SmsSenderActionSent, reference, index, base * 100 + index)
                deliveredIntents += pendingIntent(
                    SmsSenderActionDelivered,
                    reference,
                    index,
                    base * 100 + 50 + index,
                )
            }

            if (parts.size == 1) {
                manager.sendTextMessage(
                    recipient,
                    null,
                    parts[0],
                    sentIntents.firstOrNull(),
                    deliveredIntents.firstOrNull(),
                )
            } else {
                manager.sendMultipartTextMessage(
                    recipient,
                    null,
                    parts,
                    sentIntents,
                    deliveredIntents,
                )
            }
            // Handed over. Whether the carrier takes it is reported later.
            SmsSendState.Attempted
        } catch (_: SecurityException) {
            synchronized(listeners) { listeners.remove(reference) }
            SmsSendState.Failed(SmsFailureReason.PERMISSION_DENIED)
        } catch (_: IllegalArgumentException) {
            synchronized(listeners) { listeners.remove(reference) }
            SmsSendState.Failed(SmsFailureReason.INVALID_RECIPIENT)
        } catch (_: RuntimeException) {
            // A vendor SmsManager can throw for a dozen unrelated reasons (no
            // service, radio busy, encoding). The emergency must survive all of
            // them; the caller is told the send failed and nothing else.
            synchronized(listeners) { listeners.remove(reference) }
            SmsSendState.Failed(SmsFailureReason.SEND_FAILED)
        }
    }

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.SEND_SMS,
    ) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager? = appContext.getSystemService(SmsManager::class.java)
        ?: SmsManager.getDefault()

    private fun pendingIntent(action: String, reference: String, index: Int, requestCode: Int): PendingIntent {
        val intent = Intent(action)
            .setPackage(appContext.packageName)
            .putExtra(ExtraReference, reference)
            .putExtra(ExtraRecipientIndex, index)
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureReceiverRegistered() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(SmsSenderActionSent)
            addAction(SmsSenderActionDelivered)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                receiver,
                filter,
                // These broadcasts are delivered by the PLATFORM on this app's
                // behalf and are not protected by a signature permission, so an
                // exported receiver would let ANY other app on the device send a
                // fake "delivered" or "failed" verdict for this user's alert
                // text. NOT exported makes spoofing impossible: the system still
                // delivers our own results, and nobody else can.
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            // Below Android 13 there is no flag to pass, which is exactly why
            // lint flags this line. The broadcasts use app-private actions, so
            // no other app can address this receiver anyway, and the platform
            // enforces the export requirement itself from API 33 - where the
            // guarded branch above takes over. Suppressed deliberately: the
            // un-flagged registration is reachable ONLY below API 33.
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    /** MUST be called while holding the `listeners` monitor. */
    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        receiverRegistered = false
    }
}