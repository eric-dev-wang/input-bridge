package com.ericdevwang.inputbridge.core.datastore.internal

import com.ericdevwang.inputbridge.core.datastore.PersistedTextState
import com.ericdevwang.inputbridge.core.datastore.TextDataSource
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class DataStoreTextDataSource(
    private val dataStore: DataStore<Preferences>,
) : TextDataSource {
    override val state: Flow<PersistedTextState> =
        dataStore.data
            .map { preferences -> preferences.toPersistedTextState() }
            .distinctUntilChanged()

    override suspend fun saveIfNewer(state: PersistedTextState): Boolean {
        var saved = false
        dataStore.edit { preferences ->
            if (state.version > (preferences[VERSION_KEY] ?: 0L)) {
                state.writeTo(preferences)
                saved = true
            }
        }
        return saved
    }
}

private fun Preferences.toPersistedTextState(): PersistedTextState =
    PersistedTextState(
        text = this[TEXT_KEY] ?: "",
        version = this[VERSION_KEY] ?: 0L,
        updatedAt = this[UPDATED_AT_KEY] ?: 0L,
    )

private fun PersistedTextState.writeTo(preferences: MutablePreferences) {
    preferences[TEXT_KEY] = text
    preferences[VERSION_KEY] = version
    preferences[UPDATED_AT_KEY] = updatedAt
}
