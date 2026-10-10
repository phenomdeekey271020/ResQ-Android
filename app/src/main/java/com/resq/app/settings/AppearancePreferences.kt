package com.resq.app.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.resq.app.model.AppearanceMode

class AppearancePreferences(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var appearanceMode by mutableStateOf(readAppearance())
        private set

    fun set(mode: AppearanceMode) {
        if (appearanceMode == mode) return
        appearanceMode = mode
        preferences.edit().putString(KEY_APPEARANCE, mode.storageValue).apply()
    }

    private fun readAppearance(): AppearanceMode =
        AppearanceMode.fromStorageValue(preferences.getString(KEY_APPEARANCE, null))

    private companion object {
        const val PREFERENCES_NAME = "resq_settings"
        const val KEY_APPEARANCE = "appearance_mode"
    }
}
