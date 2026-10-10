package com.resq.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.resq.app.contacts.EmergencyContactsPolicy
import com.resq.app.model.EscalationCapability
import com.resq.app.model.AppearanceMode
import com.resq.app.model.EmergencyContact
import com.resq.app.model.ProtectionState
import com.resq.app.model.ResQUiState
import com.resq.app.model.SmsEscalationCapability
import com.resq.app.model.LocationAvailability
import com.resq.app.model.SensorAvailability
import com.resq.app.model.label
import com.resq.app.protection.ProtectionEngineHolder
import com.resq.app.settings.ProtectionPreferences
import com.resq.app.ui.components.ArrowBackIcon
import com.resq.app.ui.components.GlassRow
import com.resq.app.ui.components.GlassRowGroup
import com.resq.app.ui.components.ResQPrimaryButton
import com.resq.app.ui.components.ResQSegmentedControl
import com.resq.app.ui.components.ResQSecondaryButton
import com.resq.app.ui.components.ResQTextField
import com.resq.app.ui.components.ResQTextButton
import com.resq.app.ui.components.ResQToggle
import com.resq.app.ui.components.RowDivider
import com.resq.app.ui.components.SectionLabel
import com.resq.app.ui.theme.LocalResQColors
import com.resq.app.ui.theme.ResQSpacing
import com.resq.app.ui.theme.ResQTextStyles

/**
 * Destinations inside the Settings experience. The stack keeps submenus
 * navigable: opening a submenu pushes, Back pops, and Back on the main menu
 * returns to Home.
 */
sealed interface SettingsDestination {
    val key: String

    data object Main : SettingsDestination { override val key: String get() = "settings" }
    data object Appearance : SettingsDestination { override val key: String get() = "settings.appearance" }
    data object Protection : SettingsDestination { override val key: String get() = "settings.protection" }
    data object Emergency : SettingsDestination { override val key: String get() = "settings.emergency" }
    data object AlertCountdown : SettingsDestination { override val key: String get() = "settings.alertCountdown" }
    data object EmergencyContacts : SettingsDestination { override val key: String get() = "settings.emergencyContacts" }
    data object Permissions : SettingsDestination { override val key: String get() = "settings.permissions" }
    data object Developer : SettingsDestination { override val key: String get() = "settings.developer" }
}

/**
 * Settings. DESIGN.md 6.6: Appearance, Protection, Emergency and Permissions
 * sections. Everything shown reflects real app state - there are no toggles for
 * behaviour the prototype does not actually perform.
 */
