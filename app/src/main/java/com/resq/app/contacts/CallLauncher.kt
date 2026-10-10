package com.resq.app.contacts

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.resq.app.model.PrimaryContactCall

/*
 * THE ONLY WAY RESQ STARTS A CALL, AND WHY IT HAS TWO MODES.
 *
 * PLATFORM EVALUATION (targetSdk 37 / Android 16, minSdk 24)
 * ==========================================================
 *
 * ResQ escalates through one of three mechanisms, strongest first, and it
 * reports only what the platform actually did.
 *
 * 1. ACTION_CALL - places the call directly.
 *    Requires the dangerous runtime permission CALL_PHONE and explicit, informed
 *    consent. Technically supportable on this target SDK, and it is the only mode
 *    that satisfies "no response should escalate", because ACTION_DIAL always
 *    leaves a human to press Call - useless to someone who is unresponsive.
 *
 *    The real constraints, stated honestly:
 *      - CALL_PHONE is a runtime permission. It can be denied, and the user can
 *        revoke it from system settings at any time, so ResQ re-checks it at the
 *        moment of the attempt and never assumes it.
 *      - Starting the call requires launching an activity. From Android 10 a
 *        BACKGROUND activity start is blocked: the platform accepts the intent
 *        but may not show the in-call UI until the app is foregrounded. So a
 *        direct call is dependable only while ResQ is visible. When it is not,
 *        ResQ attempts it anyway (it costs nothing and succeeds more often than
 *        not) but reports the weaker, honest outcome and keeps a manual action.
 *      - While the screen is LOCKED, behaviour is OEM-dependent. Some builds
 *        place the call, others refuse until the device is unlocked. ResQ never
 *        claims a call was placed unless the platform accepted the intent, and
 *        never claims anyone answered in any case.
 *      - Google Play restricts CALL_PHONE: an app must declare why it needs it,
 *        and auto-dialling without a visible user action is exactly the kind of
 *        use that gets reviewed. This is why the consent is OFF by default,
 *        per-incident budgeted, primary-contact-only, and never used in Test
 *        Mode.
 *
 * 2. ACTION_DIAL - opens the dialer pre-filled; the user still presses Call.
 *    The fallback whenever a direct call is not permitted or not possible: no
 *    consent needed (the user acts), no special permission, works from a
 *    visible Activity.
 *
 * 3. Nothing - when the platform refuses both. The emergency screen then keeps
 *    a prominent manual Call action and the notification offers one too.
 *
 * Deliberately NOT used, under any circumstances: Accessibility services,
 * overlay windows, hidden APIs, process hiding, reflection into TelecomManager,
 * or any other bypass of the restrictions above. Those are how an app gets a
 * user hurt or removed from the store, and none of them is needed here.
 *
 * The interface exists so the incident flow can be tested with a fake launcher.
 * No automated test ever reaches the real dialer or places a call.
 */
interface CallLauncher {

    /**
     * Places a call to [number] directly, without user interaction.
     *
     * @return [CallLaunchResult.Placed] when the platform accepted the intent -
     *   which is NOT evidence that anyone answered, and is reported as such.
     */
    fun placeCall(number: String): CallLaunchResult

    /**
     * Opens the system dialer pre-filled with [number].
     *
     * @return [CallLaunchResult.Opened] when the platform launched a dialer for
     *   the intent, [CallLaunchResult.NoHandler] when it could not.
     */
    fun openDialer(number: String): CallLaunchResult

    /** True when CALL_PHONE is currently granted. Re-read on every call. */
    fun canPlaceCallsDirectly(): Boolean
}

/** What the platform did with a call intent. */
sealed interface CallLaunchResult {

    /**
     * ACTION_CALL was dispatched and accepted. This means ResQ asked the phone to
     * place a real call - it says NOTHING about whether it rang, connected or was
     * answered, and the UI must never phrase it as if it did.
     */
    data object Placed : CallLaunchResult

    /** A dialer accepted the intent. The call is NOT placed - the user confirms. */
    data object Opened : CallLaunchResult

    /** Nothing on the device could handle `tel:`, or the platform refused. */
    data object NoHandler : CallLaunchResult

    /** CALL_PHONE is not granted, so a direct call may not be attempted. */
    data object PermissionNotGranted : CallLaunchResult
}

/** What a completed escalation attempt should be reported to the user as. */
fun CallLaunchResult.toPrimaryContactCall(contactName: String): PrimaryContactCall =
    when (this) {
        CallLaunchResult.Placed -> PrimaryContactCall.CallPlaced(contactName)
        CallLaunchResult.Opened -> PrimaryContactCall.DialerOpened(contactName)
        CallLaunchResult.NoHandler -> PrimaryContactCall.CouldNotStart
        CallLaunchResult.PermissionNotGranted -> PrimaryContactCall.CallPermissionRequired
    }

/**
 * The real launcher used by the app. Never by tests.
 *
 * Both methods go through the application context, so an attempt made while the
 * app is backgrounded is still delivered to the platform rather than throwing -
 * the outcome then honestly reflects whatever the platform decided.
 */
class SystemCallLauncher(context: Context) : CallLauncher {

    private val appContext = context.applicationContext

    override fun canPlaceCallsDirectly(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CALL_PHONE,
        ) == PackageManager.PERMISSION_GRANTED

    override fun placeCall(number: String): CallLaunchResult {
        val trimmed = number.trim()
        if (trimmed.isEmpty()) return CallLaunchResult.NoHandler
        // Re-checked here, not assumed from an earlier grant: the user can revoke
        // CALL_PHONE from system settings between the consent screen and the
        // incident, and a SecurityException here would otherwise escape as a crash
        // in the middle of an emergency.
        if (!canPlaceCallsDirectly()) return CallLaunchResult.PermissionNotGranted
        return startTel(Intent.ACTION_CALL, trimmed)
    }

    override fun openDialer(number: String): CallLaunchResult {
        val trimmed = number.trim()
        if (trimmed.isEmpty()) return CallLaunchResult.NoHandler
        return startTel(Intent.ACTION_DIAL, trimmed)
    }

    private fun startTel(action: String, number: String): CallLaunchResult {
        val intent = Intent(action, Uri.parse("tel:${Uri.encode(number)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            // The strongest honest claim for each action. ACTION_CALL means ResQ
            // asked the phone to place the call; it is not a connected call.
            if (action == Intent.ACTION_CALL) CallLaunchResult.Placed else CallLaunchResult.Opened
        } catch (_: SecurityException) {
            // Permission revoked between the check and the dispatch, or an OEM
            // policy refusing the action.
            CallLaunchResult.PermissionNotGranted
        } catch (_: ActivityNotFoundException) {
            CallLaunchResult.NoHandler
        } catch (_: IllegalStateException) {
            // Thrown when the app context cannot resolve the intent at all.
            CallLaunchResult.NoHandler
        }
    }
}