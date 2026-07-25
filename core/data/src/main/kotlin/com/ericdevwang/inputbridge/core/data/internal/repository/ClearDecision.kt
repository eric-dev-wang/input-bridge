package com.ericdevwang.inputbridge.core.data.internal.repository

import com.ericdevwang.inputbridge.core.data.model.TextState

internal sealed interface ClearDecision {
    data class Persist(
        val state: TextState,
        val clearedVersion: Long,
    ) : ClearDecision

    data class Conflict(val currentVersion: Long) : ClearDecision
}
