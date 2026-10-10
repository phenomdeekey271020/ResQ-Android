package com.resq.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.resq.app.alert.AlertSoundOwner
import com.resq.app.contacts.SharedPreferencesEmergencyContactsStore
import com.resq.app.contacts.SystemCallLauncher
import com.resq.app.escalation.EscalationCoordinatorHolder
import com.resq.app.data.remote.EmergencyResourcesApi
import com.resq.app.data.remote.RemoteEmergencyResourceRepository
import com.resq.app.model.AppearanceMode
import com.resq.app.protection.ForegroundServiceProtectionController
import com.resq.app.protection.ProtectionEngineHolder
import com.resq.app.settings.AppearancePreferences
import com.resq.app.ui.ResQApp
import com.resq.app.viewmodel.ResQViewModel

class MainActivity : ComponentActivity() {

    private lateinit var appearancePreferences: AppearancePreferences

/**
     * Held lazily rather than created in onCreate, so [onResume] can reach the
     * SAME instance the composition uses. Building a second ViewModel would give
     * it its own listener registration and its own copies of the state, and the
     * permission refresh would be written into an object nobody renders.
     */
    private val viewModel: ResQViewModel by lazy {
        ViewModelProvider(this, viewModelFactory)[ResQViewModel::class.java]
    }

    private val viewModelFactory = viewModelFactory {
        initializer {
            ResQViewModel(
                sensorGateway = null,
                engine = ProtectionEngineHolder.engine(applicationContext),
                protectionController = ForegroundServiceProtectionController(applicationContext),
                // Nearby-resource discovery against the production ResQ backend.
                // BuildConfig.BACKEND_BASE_URL comes from gradle.properties
                // (resq.backend.url) and defaults to the deployed Render HTTPS
                // backend. MainActivity logs it below so the address actually
                // in use is visible in Logcat.
                resourceRepository = RemoteEmergencyResourceRepository(
                    EmergencyResourcesApi(BuildConfig.BACKEND_BASE_URL),
                ),
                // Stage B/C audible alert: alarm tone plus TextToSpeech. Built here
                // because it needs a Context; released by the ViewModel when it is
                // cleared, so the speech engine is never left running.
                alertAnnouncer = AlertSoundOwner.announcer(applicationContext),
                // Stage D: the trusted contacts and the user's escalation consent,
                // stored locally, plus the injected system-dialer launcher.
                contactsStore = SharedPreferencesEmergencyContactsStore(applicationContext),
                callLauncher = SystemCallLauncher(applicationContext),
                // The alert text and the follow-up contact call are dispatched by
                // this process-wide coordinator, NOT by the ViewModel. The
                // foreground service creates it in its own onCreate, so the
                // escalation survives the Activity being destroyed; the ViewModel
                // only reads its outcome to render truthful copy.
                escalationCoordinator = EscalationCoordinatorHolder.coordinator(
                    applicationContext,
                ),
            )
        }
    }

    /**
     * The Activity is the one place Android allows a background-started call UI
     * to actually appear, so the CALL_PHONE grant is re-read here rather than
     * only at construction: the user may have granted or revoked it in system
     * settings while the app was away, and a stale value would make the Settings
     * screen misreport what an escalation would do.
     */
    override fun onResume() {
        super.onResume()
        viewModel.onCallPermissionResult(
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE,
            ) == PackageManager.PERMISSION_GRANTED,
        )
        // Same reasoning for SEND_SMS: the user may have granted or revoked it in
        // system settings while the app was away, and the Settings screen must
        // report what an escalation would actually do right now.
        viewModel.onSmsPermissionResult(
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.SEND_SMS,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The one value that decides whether resource discovery can work from this
        // device at all, so it is worth seeing in Logcat when checking an installed
        // APK. It holds no credential, and coordinates, requests and responses are
        // never logged.
        Log.i(LogTag, "Backend base URL: ${BuildConfig.BACKEND_BASE_URL}")
        enableEdgeToEdge()
        appearancePreferences = AppearancePreferences(this)
        setContent {
            ResQRoot(
                appearancePreferences = appearancePreferences,
                viewModel = viewModel,
            )
        }
    }

    private companion object {
        const val LogTag = "ResQBackend"
    }
}

@Composable
private fun ResQRoot(
    appearancePreferences: AppearancePreferences,
    viewModel: ResQViewModel,
) {
    val appearance = appearancePreferences.appearanceMode
    val darkTheme = when (appearance) {
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
    }
    ResQApp(
        viewModel = viewModel,
        appearance = appearance,
        onAppearanceChange = appearancePreferences::set,
        darkTheme = darkTheme,
    )
}
