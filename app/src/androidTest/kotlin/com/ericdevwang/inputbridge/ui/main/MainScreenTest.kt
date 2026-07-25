package com.ericdevwang.inputbridge.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.ericdevwang.inputbridge.core.data.model.MAX_TEXT_CODE_POINTS
import com.ericdevwang.inputbridge.core.data.model.TextChangeResult
import com.ericdevwang.inputbridge.core.data.model.TextState
import com.ericdevwang.inputbridge.core.designsystem.InputBridgeTheme
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun inputUpdatesTextCharacterCountAndVersion() {
        setScreen(TextState("", 0L, 1L))

        composeTestRule.onNodeWithTag(INPUT_TEXT).performTextInput("A😀")

        composeTestRule.onNodeWithTag(INPUT_TEXT).assertTextEquals("A😀")
        composeTestRule.onNodeWithTag(CHARACTER_COUNT).assertTextEquals("Characters: 2")
        composeTestRule.onNodeWithTag(VERSION_TEXT).assertTextEquals("Version: 1")
    }

    @Test
    fun clearButtonClearsText() {
        setScreen(TextState("hello", 1L, 1L))

        composeTestRule.onNodeWithTag(CLEAR_BUTTON).performClick()

        composeTestRule.onNodeWithTag(INPUT_TEXT).assertTextEquals("")
        composeTestRule.onNodeWithTag(CHARACTER_COUNT).assertTextEquals("Characters: 0")
        composeTestRule.onNodeWithTag(VERSION_TEXT).assertTextEquals("Version: 2")
    }

    @Test
    fun loadingDisablesInputAndClearButton() {
        setStaticScreen(MainScreenUiState.Loading)

        composeTestRule.onNodeWithTag(INPUT_TEXT).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(CLEAR_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun emptyTextKeepsClearButtonEnabled() {
        setScreen(TextState("", 0L, 1L))

        composeTestRule.onNodeWithTag(CLEAR_BUTTON).assertIsEnabled()
        composeTestRule.onNodeWithTag(CLEAR_BUTTON).performClick()
        composeTestRule.onNodeWithTag(VERSION_TEXT).assertTextEquals("Version: 0")
    }

    @Test
    fun rejectedOverLimitEditRestoresPreviousText() {
        setScreen(TextState("keep", 1L, 1L))

        composeTestRule.onNodeWithTag(INPUT_TEXT)
            .performTextInput("a".repeat(MAX_TEXT_CODE_POINTS + 1))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(INPUT_TEXT).assertTextEquals("keep")
    }

    @Test
    fun persistenceErrorIsRenderedWithoutBlockingInput() {
        setScreen(TextState("", 0L, 1L), persistenceFailure = true)

        composeTestRule.onNodeWithTag(INPUT_TEXT).performTextInput("saved")

        composeTestRule.onNodeWithTag(PERSISTENCE_ERROR)
            .assertTextEquals("Text could not be saved.")
        composeTestRule.onNodeWithTag(INPUT_TEXT).assertIsEnabled()
    }

    private fun setScreen(initialState: TextState, persistenceFailure: Boolean = false) {
        composeTestRule.setContent {
            var state by remember { mutableStateOf(initialState) }
            var editorValue by remember {
                mutableStateOf(
                    TextFieldValue(
                        text = initialState.text,
                        selection = TextRange(initialState.text.length),
                    ),
                )
            }
            var persistenceMessage by remember { mutableStateOf<PersistenceMessage?>(null) }

            InputBridgeTheme {
                MainScreenContent(
                    uiState = state.toUiState(editorValue, persistenceMessage),
                    onTextChanged = { newValue ->
                        when (val result = state.changeText(newValue.text, state.updatedAt + 1L)) {
                            is TextChangeResult.Accepted -> {
                                if (persistenceFailure) {
                                    persistenceMessage = PersistenceMessage.SaveFailed
                                } else {
                                    state = result.state
                                    editorValue = newValue
                                    persistenceMessage = null
                                }
                            }

                            TextChangeResult.RejectedTooLong -> Unit
                        }
                    },
                    onClear = {
                        if (persistenceFailure) {
                            persistenceMessage = PersistenceMessage.SaveFailed
                        } else {
                            state = state.clear(state.updatedAt + 1L)
                            editorValue = TextFieldValue()
                            persistenceMessage = null
                        }
                    },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun setStaticScreen(state: MainScreenUiState) {
        composeTestRule.setContent {
            InputBridgeTheme {
                MainScreenContent(
                    uiState = state,
                    onTextChanged = {},
                    onClear = {},
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private companion object {
        const val INPUT_TEXT = "input_text"
        const val CHARACTER_COUNT = "character_count"
        const val VERSION_TEXT = "version_text"
        const val CLEAR_BUTTON = "clear_button"
        const val PERSISTENCE_ERROR = "persistence_error"
    }
}

private fun TextState.toUiState(
    editorValue: TextFieldValue,
    persistenceMessage: PersistenceMessage?,
) = MainScreenUiState.Content(
    textFieldValue = editorValue,
    version = version,
    characterCount = text.codePointCount(0, text.length),
    persistenceMessage = persistenceMessage,
)
