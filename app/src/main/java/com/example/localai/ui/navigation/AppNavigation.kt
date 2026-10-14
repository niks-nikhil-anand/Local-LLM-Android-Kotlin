package com.example.localai.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.localai.data.mock.MockModelRepository
import com.example.localai.data.model.DemoScenario
import com.example.localai.ui.screens.chat.ChatScreen
import com.example.localai.ui.screens.details.ModelDetailsScreen
import com.example.localai.ui.screens.explore.ExploreScreen
import com.example.localai.ui.screens.home.HomeScreen
import com.example.localai.ui.screens.library.LibraryScreen
import com.example.localai.ui.screens.mymodels.MyModelsScreen
import com.example.localai.ui.screens.scheduled.ScheduledTasksScreen
import com.example.localai.ui.screens.settings.SettingsScreen
import com.example.localai.ui.screens.skills.SkillsScreen
import com.example.localai.ui.state.ChatActions
import com.example.localai.ui.state.LocalAISettingsActions
import com.example.localai.ui.state.LocalAIUiState
import com.example.localai.ui.state.LocalAIViewModel

private val tabRoutes = TopLevelDestination.entries.map { it.route }.toSet()

private fun NavBackStackEntry.isTab() = destination.route in tabRoutes

/** Switches tabs while saving and restoring each tab's state. */
fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    viewModel: LocalAIViewModel,
    state: LocalAIUiState,
    modifier: Modifier = Modifier
) {
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (initialState.isTab() && targetState.isTab()) fadeIn(tween(150))
        else slideInHorizontally(tween(250)) { it / 8 } + fadeIn(tween(200))
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(tween(120))
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(tween(150))
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(tween(200)) { it / 8 } + fadeOut(tween(150))
    }

    val openDetails = { id: String -> navController.navigate(Routes.details(id)) { launchSingleTop = true } }
    // Chat is a single tab: opening a model's chat selects its conversation, then shows the tab.
    val openChat = { id: String ->
        viewModel.openChatWith(id)
        navController.navigateToTab(Routes.CHAT)
    }
    val openSecondary = { route: String -> navController.navigate(route) { launchSingleTop = true } }
    val goBack: () -> Unit = { navController.popBackStack() }
    val modelIdArg = listOf(navArgument(Routes.MODEL_ID_ARG) { type = NavType.StringType })

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = popEnter,
        popExitTransition = popExit
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                state = state,
                onExplore = { navController.navigateToTab(Routes.EXPLORE) },
                onMyModels = { navController.navigateToTab(Routes.MY_MODELS) },
                onSettings = { openSecondary(Routes.SETTINGS) },
                onStartDemoChat = { openChat(state.defaultModelId) },
                onModelClick = openDetails
            )
        }
        composable(Routes.CHAT) {
            ChatScreen(
                state = state,
                actions = ChatActions(
                    onSend = viewModel::sendMessage,
                    onClear = viewModel::clearChat,
                    onNewChat = viewModel::newConversation,
                    onSelectConversation = viewModel::selectConversation,
                    onHistoryQueryChange = viewModel::setHistoryQuery,
                    onSelectModel = viewModel::selectChatModel,
                    onToggleWebSearch = viewModel::toggleWebSearch,
                    onAddAttachment = viewModel::addAttachment,
                    onRemoveAttachment = viewModel::removeAttachment,
                    onOpenDetails = openDetails,
                    onShowMessage = viewModel::showMessage,
                    onOpenLibrary = { openSecondary(Routes.LIBRARY) },
                    onOpenSkills = { openSecondary(Routes.SKILLS) },
                    onOpenScheduledTasks = { openSecondary(Routes.SCHEDULED_TASKS) },
                    onOpenSettings = { openSecondary(Routes.SETTINGS) }
                )
            )
        }
        composable(Routes.EXPLORE) {
            ExploreScreen(
                state = state,
                onQueryChange = viewModel::setSearchQuery,
                onCategorySelect = viewModel::setCategory,
                onSortSelect = viewModel::setSortOption,
                onModelClick = openDetails
            )
        }
        composable(Routes.MY_MODELS) {
            MyModelsScreen(
                state = state,
                onModelClick = openDetails,
                onOpenChat = openChat,
                onPause = viewModel::pauseDownload,
                onResume = viewModel::resumeDownload,
                onRemove = viewModel::removeModel,
                onSelectScenario = viewModel::selectScenario,
                onExplore = { navController.navigateToTab(Routes.EXPLORE) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                state = state,
                onBack = goBack,
                actions = LocalAISettingsActions(
                    onThemeSelect = viewModel::setThemeMode,
                    onDefaultModelSelect = viewModel::setDefaultModel,
                    onPreferredSizeSelect = viewModel::setPreferredSize,
                    onAutoCompatibilityChange = viewModel::setAutoCompatibilityCheck,
                    onClearDemoData = viewModel::clearDemoData,
                    onRestoreDemoData = { viewModel.selectScenario(DemoScenario.SAMPLE) },
                    onShowMessage = viewModel::showMessage
                )
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(onBack = goBack, onShowMessage = viewModel::showMessage)
        }
        composable(Routes.SKILLS) {
            SkillsScreen(
                enabledSkillIds = state.enabledSkillIds,
                onToggleSkill = viewModel::toggleSkill,
                onBack = goBack
            )
        }
        composable(Routes.SCHEDULED_TASKS) {
            ScheduledTasksScreen(
                enabledTaskIds = state.enabledTaskIds,
                onToggleTask = viewModel::toggleTask,
                onBack = goBack
            )
        }
        composable(Routes.DETAILS, arguments = modelIdArg) { entry ->
            val model = entry.arguments?.getString(Routes.MODEL_ID_ARG)?.let(MockModelRepository::findModel)
            ModelDetailsScreen(
                model = model,
                installState = model?.let { state.installStateOf(it.id) },
                onBack = goBack,
                onOpenChat = openChat,
                onSimulateDownload = viewModel::simulateDownload
            )
        }
    }
}
