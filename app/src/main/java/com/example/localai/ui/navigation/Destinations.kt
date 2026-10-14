package com.example.localai.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.ui.graphics.vector.ImageVector

/** Route names for the whole app. */
object Routes {
    // Primary destinations (bottom navigation)
    const val HOME = "home"
    const val CHAT = "chat"
    const val EXPLORE = "explore"
    const val MY_MODELS = "my_models"

    // Secondary destinations
    const val SETTINGS = "settings"
    const val LIBRARY = "library"
    const val SKILLS = "skills"
    const val SCHEDULED_TASKS = "scheduled_tasks"
    const val MODEL_ID_ARG = "modelId"
    const val DETAILS = "details/{$MODEL_ID_ARG}"

    fun details(modelId: String) = "details/$modelId"
}

/** The four permanent bottom-navigation destinations, in display order. */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME(Routes.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    CHAT(Routes.CHAT, "Chat", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline),
    EXPLORE(Routes.EXPLORE, "Explore", Icons.Filled.Explore, Icons.Outlined.Explore),
    MY_MODELS(Routes.MY_MODELS, "My Models", Icons.Filled.Inventory2, Icons.Outlined.Inventory2)
}
