package com.alharith.ai.data

import kotlinx.coroutines.flow.MutableStateFlow

/** Features available to everyone; stored data remains scoped to the current account. */
object PersonalFeatures {
    val enabled = MutableStateFlow(true)
    suspend fun check() { enabled.value = true }
}
