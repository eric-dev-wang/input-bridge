package com.ericdevwang.inputbridge.core.data.repository

import com.ericdevwang.inputbridge.core.data.model.TextState
import kotlinx.coroutines.flow.Flow

interface TextRepository {
    val state: Flow<TextState>
    suspend fun save(state: TextState): PersistenceResult
    suspend fun clear(expectedVersion: Long): ClearResult
}
