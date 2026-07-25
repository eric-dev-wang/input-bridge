package com.ericdevwang.inputbridge.core.datastore.internal

import com.ericdevwang.inputbridge.core.datastore.PersistedTextState
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataStoreTextDataSourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun saveIfNewerAcceptsNewerStateAndRejectsStaleState() = runTest {
        val dataSource = DataStoreTextDataSource(
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = ::testFile,
            ),
        )

        assertTrue(dataSource.saveIfNewer(PersistedTextState("new", 2L, 20L)))
        assertFalse(dataSource.saveIfNewer(PersistedTextState("stale", 1L, 10L)))
        assertEquals(PersistedTextState("new", 2L, 20L), dataSource.state.first())
    }

    @Test
    fun saveIfNewerPersistsClearedState() = runTest {
        val dataSource = DataStoreTextDataSource(
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = ::testFile,
            ),
        )
        dataSource.saveIfNewer(PersistedTextState("keep", 3L, 30L))

        assertTrue(dataSource.saveIfNewer(PersistedTextState("", 4L, 40L)))
        assertEquals(PersistedTextState("", 4L, 40L), dataSource.state.first())
    }

    private fun testFile(): File =
        File(context.cacheDir, "input-bridge-data-source-${UUID.randomUUID()}.preferences_pb")
}
