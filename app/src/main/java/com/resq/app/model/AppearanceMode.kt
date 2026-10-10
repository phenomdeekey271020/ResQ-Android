package com.resq.app.model

enum class AppearanceMode(
    val storageValue: String,
    val label: String,
) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromStorageValue(value: String?): AppearanceMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}