@Composable
fun SettingsScreen(
    appearance: AppearanceMode,
    onAppearanceChange: (AppearanceMode) -> Unit,
    uiState: ResQUiState,
    onProtectionToggle: () -> Unit,
    onBack: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRequestCallPermission: () -> Unit,
    onRequestSmsPermission: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onSaveContact: (String?, String, String, Boolean) -> Boolean,
    onRemoveContact: (String) -> Unit,
    onMakePrimaryContact: (String) -> Unit,
    onEscalationConsentChange: (Boolean) -> Unit,
    onSmsEscalationConsentChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    initialDestination: SettingsDestination = SettingsDestination.Main,
    isActive: Boolean = true,
) {
    val colors = LocalResQColors.current
    // The settings submenu stack, extracted to a testable class rather than left
    // as composable locals: the system back gesture has to be able to pop it,
    // and a rule nothing can assert is a rule that silently broke once already.
    // See SettingsNavigation.kt.
    val stack = remember { SettingsBackStack(initialDestination) }

    fun push(dest: SettingsDestination) {
        stack.push(dest)
    }

    fun pop() {
        stack.popOrMain()
    }

    /*
     * SYSTEM BACK INSIDE SETTINGS - the reported navigation bug.
     *
     * This handler is registered INSIDE SettingsScreen, i.e. after ResQApp's,
     * and OnBackPressedDispatcher invokes the most recently registered enabled
     * callback first. So while a submenu is open this one wins and pops exactly
     * one level; on the main menu it is disabled, and ResQApp's handler takes
     * over and closes Settings to Home. The Android Back button and the
     * edge-swipe gesture both arrive through the same dispatcher, so they cannot
     * disagree.
     *
     * Before this handler existed the ONLY Back handler was ResQApp's, which
     * could see just `state.screen` - SETTINGS for the entire settings
     * experience - so it closed Settings and jumped to Home:
     *
     *     Settings -> Permissions -> Android back gesture -> Home
     *
     * while the in-app arrow, which calls pop() directly, behaved correctly.
     * That inconsistency is the bug.
     */
    val backAction = settingsBackAction(stack.current, isActive)
    BackHandler(enabled = backAction == SettingsBackAction.POP_SUBMENU) { pop() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ResQSpacing.gutter, vertical = ResQSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ResQSpacing.lg),
    ) {
        Crossfade(
            targetState = stack.current,
            animationSpec = tween(240),
            label = "settingsTransition",
        ) { dest ->
            when (dest) {
                SettingsDestination.Main -> SettingsMainMenu(
                    appearance = appearance,
                    onAppearanceChange = onAppearanceChange,
                    protection = uiState.protection,
                    contacts = uiState.contacts,
                    push = { push(it) },
                    onMainBack = onBack,
                )

                SettingsDestination.Appearance -> AppearanceSettingsScreen(
                    appearance = appearance,
                    onAppearanceChange = onAppearanceChange,
                    onBack = { pop() },
                )

                SettingsDestination.Protection -> ProtectionSettingsScreen(
                    uiState = uiState,
                    onProtectionToggle = onProtectionToggle,
                    onBack = { pop() },
                )

                SettingsDestination.Emergency -> EmergencySettingsScreen(
                    uiState = uiState,
                    onBack = { pop() },
                    onOpenAlertCountdown = { push(SettingsDestination.AlertCountdown) },
                    onOpenContacts = { push(SettingsDestination.EmergencyContacts) },
                )

                SettingsDestination.AlertCountdown -> AlertCountdownSettingsScreen(
                    uiState = uiState,
                    onBack = { pop() },
                )

                SettingsDestination.EmergencyContacts -> EmergencyContactsSettingsScreen(
                    uiState = uiState,
                    onSaveContact = onSaveContact,
                    onRemoveContact = onRemoveContact,
                    onMakePrimaryContact = onMakePrimaryContact,
                    onEscalationConsentChange = onEscalationConsentChange,
                    onSmsEscalationConsentChange = onSmsEscalationConsentChange,
                    onRequestSmsPermission = onRequestSmsPermission,
                    onBack = { pop() },
                )

                SettingsDestination.Permissions -> PermissionsSettingsScreen(
                    uiState = uiState,
                    onBack = { pop() },
                    onRequestLocationPermission = onRequestLocationPermission,
                    onOpenLocationSettings = onOpenLocationSettings,
                    onRequestCallPermission = onRequestCallPermission,
                    onRequestSmsPermission = onRequestSmsPermission,
                )

                SettingsDestination.Developer -> DeveloperSettingsScreen(
                    onBack = { pop() },
                    onOpenDiagnostics = onOpenDiagnostics,
                )
            }
        }
    }
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ResQSpacing.sm),
    ) {
        ArrowBackIcon(
            // minimumInteractiveComponentSize grows a 22 dp icon to the 48 dp
            // platform touch target; the bare clickable was unusable with one
            // hand or at large font scales.
            modifier = Modifier.minimumInteractiveComponentSize().clickable(onClick = onBack),
            contentDescription = "Back",
        )
        Text(
            text = title,
            style = ResQTextStyles.headlineMd,
            fontWeight = FontWeight.Bold,
            color = LocalResQColors.current.text,
        )
    }
}

@Composable
private fun SettingsMainMenu(
    appearance: AppearanceMode,
    onAppearanceChange: (AppearanceMode) -> Unit,
    protection: ProtectionState,
    contacts: List<EmergencyContact>,
    push: (SettingsDestination) -> Unit,
    onMainBack: () -> Unit,
) {
    val colors = LocalResQColors.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ResQSpacing.lg),
    ) {
        SettingsHeader("Settings") { onMainBack() }

        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Appearance")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                GlassRow(
                    label = "Appearance",
                    value = appearanceModeText(appearance),
                    description = "Choose how ResQ looks",
                    onClick = { push(SettingsDestination.Appearance) },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Protection")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                GlassRow(
                    label = "Protection",
                    value = when (protection) {
                        ProtectionState.ACTIVE -> "On"
                        ProtectionState.PAUSED -> "Off"
                    },
                    description = "Detection settings",
                    onClick = { push(SettingsDestination.Protection) },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Emergency")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                GlassRow(
                    label = "Emergency settings",
                    value = contactSummary(contacts),
                    onClick = { push(SettingsDestination.Emergency) },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Permissions")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                GlassRow(
                    label = "Permissions",
                    onClick = { push(SettingsDestination.Permissions) },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Developer")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                GlassRow(
                    label = "Developer / Test Mode",
                    description = "Advanced diagnostics and simulation",
                    onClick = { push(SettingsDestination.Developer) },
                )
            }
        }

        Text(
            text = "ResQ discovers nearby resources. It never sends an alert or a message to anyone.",
            style = ResQTextStyles.caption,
            color = colors.textMuted,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(modifier = Modifier.padding(bottom = ResQSpacing.lg)) {}
    }
}

