package com.alharith.ai.data

import kotlinx.coroutines.flow.MutableStateFlow

/** Resolve from authenticated server identity, never from the display name. */
object PersonalFeatures {
    val enabled = MutableStateFlow(false)
    suspend fun check() {
        enabled.value = false
        if (Prefs.sharedBrainReady) runCatching {
            enabled.value = SharedBrain.get("/api/me").getJSONObject("user").optInt("id") == 54
        }
        // Isolated emulator fixture only. Never available in the release APK.
        if (com.alharith.ai.BuildConfig.DEBUG && !Prefs.sharedBrainReady) enabled.value = true
    }
}
