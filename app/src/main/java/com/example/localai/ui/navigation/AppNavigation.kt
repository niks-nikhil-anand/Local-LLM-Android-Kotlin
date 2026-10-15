package com.example.localai.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.localai.data.CatalogServices
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.mock.MockModelRepository
import com.example.localai.ui.screens.chat.ChatScreen
import com.example.localai.ui.screens.details.ModelDetailsScreen
import com.example.localai.ui.screens.explore.ExploreActions
import com.example.localai.ui.screens.explore.ExploreScreen
import com.example.localai.ui.screens.explore.ExploreViewModel
import com.example.localai.ui.screens.explore.HfModelDetailsScreen
import com.example.localai.ui.screens.explore.DownloadActions
import com.example.localai.ui.screens.explore.RepoDetailsActions
import com.example.localai.ui.screens.explore.RepoDetailsViewModel
import com.example.localai.ui.screens.home.HomeScreen
import com.example.localai.ui.screens.home.HomeViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.localai.ui.screens.library.LibraryScreen
import com.example.localai.ui.screens.mymodels.MyModelsActions
import com.example.localai.ui.screens.mymodels.MyModelsScreen
import com.example.localai.ui.screens.mymodels.MyModelsViewModel
import com.example.localai.ui.screens.scheduled.ScheduledTasksScreen
import com.example.localai.ui.screens.settings.SettingsScreen
import com.example.localai.ui.screens.skills.SkillsScreen
import com.example.localai.ui.state.ChatActions
import com.example.localai.ui.screens.settings.LicencesRoute
import com.example.localai.ui.screens.settings.SettingsActions
import com.example.localai.ui.screens.settings.SettingsViewModel
import com.example.localai.data.AppInfo
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
            val context = LocalContext.current.applicationContext
            val home: HomeViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        HomeViewModel(
                            inventory = CatalogServices.modelInventory(context),
                            session = CatalogServices.modelSession(context),
                            deviceResources = CatalogServices.deviceResources(context)
                        )
                    }
                }
            )
            val homeState by home.state.collectAsStateWithLifecycle()
            // Re-check files and re-measure the device each time Home comes back into view.
            LifecycleResumeEffect(home) {
                home.refresh()
                onPauseOrDispose { }
            }
            HomeScreen(
                state = homeState,
                onExplore = { navController.navigateToTab(Routes.EXPLORE) },
                onMyModels = { navController.navigateToTab(Routes.MY_MODELS) },
                onSettings = { openSecondary(Routes.SETTINGS) },
                onOpenChat = { navController.navigateToTab(Routes.CHAT) }
            )
        }
        composable(Routes.CHAT) {
            ChatScreen(
                state = state,
                actions = ChatActions(
                    onSend = viewModel::sendMessage,
                    onStop = viewModel::stopGeneration,
                    onOpenMyModels = { navController.navigateToTab(Routes.MY_MODELS) },
                    onDismissError = viewModel::dismissChatError,
                    onClear = viewModel::clearChat,
                    onNewChat = viewModel::newConversation,
                    onSelectConversation = viewModel::selectConversation,
                    onHistoryQueryChange = viewModel::setHistoryQuery,
                    onToggleWebSearch = viewModel::toggleWebSearch,
                    onConfirmWebSearch = viewModel::confirmWebSearch,
                    onDismissWebSearchConsent = viewModel::dismissWebSearchConsent,
                    onAttachDocument = viewModel::attachDocument,
                    onRemoveAttachment = viewModel::removeAttachment,
                    onShowMessage = viewModel::showMessage,
                    onOpenLibrary = { openSecondary(Routes.LIBRARY) },
                    onOpenSkills = { openSecondary(Routes.SKILLS) },
                    onOpenScheduledTasks = { openSecondary(Routes.SCHEDULED_TASKS) },
                    onOpenSettings = { openSecondary(Routes.SETTINGS) }
                )
            )
        }
        composable(Routes.EXPLORE) {
            // Scoped to this destination: survives rotation and tab switches without re-requesting.
            val explore: ExploreViewModel = viewModel(
                factory = viewModelFactory { initializer { ExploreViewModel(CatalogServices.catalog) } }
            )
            val exploreState by explore.state.collectAsStateWithLifecycle()
            ExploreScreen(
                state = exploreState,
                actions = ExploreActions(
                    onQueryChange = explore::onQueryChange,
                    onCategorySelect = explore::onCategorySelect,
                    onSortSelect = explore::onSortSelect,
                    onRefresh = explore::refresh,
                    onRetry = explore::retry,
                    onLoadMore = explore::loadMore,
                    onClearFilters = explore::clearSearchAndFilters,
                    onModelClick = { repoId -> navController.navigate(Routes.hfModel(repoId)) { launchSingleTop = true } }
                )
            )
        }
        composable(
            Routes.HF_MODEL,
            arguments = listOf(navArgument(Routes.HF_REPO_ARG) { type = NavType.StringType; nullable = true })
        ) { entry ->
            val repoId = entry.arguments?.getString(Routes.HF_REPO_ARG)
                ?.let { runCatching { HfRepoId(it) }.getOrNull() }
            if (repoId == null) {
                LaunchedEffect(Unit) { goBack() }
                return@composable
            }
            val context = LocalContext.current.applicationContext
            val details: RepoDetailsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        RepoDetailsViewModel(
                            repoId = repoId,
                            catalog = CatalogServices.catalog,
                            deviceResources = CatalogServices.deviceResources(context),
                            headerInspector = CatalogServices.headerInspector,
                            downloads = CatalogServices.downloads(context),
                            clock = SystemClock::elapsedRealtime
                        )
                    }
                }
            )
            val detailsState by details.state.collectAsStateWithLifecycle()
            HfModelDetailsScreen(
                state = detailsState,
                actions = RepoDetailsActions(
                    onBack = goBack,
                    onRetrySummary = details::retrySummary,
                    onRetryFiles = details::retryFiles,
                    onRecheckDevice = details::recheckDevice,
                    onInspectHeader = details::inspectHeader,
                    downloads = DownloadActions(
                        onDownload = details::download,
                        onPause = details::pauseDownload,
                        onCancel = details::cancelDownload,
                        onRetry = details::retryDownload,
                        onDelete = details::deleteDownload,
                        onDismissMessage = details::dismissDownloadMessage
                    )
                )
            )
        }
        composable(Routes.MY_MODELS) {
            // Real downloads (Room + app storage); scoped to this destination like Explore.
            val context = LocalContext.current.applicationContext
            val myModels: MyModelsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        MyModelsViewModel(
                            downloads = CatalogServices.downloads(context),
                            deviceResources = CatalogServices.deviceResources(context),
                            clock = SystemClock::elapsedRealtime,
                            session = CatalogServices.modelSession(context)
                        )
                    }
                }
            )
            val myModelsState by myModels.state.collectAsStateWithLifecycle()
            MyModelsScreen(
                state = myModelsState,
                actions = MyModelsActions(
                    onOpenDetails = { repoId -> navController.navigate(Routes.hfModel(repoId)) { launchSingleTop = true } },
                    onPause = myModels::pause,
                    onResume = myModels::retry,
                    onCancel = myModels::cancel,
                    onDelete = myModels::delete,
                    onRefresh = myModels::refresh,
                    onDismissMessage = myModels::dismissMessage,
                    onExplore = { navController.navigateToTab(Routes.EXPLORE) },
                    onLoad = myModels::loadModel,
                    onUnload = myModels::unloadModel,
                    onOpenChat = { navController.navigateToTab(Routes.CHAT) }
                )
            )
        }
        composable(Routes.SETTINGS) {
            val context = LocalContext.current.applicationContext
            val settings: SettingsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SettingsViewModel(
                            inventory = CatalogServices.modelInventory(context),
                            session = CatalogServices.modelSession(context),
                            deviceResources = CatalogServices.deviceResources(context),
                            appInfo = { AppInfo.read(context) }
                        )
                    }
                }
            )
            val settingsState by settings.state.collectAsStateWithLifecycle()
            LifecycleResumeEffect(settings) {
                settings.refresh()
                onPauseOrDispose { }
            }
            SettingsScreen(
                themeMode = state.themeMode,
                state = settingsState,
                onBack = goBack,
                actions = SettingsActions(
                    onThemeSelect = viewModel::setThemeMode,
                    onClearConversations = viewModel::clearConversations,
                    onOpenMyModels = { navController.navigateToTab(Routes.MY_MODELS) },
                    onOpenLicences = { openSecondary(Routes.LICENCES) }
                )
            )
        }
        composable(Routes.LICENCES) {
            LicencesRoute(onBack = goBack)
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
