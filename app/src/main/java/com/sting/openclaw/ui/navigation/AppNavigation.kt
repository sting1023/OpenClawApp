package com.sting.openclaw.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sting.openclaw.ui.screens.chat.ChatScreen
import com.sting.openclaw.ui.screens.settings.SettingsScreen
import com.sting.openclaw.ui.screens.setup.SetupScreen

sealed class Screen(val route: String) {
    data object Setup : Screen("setup")
    data object Chat : Screen("chat")
    data object Settings : Screen("settings")
}

@Composable
fun AppNavigation(
    onDisconnect: () -> Unit
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Setup.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Setup.route) {
            SetupScreen(
                onConnected = {
                    navController.navigate(Screen.Chat.route) {
                        popUpTo(Screen.Setup.route) { inclusive = true }
                    }
                }
            )
        }
        
        composable(Screen.Chat.route) {
            ChatScreen(
            onDisconnect = onDisconnect,
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }
        
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onDisconnected = {
                    navController.navigate(Screen.Setup.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
