package com.ericdevwang.inputbridge

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.ericdevwang.inputbridge.ui.main.MainScreen

@Composable
@Suppress("FunctionName")
fun MainNavigation() {
    val backStack = rememberNavBackStack(Main)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider =
        entryProvider {
            entry<Main> {
                MainScreen()
            }
        },
    )
}
