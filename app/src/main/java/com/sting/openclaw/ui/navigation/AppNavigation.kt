package com.sting.openclaw.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sting.openclaw.ui.screens.chat.ChatScreen
import com.sting.openclaw.ui.screens.setup.SetupScreen

sealed class Screen(val route: String) {
    data object Setup : Screen("setup")
    data object Chat : Screen("chat")
}

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Setup.route,
    onDisconnect: () -> Unit = {}
) {
    val navController: NavHostController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
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
                onDisconnect = {
                    onDisconnect()
                    navController.navigate(Screen.Setup.route) {
                        popUpTo(Screen.Chat.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