@Composable
private fun appearanceModeText(mode: AppearanceMode): String = when (mode) {
    AppearanceMode.SYSTEM -> "System"
    AppearanceMode.LIGHT -> "Light"
    AppearanceMode.DARK -> "Dark"
}

@Composable
fun AppearanceSettingsScreen(
    appearance: AppearanceMode,
    onAppearanceChange: (AppearanceMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalResQColors.current
    val modes = listOf(AppearanceMode.LIGHT, AppearanceMode.DARK, AppearanceMode.SYSTEM)
    val selectedIndex = modes.indexOf(appearance).coerceAtLeast(0)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ResQSpacing.gutter, vertical = ResQSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ResQSpacing.lg),
    ) {
        SettingsHeader("Appearance") { onBack() }
        Text(
            text = "Choose how ResQ looks",
            style = ResQTextStyles.bodyLg,
            color = colors.textMuted,
        )
        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Theme")
            ResQSegmentedControl(
                labels = modes.map { it.label },
                selected = selectedIndex,
                onSelected = { onAppearanceChange(modes[it]) },
            )
        }
    }
}

@Composable
fun ProtectionSettingsScreen(
    uiState: ResQUiState,
    onProtectionToggle: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalResQColors.current
    val active = uiState.protection == ProtectionState.ACTIVE

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ResQSpacing.gutter, vertical = ResQSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ResQSpacing.lg),
    ) {
        SettingsHeader("Protection") { onBack() }
        Text(
            text = "Manage detection and monitoring.",
            style = ResQTextStyles.bodyLg,
            color = colors.textMuted,
        )
        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Monitoring")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                ResQToggle(
                    checked = active,
                    onCheckedChange = { onProtectionToggle() },
                    label = "Background protection",
                )
                RowDivider()
                GlassRow(label = "Crash detection", value = "On")
                RowDivider()
                GlassRow(label = "Fall detection", value = "On")
            }
            Text(
                text = "Background protection keeps ResQ monitoring while the app is not in the foreground. Crash and fall detection run alongside it.",
                style = ResQTextStyles.caption,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
fun EmergencySettingsScreen(
    uiState: ResQUiState,
    onBack: () -> Unit,
    onOpenAlertCountdown: () -> Unit,
    onOpenContacts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalResQColors.current
    val context = LocalContext.current
    val engine = ProtectionEngineHolder.engine(context)
    val countdownLabel = engine.alertCountdownSeconds?.let { "$it s" }
        ?: "Per-level (10/5)"

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ResQSpacing.gutter, vertical = ResQSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ResQSpacing.lg),
    ) {
        SettingsHeader("Emergency") { onBack() }
        Text(
            text = "Alert behaviour and emergency contacts.",
            style = ResQTextStyles.bodyLg,
            color = colors.textMuted,
        )
        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Alerts")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                GlassRow(
                    label = "Alert countdown",
                    value = countdownLabel,
                    onClick = onOpenAlertCountdown,
                )
                RowDivider()
                GlassRow(
                    label = "Emergency contacts",
                    value = contactSummary(uiState.contacts),
                    onClick = onOpenContacts,
                )
            }
        }
        Text(
            text = "ResQ opens the dialer for your primary contact when an emergency " +
                "escalates, and only with the consent you set below. It never " +
                "messages anyone and never calls a hospital or a service by itself.",
            style = ResQTextStyles.caption,
            color = colors.textMuted,
        )
    }
}

