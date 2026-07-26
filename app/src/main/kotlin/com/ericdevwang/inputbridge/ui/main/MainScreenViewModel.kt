package com.ericdevwang.inputbridge.ui.main

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ericdevwang.inputbridge.core.data.model.TextChangeResult
import com.ericdevwang.inputbridge.core.data.model.TextState
import com.ericdevwang.inputbridge.core.data.repository.ClearResult
import com.ericdevwang.inputbridge.core.data.repository.PersistenceResult
import com.ericdevwang.inputbridge.core.data.repository.TextRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainScreenViewModel(
    private val repository: TextRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow<MainScreenUiState>(MainScreenUiState.Loading)
    private var currentTextState = TextState.initial(0L)

    val uiState = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch { observeRepositoryState() }
    }

    fun onTextChanged(value: TextFieldValue) {
        val currentContent = mutableUiState.value as? MainScreenUiState.Content ?: return

        if (value.text == currentTextState.text) {
            mutableUiState.value = currentContent.copy(textFieldValue = value)
            return
        }

        when (val result = currentTextState.changeText(value.text, clock())) {
            is TextChangeResult.Accepted -> {
                currentTextState = result.state
                mutableUiState.value = result.state.toContent(
                    textFieldValue = value,
                    persistenceMessage = currentContent.persistenceMessage,
                )
                persistSnapshot(result.state)
            }

            TextChangeResult.RejectedTooLong -> Unit
        }
    }

    fun onClear() {
        val currentContent = mutableUiState.value as? MainScreenUiState.Content ?: return
        val expectedVersion = currentTextState.version
        val cleared = currentTextState.clear(clock())
        if (cleared == currentTextState) {
            mutableUiState.value = currentContent.copy(
                textFieldValue = TextFieldValue(""),
            )
            return
        }

        currentTextState = cleared
        val clearedValue = TextFieldValue(text = "", selection = TextRange.Zero)
        mutableUiState.value = cleared.toContent(
            textFieldValue = clearedValue,
            persistenceMessage = currentContent.persistenceMessage,
        )
        persistClear(expectedVersion, cleared)
    }

    private suspend fun observeRepositoryState() {
        try {
            repository.state.collect { persistedState ->
                val isLoading = mutableUiState.value is MainScreenUiState.Loading
                if (!isLoading && persistedState.version < currentTextState.version) return@collect
                if (!isLoading &&
                    persistedState.version == currentTextState.version &&
                    persistedState.text == currentTextState.text
                ) {
                    return@collect
                }

                val currentContent = mutableUiState.value as? MainScreenUiState.Content
                showPersistedState(
                    persistedState = persistedState,
                    persistenceMessage = currentContent?.persistenceMessage,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableUiState.value = MainScreenUiState.InitializationError
        }
    }

    private fun persistClear(expectedVersion: Long, localClearedState: TextState) {
        viewModelScope.launch {
            try {
                when (repository.clear(expectedVersion)) {
                    is ClearResult.Cleared -> {
                        if (currentTextState.version == localClearedState.version) {
                            updateContent { it.copy(persistenceMessage = null) }
                        }
                    }

                    is ClearResult.VersionConflict -> reconcileClearConflict(localClearedState)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (currentTextState.version == localClearedState.version) {
                    updateContent { it.copy(persistenceMessage = PersistenceMessage.SaveFailed) }
                }
            }
        }
    }

    private suspend fun reconcileClearConflict(localClearedState: TextState) {
        try {
            val persistedState = repository.state.first()
            if (currentTextState.version == localClearedState.version) {
                showPersistedState(persistedState, persistenceMessage = null)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (currentTextState.version == localClearedState.version) {
                updateContent { it.copy(persistenceMessage = PersistenceMessage.SaveFailed) }
            }
        }
    }

    private fun showPersistedState(
        persistedState: TextState,
        persistenceMessage: PersistenceMessage?,
    ) {
        currentTextState = persistedState
        val value = TextFieldValue(
            text = persistedState.text,
            selection = TextRange(persistedState.text.length),
        )
        mutableUiState.value = persistedState.toContent(
            textFieldValue = value,
            persistenceMessage = persistenceMessage,
        )
    }

    private fun persistSnapshot(state: TextState) {
        viewModelScope.launch {
            when (repository.save(state)) {
                is PersistenceResult.Succeeded -> {
                    if (currentTextState.version == state.version) {
                        updateContent { it.copy(persistenceMessage = null) }
                    }
                }

                is PersistenceResult.Failed -> {
                    if (currentTextState.version == state.version) {
                        updateContent { it.copy(persistenceMessage = PersistenceMessage.SaveFailed) }
                    }
                }

                is PersistenceResult.Superseded -> Unit
            }
        }
    }

    private fun updateContent(transform: (MainScreenUiState.Content) -> MainScreenUiState.Content) {
        val current = mutableUiState.value as? MainScreenUiState.Content ?: return
        mutableUiState.value = transform(current)
    }
}

enum class PersistenceMessage {
    InitializationFailed,
    SaveFailed,
}

sealed interface MainScreenUiState {
    data object Loading : MainScreenUiState

    data class Content(
        val textFieldValue: TextFieldValue,
        val version: Long,
        val characterCount: Int,
        val persistenceMessage: PersistenceMessage? = null,
    ) : MainScreenUiState

    data object InitializationError : MainScreenUiState
}

private fun TextState.toContent(
    textFieldValue: TextFieldValue,
    persistenceMessage: PersistenceMessage? = null,
): MainScreenUiState.Content =
    MainScreenUiState.Content(
        textFieldValue = textFieldValue,
        version = version,
        characterCount = text.codePointCount(0, text.length),
        persistenceMessage = persistenceMessage,
    )
