package com.resq.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.resq.app.model.AppScreen
import com.resq.app.model.AppearanceMode
import com.resq.app.model.EmergencyState
import com.resq.app.model.IncidentState
import com.resq.app.model.ProtectionReadiness
import com.resq.app.model.ProtectionState
import com.resq.app.ui.screens.DiagnosticsScreen
import com.resq.app.ui.screens.EmergencyResponseScreen
import com.resq.app.ui.screens.IncidentAlertScreen
import com.resq.app.ui.screens.ProtectionHomeScreen
import com.resq.app.ui.screens.SettingsDestination
import com.resq.app.ui.screens.SettingsScreen
import com.resq.app.ui.theme.LocalResQColors
import com.resq.app.ui.theme.ResQBackground
import com.resq.app.ui.theme.ResQTheme
import com.resq.app.viewmodel.ResQViewModel

/**
 * Root composition for the ResQ experiences.
 *
 * The atmosphere (Light / Dark / Emergency) is decided here from the real
 * [ResQViewModel] state: any incident or active emergency overrides the user's
 * appearance choice with the emergency theme, and returns to it when cleared.
 */
@Composable
fun ResQApp(
    viewModel: ResQViewModel,
    appearance: AppearanceMode,
    onAppearanceChange: (AppearanceMode) -> Unit,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state = viewModel.state
    LaunchedEffect(viewModel) { viewModel.startTelemetryStream() }

    /*
     * RECOVERY WHEN THE USER COMES BACK FROM ANDROID SETTINGS.
     *
     * This is the "turn Location on, press back, it just works" half of the fix.
     * A lifecycle-aware resume observer is used rather than a LaunchedEffect on
     * the location state, because the state does not change while the app is away
     * - it is the RETURN that has to trigger the re-check.
     *
     * The observer is registered once per LifecycleOwner and removed on dispose,
     * so it cannot leak or fire for a screen that is gone. The ViewModel decides
     * whether recovery is actually warranted (see
     * ResQViewModel.onEmergencyScreenResumed), which is what keeps recomposition
     * and unrelated navigation from starting location requests.
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onEmergencyScreenResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val emergencyAtmosphere = when (state.screen) {
        AppScreen.INCIDENT -> true
        AppScreen.EMERGENCY -> state.emergency != EmergencyState.Inactive
        else -> false
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        viewModel.onLocationPermissionResult(grants.values.any { granted -> granted })
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.startProtection()
    }

    /*
     * CALL_PHONE is requested here and ONLY here - a visible, deliberate tap on an
     * escalation control. Two rules make this defensible:
     *  - it is never requested automatically at incident time. A permission dialog
     *    during an emergency is both unusable and coercive;
     *  - a denial is not retried automatically. The emergency screen falls back to
     *    the dialer, which always works.
     *
     * The result is recorded so the ViewModel can re-evaluate its reporting: a
     * granted permission upgrades future escalations to a real call, and a denial
     * is reported honestly rather than silently degrading.
     */
    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onCallPermissionResult(granted)
    }

    /*
     * SEND_SMS is requested here and ONLY from an explicit, visible tap in
     * Settings - never automatically, and never at incident time.
     *
     * The same two rules that protect the CALL_PHONE prompt apply here and matter
     * even more: a permission dialog during an emergency is unusable and
     * coercive, and a denial must never be retried automatically. A user who says
     * no keeps a working manual fallback - the emergency screen's per-contact
     * Call actions - so a refusal is a choice, not a dead end.
     */
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onSmsPermissionResult(granted)
    }

    // Dialog states: the end-emergency confirmation and the first-launch
    // contact-setup step. Both live here so the ViewModel stays free of
    // Compose and every path still funnels into the existing intents.
    var confirmEndEmergency by remember { mutableStateOf(false) }
    var showContactSetup by remember { mutableStateOf(false) }
    // Settings deep link; reset to Main whenever Settings is closed so the next
    // open always starts on the main menu.
    var settingsDestination by remember {
        mutableStateOf<SettingsDestination>(SettingsDestination.Main)
    }

    /** The part of enabling protection that follows the contact-setup decision. */
    val enableProtection: () -> Unit = {
        if (needsNotificationPermission(context)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startProtection()
        }
    }

    val toggleProtection: () -> Unit = {
        if (state.protection == ProtectionState.ACTIVE) {
            viewModel.stopProtection()
        } else if (state.protectionReadiness == ProtectionReadiness.CONTACT_SETUP_INCOMPLETE) {
            // First-launch contact setup: offered at the moment protection
            // would be enabled, BEFORE it turns on. Skipping is always allowed
            // and never traps the user - protection starts either way, and the
            // persistent Home warning keeps the gap visible afterwards.
            showContactSetup = true
        } else {
            enableProtection()
        }
    }

    /**
     * Leaving the emergency screen, from the in-app arrow or the system Back
     * gesture - one handler for both, so the two can never behave differently.
     * An unresolved incident is never discarded silently: the user confirms
     * first, in the same dialog style the app already uses for pausing
     * protection.
     */
    val requestLeaveEmergency: () -> Unit = {
        if (state.emergency != EmergencyState.Inactive) {
            confirmEndEmergency = true
        } else {
            viewModel.standDown()
        }
    }

    val closeSettings: () -> Unit = {
        settingsDestination = SettingsDestination.Main
        viewModel.closeSettings()
    }

    BackHandler(
        enabled = state.screen != AppScreen.HOME,
        onBack = {
            when (state.screen) {
                AppScreen.SETTINGS -> closeSettings()
                AppScreen.EMERGENCY -> requestLeaveEmergency()
                AppScreen.DIAGNOSTICS -> viewModel.closeDiagnostics()
                else -> Unit
            }
        },
    )

    val home: @Composable () -> Unit = {
        ProtectionHomeScreen(
            uiState = state,
            onToggleProtection = toggleProtection,
            onOpenSettings = viewModel::openSettings,
            onOpenDiagnostics = viewModel::openDiagnostics,
        )
    }

    ResQTheme(darkTheme = darkTheme, emergency = emergencyAtmosphere) {
        ResQBackground(modifier = modifier) {
            Crossfade(
                targetState = state.screen,
                animationSpec = tween(durationMillis = if (emergencyAtmosphere) 300 else 240),
                label = "resqScreen",
            ) { screen ->
                when (screen) {
                    AppScreen.HOME -> home()

                    AppScreen.INCIDENT -> if (state.incident == IncidentState.None) {
                        home()
                    } else {
                        IncidentAlertScreen(
                            incident = state.incident,
                            confirmationSecondsRemaining = state.confirmationSecondsRemaining,
                            onUserOk = viewModel::confirmUserOk,
                            onRequestHelp = viewModel::requestEmergencyHelp,
                        )
                    }

                    AppScreen.EMERGENCY -> if (state.emergency == EmergencyState.Inactive) {
                        home()
                    } else {
                        EmergencyResponseScreen(
                            emergency = state.emergency,
                            location = state.emergencyLocation,
                            discovery = state.resourceDiscovery,
                            recovery = state.locationRecovery,
                            contacts = state.contacts,
                            primaryCall = state.primaryContactCall,
                            smsEscalation = state.smsEscalation,
                            onCallContact = viewModel::callContact,
                            onStandDown = requestLeaveEmergency,
                            onEnableLocation = {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ),
                                )
                            },
                            onRefreshLocation = viewModel::recoverResourceLocation,
                            // The device-wide Location toggle, not just this app's
                            // permission page: the reported bug is a user who
                            // turned the MASTER switch on and expected ResQ to
                            // recover. Offering only the app page would send them
                            // to a screen that cannot fix it.
                            onOpenLocationSettings = { openLocationSettings(context) },
                            // A DENIED runtime permission is fixed on this app's
                            // own settings page; the emergency screen's "Open app
                            // settings" action lands here so the label and the
                            // destination agree.
                            onOpenAppSettings = { openApplicationSettings(context) },
                            onRequestCallPermission = {
                                callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                            },
                            onRequestSmsPermission = {
                                smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                            },
                            onRetryResources = viewModel::retryResourceDiscovery,
                        )
                    }

                    AppScreen.DIAGNOSTICS -> DiagnosticsScreen(
                        uiState = state,
                        onBack = viewModel::closeDiagnostics,
                        onSimulateHardBraking = viewModel::simulateHardBraking,
                        onSimulatePossibleCollision = viewModel::simulatePossibleCollision,
                        onSimulateHighConfidence = viewModel::simulateHighConfidenceCollision,
                        onSimulateUserOk = viewModel::simulateUserOk,
                        onSimulateNoResponse = viewModel::simulateNoResponse,
                        onResetSimulation = viewModel::resetSimulation,
                        onRequestLocationPermission = {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                    )

                    AppScreen.SETTINGS -> SettingsScreen(
                        appearance = appearance,
                        onAppearanceChange = onAppearanceChange,
                        uiState = state,
                        onProtectionToggle = toggleProtection,
                        onBack = closeSettings,
                        onRequestLocationPermission = {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                        onOpenLocationSettings = { openApplicationSettings(context) },
                        // The Permissions submenu's "Phone calls" row drives the
                        // same launcher the emergency escalation uses; denial is
                        // reported through the existing capability check, not
                        // silently swallowed here.
                        onRequestCallPermission = {
                            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                        },
                        onRequestSmsPermission = {
                            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                        },
                        onOpenDiagnostics = viewModel::openDiagnostics,
                        onSaveContact = { id, name, phone, makePrimary ->
                            viewModel.saveContact(id, name, phone, makePrimary) != null
                        },
                        onRemoveContact = viewModel::removeContact,
                        onMakePrimaryContact = viewModel::makeContactPrimary,
                        onEscalationConsentChange = viewModel::setEscalationConsent,
                        onSmsEscalationConsentChange = viewModel::setSmsEscalationConsent,
                        initialDestination = settingsDestination,
                        // Only claim system back while Settings is really the
                        // current screen, so a submenu cannot swallow a press
                        // during the Crossfade away from Settings.
                        isActive = state.screen == AppScreen.SETTINGS,
                    )
                }
            }

            val dialogColors = LocalResQColors.current

            // End-emergency confirmation. Shown for BOTH back paths whenever an
            // incident is unresolved; confirming is the only way the incident
            // state is cleared.
            if (confirmEndEmergency) {
                AlertDialog(
                    onDismissRequest = { confirmEndEmergency = false },
                    containerColor = dialogColors.bg,
                    titleContentColor = dialogColors.text,
                    textContentColor = dialogColors.textMuted,
                    title = { Text("End this emergency?") },
                    text = {
                        Text(
                            "ResQ will clear this incident and return to the home screen. " +
                                "The current location search and incident state will be " +
                                "discarded. Your trusted contacts stay configured.",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmEndEmergency = false
                                viewModel.standDown()
                            },
                        ) {
                            Text("End emergency", color = dialogColors.statusCritical)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmEndEmergency = false }) {
                            Text("Keep emergency open", color = dialogColors.accent)
                        }
                    },
                )
            }

            // First-launch contact setup, offered before protection is enabled.
            // Skipping proceeds with the original intent immediately; the Home
            // warning keeps the incomplete setup visible afterwards.
            if (showContactSetup) {
                AlertDialog(
                    onDismissRequest = { showContactSetup = false },
                    containerColor = dialogColors.bg,
                    titleContentColor = dialogColors.text,
                    textContentColor = dialogColors.textMuted,
                    title = { Text("Set up emergency contacts") },
                    text = {
                        Text(
                            "ResQ uses up to ${com.resq.app.contacts.EmergencyContactsPolicy.MaxContacts} " +
                                "trusted contacts as part of its configured emergency " +
                                "escalation workflow: when an emergency escalates it can open " +
                                "your dialer for the primary contact - only if you allow that " +
                                "in Settings. Adding a contact does not guarantee anyone is " +
                                "reached, and ResQ cannot confirm a call connected. Numbers " +
                                "stay on this device. You can skip this and finish later in " +
                                "Settings; calling 112 never needs a contact.",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showContactSetup = false
                                settingsDestination = SettingsDestination.EmergencyContacts
                                viewModel.openSettings()
                            },
                        ) {
                            Text("Add contacts", color = dialogColors.accent)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showContactSetup = false
                                enableProtection()
                            },
                        ) {
                            Text("Not now", color = dialogColors.text)
                        }
                    },
                )
            }
        }
    }
}

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED

