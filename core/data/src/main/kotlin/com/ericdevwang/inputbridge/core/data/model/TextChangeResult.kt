package com.ericdevwang.inputbridge.core.data.model

sealed interface TextChangeResult {
    data class Accepted(val state: TextState) : TextChangeResult
    data object RejectedTooLong : TextChangeResult
}
