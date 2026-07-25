package com.ericdevwang.inputbridge.core.data.internal.repository

import kotlinx.coroutines.CompletableDeferred

internal sealed interface WriteRequest {
    val result: CompletableDeferred<*>
}
