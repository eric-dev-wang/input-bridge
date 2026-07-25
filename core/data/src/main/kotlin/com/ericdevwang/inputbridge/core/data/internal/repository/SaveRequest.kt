package com.ericdevwang.inputbridge.core.data.internal.repository

import com.ericdevwang.inputbridge.core.data.model.TextState
import com.ericdevwang.inputbridge.core.data.repository.PersistenceResult
import kotlinx.coroutines.CompletableDeferred

internal class SaveRequest(
    val state: TextState,
    override val result: CompletableDeferred<PersistenceResult>,
) : WriteRequest
