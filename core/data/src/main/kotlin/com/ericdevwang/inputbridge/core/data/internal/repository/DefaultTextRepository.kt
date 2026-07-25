package com.ericdevwang.inputbridge.core.data.internal.repository

import com.ericdevwang.inputbridge.core.data.model.TextState
import com.ericdevwang.inputbridge.core.data.repository.ClearResult
import com.ericdevwang.inputbridge.core.data.repository.PersistenceResult
import com.ericdevwang.inputbridge.core.data.repository.TextRepository
import com.ericdevwang.inputbridge.core.datastore.PersistedTextState
import com.ericdevwang.inputbridge.core.datastore.TextDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DefaultTextRepository(
    private val dataSource: TextDataSource,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : TextRepository {
    private val requests = ArrayDeque<WriteRequest>()
    private val requestsMutex = Mutex()
    private val requestSignal = Channel<Unit>(capacity = Channel.CONFLATED)
    private val liveState = MutableStateFlow<TextState?>(null)

    override val state: Flow<TextState> = liveState
        .filterNotNull()
        .distinctUntilChanged()

    init {
        scope.launch { observePersistedState() }
        scope.launch { processRequests() }
    }

    override suspend fun save(state: TextState): PersistenceResult {
        val request = SaveRequest(state, CompletableDeferred())
        requestsMutex.withLock {
            val current = liveState.value
            if (current != null && state.version <= current.version) {
                request.result.complete(PersistenceResult.Superseded(state.version))
            } else {
                liveState.value = state
                enqueueSaveLocked(request)
            }
        }
        return request.result.await()
    }

    override suspend fun clear(expectedVersion: Long): ClearResult {
        val request = ClearRequest(expectedVersion, CompletableDeferred())
        enqueue(request)
        return request.result.await()
    }

    private fun enqueueSaveLocked(request: SaveRequest) {
        val last = if (requests.isEmpty()) null else requests.last()
        if (last is SaveRequest) {
            requests.removeLast()
            last.result.complete(PersistenceResult.Superseded(last.state.version))
        }
        requests.addLast(request)
        requestSignal.trySend(Unit)
    }

    private suspend fun enqueue(request: WriteRequest) {
        requestsMutex.withLock {
            requests.addLast(request)
            requestSignal.trySend(Unit)
        }
    }

    private suspend fun processRequests() {
        try {
            while (currentCoroutineContext().isActive) {
                val request = requestsMutex.withLock {
                    if (requests.isEmpty()) null else requests.removeFirst()
                }
                if (request == null) {
                    requestSignal.receive()
                    continue
                }
                process(request)
            }
        } finally {
            requestsMutex.withLock {
                requests.forEach { request -> request.result.cancel() }
                requests.clear()
            }
        }
    }

    private suspend fun process(request: WriteRequest) {
        when (request) {
            is SaveRequest -> processSave(request)
            is ClearRequest -> processClear(request)
        }
    }

    private suspend fun processSave(request: SaveRequest) {
        val result = try {
            if (dataSource.saveIfNewer(request.state.toPersistedTextState())) {
                PersistenceResult.Succeeded(request.state.version)
            } else {
                PersistenceResult.Superseded(request.state.version)
            }
        } catch (error: Exception) {
            if (error is CancellationException) {
                request.result.cancel(error)
                throw error
            }
            PersistenceResult.Failed(request.state.version)
        }
        request.result.complete(result)
    }

    private suspend fun observePersistedState() {
        dataSource.state.collect { persistedState ->
            requestsMutex.withLock {
                val current = liveState.value
                if (current == null || persistedState.version > current.version) {
                    liveState.value = persistedState.toTextState()
                }
            }
        }
    }

    private suspend fun processClear(request: ClearRequest) {
        try {
            currentLiveState()
            when (val decision = requestsMutex.withLock {
                val latest = checkNotNull(liveState.value)
                if (latest.version != request.expectedVersion) {
                    ClearDecision.Conflict(latest.version)
                } else {
                    val cleared = latest.clear(clock())
                    liveState.value = cleared
                    ClearDecision.Persist(cleared, latest.version)
                }
            }) {
                is ClearDecision.Conflict ->
                    request.result.complete(ClearResult.VersionConflict(decision.currentVersion))
                is ClearDecision.Persist -> {
                    dataSource.saveIfNewer(decision.state.toPersistedTextState())
                    request.result.complete(
                        ClearResult.Cleared(
                            clearedVersion = decision.clearedVersion,
                            newVersion = decision.state.version,
                        ),
                    )
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) {
                request.result.cancel(error)
                throw error
            }
            request.result.completeExceptionally(error)
        }
    }

    private suspend fun currentLiveState(): TextState {
        liveState.value?.let { return it }
        val persistedState = dataSource.state.first().toTextState()
        return requestsMutex.withLock {
            liveState.value ?: persistedState.also { liveState.value = it }
        }
    }
}

private fun TextState.toPersistedTextState(): PersistedTextState =
    PersistedTextState(
        text = text,
        version = version,
        updatedAt = updatedAt,
    )

private fun PersistedTextState.toTextState(): TextState =
    TextState(
        text = text,
        version = version,
        updatedAt = updatedAt,
    )