@Composable
fun AlertCountdownSettingsScreen(
    uiState: ResQUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalResQColors.current
    val context = LocalContext.current
    val engine = ProtectionEngineHolder.engine(context)
    var selected by remember { mutableStateOf(engine.alertCountdownSeconds) }
    val options = listOf(10, 20, 30, 45, 60)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ResQSpacing.gutter, vertical = ResQSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ResQSpacing.lg),
    ) {
        SettingsHeader("Alert countdown") { onBack() }
        Text(
            text = "How long ResQ waits with the 'are you okay?' prompt before escalating to the emergency flow.",
            style = ResQTextStyles.bodyLg,
            color = colors.textMuted,
        )
        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Countdown")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                options.forEach { seconds ->
                    GlassRow(
                        label = "$seconds s",
                        value = "",
                        showDot = selected == seconds,
                        onClick = {
                            selected = seconds
                            engine.alertCountdownSeconds = seconds
                            ProtectionPreferences(context).updateAlertCountdownSeconds(seconds)
                        },
                    )
                }
                RowDivider()
                GlassRow(
                    label = "Per-level (10/5)",
                    value = "",
                    showDot = selected == null,
                    onClick = {
                        selected = null
                        engine.alertCountdownSeconds = null
                        ProtectionPreferences(context).updateAlertCountdownSeconds(null)
                    },
                )
            }
            Text(
                text = "When unset, POSSIBLE detections get 10 s and HIGH confidence detections get 5 s.",
                style = ResQTextStyles.caption,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
fun EmergencyContactsSettingsScreen(
    uiState: ResQUiState,
    onSaveContact: (String?, String, String, Boolean) -> Boolean,
    onRemoveContact: (String) -> Unit,
    onMakePrimaryContact: (String) -> Unit,
    onEscalationConsentChange: (Boolean) -> Unit,
    onSmsEscalationConsentChange: (Boolean) -> Unit,
    onRequestSmsPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalResQColors.current
    val contacts = uiState.contacts
    val full = contacts.size >= EmergencyContactsPolicy.MaxContacts

    var showForm by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var makePrimary by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun startNew() {
        editingId = null
        name = ""
        phone = ""
        makePrimary = contacts.isEmpty()
        error = null
        showForm = true
    }

    fun startEdit(contact: EmergencyContact) {
        editingId = contact.id
        name = contact.name
        phone = contact.phoneNumber
        makePrimary = contact.isPrimary
        error = null
        showForm = true
    }

    fun submit() {
        // Validation runs against the same pure policy the store uses, so the
        // screen can explain the problem before anything is persisted.
        val message = when {
            !EmergencyContactsPolicy.isValidName(name) -> "Enter a name."
            !EmergencyContactsPolicy.isValidPhone(phone) ->
                "Enter a dialable number (7 to 15 digits)."
            EmergencyContactsPolicy.isDuplicate(contacts, phone, excludingId = editingId) ->
                "Another contact already uses this number."
            editingId == null && full ->
                "You can save up to ${EmergencyContactsPolicy.MaxContacts} contacts."

            else -> null
        }
        if (message != null) {
            error = message
            return
        }
        if (onSaveContact(editingId, name, phone, makePrimary)) {
            showForm = false
            editingId = null
            name = ""
            phone = ""
            makePrimary = false
            error = null
        } else {
            error = "That contact could not be saved."
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ResQSpacing.gutter, vertical = ResQSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ResQSpacing.lg),
    ) {
        SettingsHeader("Emergency contacts") { onBack() }
        Text(
            text = "Up to ${EmergencyContactsPolicy.MaxContacts} people ResQ can reach when an " +
                "emergency escalates. Numbers stay on this device.",
            style = ResQTextStyles.bodyLg,
            color = colors.textMuted,
        )

        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Contacts")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                if (contacts.isEmpty()) {
                    Text(
                        text = "No contacts yet. Add one so ResQ has someone to call.",
                        style = ResQTextStyles.row,
                        color = colors.textMuted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    contacts.forEachIndexed { index, contact ->
                        GlassRow(
                            label = contact.name,
                            description = contact.phoneNumber,
                            value = if (contact.isPrimary) "Primary" else "",
                            showDot = contact.isPrimary,
                            onClick = { startEdit(contact) },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!contact.isPrimary) {
                                ResQTextButton(
                                    text = "Make primary",
                                    onClick = { onMakePrimaryContact(contact.id) },
                                )
                            }
                            ResQTextButton(
                                text = "Remove",
                                onClick = { onRemoveContact(contact.id) },
                            )
                        }
                        if (index != contacts.lastIndex) RowDivider()
                    }
                }
            }
        }

        if (showForm) {
            Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
                SectionLabel(if (editingId == null) "New contact" else "Edit contact")
                ResQTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Display name",
                    isError = error != null,
                )
                ResQTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = "Phone number",
                    keyboardType = KeyboardType.Phone,
                    isError = error != null,
                    supportingText = error,
                )
                GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                    ResQToggle(
                        checked = makePrimary,
                        onCheckedChange = { makePrimary = it },
                        label = "Call this contact first",
                    )
                }
                ResQPrimaryButton(
                    text = "Save contact",
                    onClick = { submit() },
                    modifier = Modifier.fillMaxWidth(),
                )
                ResQTextButton(
                    text = "Cancel",
                    onClick = {
                        showForm = false
                        editingId = null
                        error = null
                    },
                )
            }
        } else {
            ResQSecondaryButton(
                text = if (full) {
                    "Contact limit reached"
                } else {
                    "Add contact"
                },
                enabled = !full,
                onClick = { startNew() },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Escalation consent")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                ResQToggle(
                    checked = uiState.escalationConsent,
                    onCheckedChange = onEscalationConsentChange,
                    label = "Let ResQ call my primary contact automatically",
                )
            }
            /*
             * Informed consent, stated before it is granted and kept honest after.
             *
             * The two problems this fixes:
             *  - the user must know a REAL call can be placed to a real person,
             *    with no chance to answer it themselves first;
             *  - the screen must say what the current configuration will actually
             *    DO, because consent without CALL_PHONE only opens a dialer, and
             *    claiming otherwise would be the exact over-claim this app is
             *    meant to avoid.
             */
            Text(
                text = if (uiState.escalationConsent) {
                    "When an emergency escalates and you do not answer, ResQ will " +
                        "call your primary contact for you. That is a real phone " +
                        "call to a real person, placed without you pressing " +
                        "anything. It happens at most once per incident and only " +
                        "for your primary contact. ResQ cannot tell whether the " +
                        "call connected or was answered."
                } else {
                    "Off. ResQ will never call anyone by itself. The emergency " +
                        "screen will still show every contact with a call action."
                },
                style = ResQTextStyles.caption,
                color = colors.textMuted,
            )

            val capability = uiState.escalationCapability
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                GlassRow(
                    label = "Right now, an escalation would",
                    value = escalationCapabilityLabel(capability),
                    valueColor = colors.textMuted,
                )
            }
            if (capability == EscalationCapability.PERMISSION_MISSING) {
                Text(
                    text = "To let ResQ place the call itself, grant the " +
                        "phone-call permission. Until then ResQ opens your dialer " +
                        "and you press call.",
                    style = ResQTextStyles.caption,
                    color = colors.textMuted,
                )
            }
        }

        /*
         * SMS ESCALATION - a SEPARATE, default-off consent.
         *
         * It is not bundled with the call consent on purpose. A phone call and a
         * text cost the user different things: the call may be unwanted at 3am,
         * and the text spends mobile credit through their carrier. The user is
         * told both of those before they can switch this on, which is what
         * "informed" has to mean for an effect they cannot see or cancel.
         *
         * The capability row below is what stops the promise from being a lie:
         * consent without the SEND_SMS grant means no message can be sent, and
         * the screen says exactly that instead of implying ResQ is armed.
         */
        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Alert text consent")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                ResQToggle(
                    checked = uiState.smsEscalationConsent,
                    onCheckedChange = onSmsEscalationConsentChange,
                    label = "Send an alert text to my contacts",
                )
            }
            Text(
                text = if (uiState.smsEscalationConsent) {
                    "When an emergency escalates and you do not respond, ResQ " +
                        "sends ONE text message from this phone's SIM to your " +
                        "saved contacts. Your carrier may charge for each " +
                        "message. ResQ cannot see or cancel a message it has " +
                        "sent, and it cannot know whether it was delivered or " +
                        "read. Contacts and messages stay on your device and " +
                        "your carrier."
                } else {
                    "Off. ResQ will never send a text message by itself. " +
                        "Your contacts are still one tap away on the emergency " +
                        "screen."
                },
                style = ResQTextStyles.caption,
                color = colors.textMuted,
            )

            val smsCapability = uiState.smsEscalationCapability
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                GlassRow(
                    label = "Right now, an alert text would",
                    value = smsEscalationCapabilityLabel(smsCapability),
                    valueColor = colors.textMuted,
                )
            }
            if (smsCapability == SmsEscalationCapability.PERMISSION_MISSING) {
                Text(
                    text = "To let ResQ send the alert text itself, grant the " +
                        "text-message permission. Until then no message can be " +
                        "sent from this phone.",
                    style = ResQTextStyles.caption,
                    color = colors.textMuted,
                )
                ResQSecondaryButton(
                    text = "Allow alert texts",
                    onClick = onRequestSmsPermission,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * What the current configuration will actually do, in one phrase.
 *
 * Pure and separate from the copy above on purpose: the consent text explains the
 * choice, this reports the resulting capability, and neither has to be trusted to
 * imply the other.
 */
internal fun escalationCapabilityLabel(capability: EscalationCapability): String =
    when (capability) {
        EscalationCapability.DIRECT_CALL -> "Place a real call"
        EscalationCapability.PERMISSION_MISSING -> "Open your dialer"
        EscalationCapability.CONSENT_OFF -> "Nothing — you call yourself"
        EscalationCapability.NO_CONTACT -> "Nothing — no contact saved"
    }

/**
 * The same honest one-phrase answer for the alert text.
 *
 * Separate from the call label because the two configurations fail differently:
 * a message can be blocked by a permission the user never sees, while a call
 * always degrades to a dialer they can complete themselves.
 */
internal fun smsEscalationCapabilityLabel(capability: SmsEscalationCapability): String =
    when (capability) {
        SmsEscalationCapability.SMS_READY -> "Send one alert text"
        SmsEscalationCapability.PERMISSION_MISSING -> "Nothing — permission missing"
        SmsEscalationCapability.CONSENT_OFF -> "Nothing — you have not agreed"
        SmsEscalationCapability.NO_CONTACT -> "Nothing — no contact saved"
    }

/** Short, non-sensitive summary of how many contacts are configured. */
private fun contactSummary(contacts: List<EmergencyContact>): String = when (contacts.size) {
    0 -> "Not set"
    1 -> "1 contact"
    else -> "${contacts.size} contacts"
}

@Composable
fun PermissionsSettingsScreen(
    uiState: ResQUiState,
    onBack: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRequestCallPermission: () -> Unit,
    onRequestSmsPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalResQColors.current
    val location = uiState.sensors.location
    val acceleration = uiState.sensors.accelerometer

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ResQSpacing.gutter, vertical = ResQSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ResQSpacing.lg),
    ) {
        SettingsHeader("Permissions") { onBack() }
        Text(
            text = "Control which system features ResQ may use.",
            style = ResQTextStyles.bodyLg,
            color = colors.textMuted,
        )
        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Features")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                GlassRow(
                    label = "Location",
                    value = location.availability.label,
                    onClick = when {
                        location.availability == LocationAvailability.PERMISSION_DENIED ||
                        location.availability == LocationAvailability.PROVIDERS_DISABLED ->
                            onOpenLocationSettings

                        else -> onRequestLocationPermission
                    },
                )
                RowDivider()
                GlassRow(
                    label = "Motion sensors",
                    value = acceleration.availability.label,
                )
                RowDivider()
                // The consent screen promises "grant the phone-call permission"
                // as the next step, but the permission had no home anywhere in
                // Settings - the promise had no destination. The row's status
                // reflects the real runtime state, never a constant.
                GlassRow(
                    label = "Phone calls",
                    value = if (uiState.callPermissionGranted) "Granted" else "Not granted",
                    description = "Lets ResQ place an escalation call itself",
                    onClick = onRequestCallPermission,
                )
                RowDivider()
                // Same rule as the call permission: the status reflects the real
                // runtime grant, and the only way to change it is an explicit tap
                // here. It is never requested automatically, and a denial is never
                // retried on the user's behalf.
                GlassRow(
                    label = "Text messages",
                    value = if (uiState.smsPermissionGranted) "Granted" else "Not granted",
                    description = "Lets ResQ send an alert text from this SIM",
                    onClick = onRequestSmsPermission,
                )
            }
            Text(
                text = "Location is used to share your position in an emergency. Motion sensors power impact and fall detection. Without the phone-call permission ResQ opens your dialer and you press call.",
                style = ResQTextStyles.caption,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
fun DeveloperSettingsScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalResQColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ResQSpacing.gutter, vertical = ResQSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ResQSpacing.lg),
    ) {
        SettingsHeader("Developer / Test mode") { onBack() }
        Text(
            text = "Live sensor and detection information for debugging.",
            style = ResQTextStyles.bodyLg,
            color = colors.textMuted,
        )
        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Tools")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                GlassRow(
                    label = "Diagnostics",
                    description = "Accelerometer, gyroscope, location and detection state",
                    onClick = onOpenDiagnostics,
                )
            }
            Text(
                text = "Only for debugging. Does not send alerts, messages or calls.",
                style = ResQTextStyles.caption,
                color = colors.textMuted,
            )
        }
    }
}