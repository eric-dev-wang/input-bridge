package com.ericdevwang.inputbridge.core.data.repository

sealed interface ClearResult {
    data class Cleared(
        val clearedVersion: Long,
        val newVersion: Long,
    ) : ClearResult

    data class VersionConflict(val currentVersion: Long) : ClearResult
}
