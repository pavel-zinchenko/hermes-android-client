package com.hermes.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.android.ui.chat.ChatScreen
import com.hermes.android.ui.chat.ChatSessionViewModel
import com.hermes.android.ui.schedule.ScheduleScreen
import com.hermes.android.ui.sessions.SessionsScreen
import com.hermes.android.ui.settings.SettingsScreen
import com.hermes.android.ui.startup.ConnectionGate
import com.hermes.android.ui.voice.VoiceScreen

object Routes {
    const val GATE = "gate"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val SCHEDULE = "schedule"
    // Chat + voice live in one nested graph keyed by sessionId so they can share a
    // single ViewModel (scoped to this graph's back stack entry).
    const val SESSION = "session/{sessionId}"
    fun session(sessionId: String) = "session/$sessionId"
    const val CHAT = "chat"
    const val VOICE = "voice"
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
                onOpenChat = { id -> navController.navigate(Routes.session(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSchedule = { navController.navigate(Routes.SCHEDULE) },
            )
        }

        // The session subgraph carries {sessionId}; both chat and voice resolve the
        // same ChatSessionViewModel from this graph's entry, so their message list is
        // shared live. Popping the whole subgraph (back from chat) clears the VM.
        navigation(
            startDestination = Routes.CHAT,
            route = Routes.SESSION,
        ) {
            composable(Routes.CHAT) { entry ->
                val parentEntry = remember(entry) { navController.getBackStackEntry(Routes.SESSION) }
                val vm: ChatSessionViewModel = viewModel(parentEntry, factory = AppViewModelProvider.Factory)
                ChatScreen(
                    onBack = { navController.popBackStack(Routes.SESSION, inclusive = true) },
                    onOpenVoice = { navController.navigate(Routes.VOICE) },
                    viewModel = vm,
                )
            }

            composable(Routes.VOICE) { entry ->
                val parentEntry = remember(entry) { navController.getBackStackEntry(Routes.SESSION) }
                val vm: ChatSessionViewModel = viewModel(parentEntry, factory = AppViewModelProvider.Factory)
                VoiceScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = vm,
                )
            }
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SCHEDULE) {
            ScheduleScreen(onBack = { navController.popBackStack() })
        }
    }
}
