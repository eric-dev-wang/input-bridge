package com.ericdevwang.inputbridge.core.data.internal.repository

import com.ericdevwang.inputbridge.core.data.repository.ClearResult
import kotlinx.coroutines.CompletableDeferred

internal class ClearRequest(
    val expectedVersion: Long,
    override val result: CompletableDeferred<ClearResult>,
) : WriteRequest
