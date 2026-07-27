package com.ericdevwang.inputbridge.core.data.internal.repository

import com.ericdevwang.inputbridge.core.data.repository.ClearResult
import com.ericdevwang.inputbridge.core.data.repository.PersistenceResult
import com.ericdevwang.inputbridge.core.data.repository.TextRepository
import com.ericdevwang.inputbridge.core.data.model.TextState
import com.ericdevwang.inputbridge.core.datastore.PersistedTextState
import com.ericdevwang.inputbridge.core.datastore.TextDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTextRepositoryTest {
    @Test
    fun savePublishesLiveStateBeforePersistenceCompletes() = runTest {
        val dataSource = FakeTextDataSource(TextState.initial(1L))
        val writeGate = CompletableDeferred<Unit>()
        dataSource.writeGate = writeGate
        val repositoryScope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler),
        )
        val repository = DefaultTextRepository(dataSource, repositoryScope)
        val save = async { repository.save(TextState("live", 1L, 2L)) }
        val liveState = async { repository.state.first { it.version == 1L } }

        runCurrent()

        assertTrue(liveState.isCompleted)
        assertEquals(TextState("live", 1L, 2L), liveState.await())
        assertFalse(save.isCompleted)

        writeGate.complete(Unit)
        advanceUntilIdle()
        repositoryScope.cancel()
    }

    @Test
    fun saveReturnsSuccessAfterDataSourceCompletes() = runTest {
        val dataSource = FakeTextDataSource(TextState("", 0L, 1L))
        val writeGate = CompletableDeferred<Unit>()
        dataSource.writeGate = writeGate
        val repositoryScope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler),
        )
        val repository = DefaultTextRepository(dataSource, repositoryScope)
        val save = async { repository.save(TextState("saved", 1L, 2L)) }

        runCurrent()

        assertFalse(save.isCompleted)
        writeGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(PersistenceResult.Succeeded(1L), save.await())
        assertEquals(listOf(TextState("saved", 1L, 2L)), dataSource.persisted)
        repositoryScope.cancel()
    }

    @Test
    fun supersededSaveCompletesWithoutHanging() = runTest {
        val dataSource = FakeTextDataSource(TextState("", 0L, 1L))
        val writeGate = CompletableDeferred<Unit>()
        dataSource.writeGate = writeGate
        val repositoryScope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler),
        )
        val repository = DefaultTextRepository(dataSource, repositoryScope)
        val first = async { repository.save(TextState("first", 1L, 2L)) }
        runCurrent()
        val second = async { repository.save(TextState("second", 2L, 3L)) }
        runCurrent()
        val third = async { repository.save(TextState("third", 3L, 4L)) }
        runCurrent()

        assertEquals(PersistenceResult.Superseded(2L), second.await())
        writeGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(PersistenceResult.Succeeded(1L), first.await())
        assertEquals(PersistenceResult.Succeeded(3L), third.await())
        assertEquals(
            listOf(
                TextState("first", 1L, 2L),
                TextState("third", 3L, 4L),
            ),
            dataSource.persisted,
        )
        repositoryScope.cancel()
    }

    @Test
    fun saveReturnsFailureWhenDataSourceWriteFails() = runTest {
        val repositoryScope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler),
        )
        val repository = DefaultTextRepository(
            dataSource = FailingTextDataSource(),
            scope = repositoryScope,
        )

        assertEquals(
            PersistenceResult.Failed(1L),
            repository.save(TextState("failed", 1L, 2L)),
        )
        repositoryScope.cancel()
    }

    @Test
    fun staleSaveIsSupersededByVersionAwareDataSource() = runTest {
        val dataSource = FakeTextDataSource(TextState("current", 2L, 2L))
        val repositoryScope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler),
        )
        val repository = DefaultTextRepository(dataSource, repositoryScope)

        assertEquals(
            PersistenceResult.Superseded(1L),
            repository.save(TextState("stale", 1L, 1L)),
        )
        assertEquals(TextState("current", 2L, 2L), dataSource.currentState)
        repositoryScope.cancel()
    }

    @Test
    fun clearReturnsClearedVersionAndNewVersion() = runTest {
        val dataSource = FakeTextDataSource(TextState("keep", 1L, 1L))
        val repositoryScope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler),
        )
        val repository = DefaultTextRepository(dataSource, repositoryScope, clock = { 2L })

        assertEquals(
            ClearResult.Cleared(clearedVersion = 1L, newVersion = 2L),
            repository.clear(expectedVersion = 1L),
        )
        assertEquals(TextState("", 2L, 2L), dataSource.currentState)
        repositoryScope.cancel()
    }

    @Test
    fun clearPublishesLiveStateBeforePersistenceCompletes() = runTest {
        val dataSource = FakeTextDataSource(TextState("keep", 1L, 1L))
        val writeGate = CompletableDeferred<Unit>()
        dataSource.writeGate = writeGate
        val repositoryScope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler),
        )
        val repository = DefaultTextRepository(dataSource, repositoryScope, clock = { 2L })
        val clear = async { repository.clear(expectedVersion = 1L) }
        val liveState = async { repository.state.first { it.version == 2L } }

        runCurrent()

        assertTrue(liveState.isCompleted)
        assertEquals(TextState("", 2L, 2L), liveState.await())
        assertFalse(clear.isCompleted)

        writeGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ClearResult.Cleared(clearedVersion = 1L, newVersion = 2L), clear.await())
        repositoryScope.cancel()
    }

    @Test
    fun olderPersistedStateDoesNotRegressLiveState() = runTest {
        val dataSource = FakeTextDataSource(TextState("persisted", 1L, 1L))
        val repositoryScope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler),
        )
        val repository = DefaultTextRepository(dataSource, repositoryScope)
        val live = TextState("live", 2L, 2L)

        assertEquals(PersistenceResult.Succeeded(2L), repository.save(live))
        dataSource.emitPersisted(TextState("old", 1L, 3L))

        assertEquals(live, repository.state.first())
        repositoryScope.cancel()
    }

    @Test
    fun staleClearReturnsVersionConflict() = runTest {
        val dataSource = FakeTextDataSource(TextState("current", 2L, 2L))
        val repositoryScope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler),
        )
        val repository = DefaultTextRepository(dataSource, repositoryScope)

        assertEquals(
            ClearResult.VersionConflict(currentVersion = 2L),
            repository.clear(expectedVersion = 1L),
        )
        assertEquals(TextState("current", 2L, 2L), dataSource.currentState)
        repositoryScope.cancel()
    }

    @Test
    fun pendingSaveIsAppliedBeforeClearAndCannotResurrectContent() = runTest {
        val dataSource = FakeTextDataSource(TextState("keep", 1L, 1L))
        val writeGate = CompletableDeferred<Unit>()
        dataSource.writeGate = writeGate
        val repositoryScope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler),
        )
        val repository = DefaultTextRepository(dataSource, repositoryScope, clock = { 3L })
        val save = async { repository.save(TextState("pending", 2L, 2L)) }
        runCurrent()
        val clear = async { repository.clear(expectedVersion = 2L) }
        runCurrent()

        assertFalse(clear.isCompleted)
        writeGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(PersistenceResult.Succeeded(2L), save.await())
        assertEquals(ClearResult.Cleared(clearedVersion = 2L, newVersion = 3L), clear.await())
        assertEquals(TextState("", 3L, 3L), dataSource.currentState)
        repositoryScope.cancel()
    }

    @Test
    fun saveAfterQueuedClearMakesClearConflictWithNewerLiveState() = runTest {
        val dataSource = FakeTextDataSource(TextState("initial", 4L, 4L))
        val writeGate = CompletableDeferred<Unit>()
        dataSource.writeGate = writeGate
        val repositoryScope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler),
        )
        val repository = DefaultTextRepository(dataSource, repositoryScope)
        val firstSave = async { repository.save(TextState("v5", 5L, 5L)) }
        runCurrent()
        val clear = async { repository.clear(expectedVersion = 5L) }
        runCurrent()
        val secondSave = async { repository.save(TextState("v6", 6L, 6L)) }
        runCurrent()

        assertFalse(clear.isCompleted)
        assertEquals(TextState("v6", 6L, 6L), repository.state.first())

        writeGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(PersistenceResult.Succeeded(5L), firstSave.await())
        assertEquals(ClearResult.VersionConflict(currentVersion = 6L), clear.await())
        assertEquals(PersistenceResult.Succeeded(6L), secondSave.await())
        assertEquals(TextState("v6", 6L, 6L), dataSource.currentState)
        repositoryScope.cancel()
    }
}

private class FakeTextDataSource(initialState: TextState) : TextDataSource {
    private val mutableState = MutableStateFlow(initialState.toPersistedTextState())
    override val state: Flow<PersistedTextState> = mutableState

    var writeGate: CompletableDeferred<Unit>? = null
    val persisted = mutableListOf<TextState>()
    val currentState: TextState
        get() = mutableState.value.toTextState()

    fun emitPersisted(state: TextState) {
        mutableState.value = state.toPersistedTextState()
    }

    override suspend fun saveIfNewer(state: PersistedTextState): Boolean {
        writeGate?.let { gate ->
            writeGate = null
            gate.await()
        }
        if (state.version <= mutableState.value.version) return false
        persisted += state.toTextState()
        mutableState.value = state
        return true
    }
}

private class FailingTextDataSource : TextDataSource {
    override val state: Flow<PersistedTextState> =
        MutableStateFlow(PersistedTextState("", 0L, 0L))

    override suspend fun saveIfNewer(state: PersistedTextState): Boolean {
        error("write failed")
    }
}

private fun TextState.toPersistedTextState(): PersistedTextState =
    PersistedTextState(text, version, updatedAt)

private fun PersistedTextState.toTextState(): TextState =
    TextState(text, version, updatedAt)