/** Deep-links to this app's system settings page (permission recovery paths). */
private fun openApplicationSettings(context: Context) {
    val intent = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
    val uri = android.net.Uri.fromParts("package", context.packageName, null)
    val settingsIntent = android.content.Intent(intent, uri)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(settingsIntent)
    } catch (_: android.content.ActivityNotFoundException) {
        // No settings screen available - nothing to do.
    }
}

/**
 * Deep-links to the DEVICE location settings, where the master Location toggle
 * lives.
 *
 * This is the screen the reported bug actually needed: the user turned the
 * master Location switch off and back on there. Sending them to this app's
 * permission page instead would show them a toggle they had never changed and
 * would not fix anything.
 *
 * WHY NOT `SettingsClient` / `LocationSettingsRequest` / `ResolvableApiException`?
 *
 * That API is the nicer path - it shows an in-app dialog and, on success,
 * ResQ would know immediately that Location was switched on. It was evaluated
 * and deliberately NOT used:
 *
 *  - it requires the `play-services-location` dependency, and ResQ currently has
 *    NO Google Play Services dependency at all. Adding one makes a Google account
 *    and Play Services a requirement of the location path - a real availability
 *    risk on the OEM ROMs this app must work on, where Play Services can be
 *    absent, stale or partially removed;
 *  - the requirement itself says to fall back to Android Location Settings when
 *    the API is unavailable, and that fallback is what the user needs anyway
 *    when the problem is the MASTER toggle, which is the reported case;
 *  - an in-app confirmation dialog cannot be shown from a background
 *    foreground-service context, which is where location trouble is first
 *    noticed with the screen off.
 *
 * Revisit this only if a Google Play Services dependency is adopted for another
 * reason. The automatic half of the recovery - noticing the toggle flip and
 * re-registering - does not depend on this choice at all; see
 * AndroidSensorGateway's PROVIDERS_CHANGED receiver and the ViewModel's
 * onEmergencyScreenResumed.
 *
 * Resolution falls back to this app's own settings page on OEM builds that do
 * not expose ACTION_LOCATION_SOURCE_SETTINGS (some Vivo/OriginOS variants), so
 * the button is never a dead end. It is a system settings link only: ResQ never
 * attempts to change the Location toggle itself, silently or otherwise.
 */
private fun openLocationSettings(context: Context) {
    val candidates = listOf(
        android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            .putExtra("android.provider.extra.LOCATION_SOURCE_SETTINGS", true),
        android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS),
    )
    for (intent in candidates) {
        val withTask = intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = try {
            context.startActivity(withTask)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (launched) return
    }
    openApplicationSettings(context)
}
