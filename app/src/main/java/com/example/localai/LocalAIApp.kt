package com.example.localai

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.localai.ui.components.AppBottomNavigation
import com.example.localai.ui.navigation.AppNavHost
import com.example.localai.ui.navigation.TopLevelDestination
import com.example.localai.ui.navigation.navigateToTab
import com.example.localai.ui.state.LocalAIViewModel
import com.example.localai.ui.theme.LocalAITheme
import androidx.compose.material3.MaterialTheme

/** Root composable: applies the selected theme, hosts navigation, bottom bar and snackbars. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalAIApp(viewModel: LocalAIViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LocalAITheme(themeMode = state.themeMode) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        // The bar is hidden only while the keyboard is open so imePadding() lines up in Chat.
        val imeVisible = WindowInsets.isImeVisible
        val isPrimaryDestination = TopLevelDestination.entries.any { it.route == currentRoute }
        val showBottomBar = isPrimaryDestination && !imeVisible
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(viewModel) {
            viewModel.messages.collect { message ->
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message)
            }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = if (showBottomBar) Modifier else Modifier.navigationBarsPadding()
                )
            },
            bottomBar = {
                if (showBottomBar) {
                    AppBottomNavigation(
                        currentRoute = currentRoute,
                        onNavigate = { navController.navigateToTab(it.route) }
                    )
                }
            }
        ) { innerPadding ->
            AppNavHost(
                navController = navController,
                viewModel = viewModel,
                state = state,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
