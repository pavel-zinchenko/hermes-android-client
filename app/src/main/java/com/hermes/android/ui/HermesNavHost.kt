package com.hermes.android.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermes.android.ui.chat.ChatScreen
import com.hermes.android.ui.sessions.SessionsScreen
import com.hermes.android.ui.settings.SettingsScreen
import com.hermes.android.ui.startup.ConnectionGate

object Routes {
    const val GATE = "gate"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{sessionId}"
    fun chat(sessionId: String) = "chat/$sessionId"
}

@Composable
fun HermesNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.GATE) {
        composable(Routes.GATE) {
            ConnectionGate(
                onConnected = {
                    navController.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.GATE) { inclusive = true }
                    }
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SESSIONS) {
            SessionsScreen(
                onOpenChat = { id -> navController.navigate(Routes.chat(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) {
            ChatScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
