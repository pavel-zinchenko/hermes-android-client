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
import com.hermes.android.ui.models.ModelsDetailScreen
import com.hermes.android.ui.models.ModelsListScreen
import com.hermes.android.ui.models.ModelsViewModel
import com.hermes.android.ui.schedule.ScheduleScreen
import com.hermes.android.ui.sessions.SessionsScreen
import com.hermes.android.ui.settings.SettingsScreen
import com.hermes.android.ui.startup.ConnectionGate
import com.hermes.android.ui.voice.SttDetailScreen
import com.hermes.android.ui.voice.SttListScreen
import com.hermes.android.ui.voice.SttViewModel
import com.hermes.android.ui.voice.TtsDetailScreen
import com.hermes.android.ui.voice.TtsListScreen
import com.hermes.android.ui.voice.TtsViewModel
import com.hermes.android.ui.voice.VoiceScreen

object Routes {
    const val GATE = "gate"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    // Each provider category is a nested graph (list + detail) so both screens share
    // the category's ViewModel, resolved from the graph's back-stack entry.
    const val MODELS = "models"
    const val MODELS_LIST = "models/list"
    const val MODELS_DETAIL = "models/detail"
    const val TTS = "tts"
    const val TTS_LIST = "tts/list"
    const val TTS_DETAIL = "tts/detail"
    const val STT = "stt"
    const val STT_LIST = "stt/list"
    const val STT_DETAIL = "stt/detail"
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
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenModels = { navController.navigate(Routes.MODELS) },
                onOpenTts = { navController.navigate(Routes.TTS) },
                onOpenStt = { navController.navigate(Routes.STT) },
            )
        }

        navigation(startDestination = Routes.MODELS_LIST, route = Routes.MODELS) {
            composable(Routes.MODELS_LIST) { entry ->
                val parentEntry = remember(entry) { navController.getBackStackEntry(Routes.MODELS) }
                val vm: ModelsViewModel = viewModel(parentEntry, factory = AppViewModelProvider.Factory)
                ModelsListScreen(
                    onBack = { navController.popBackStack() },
                    onConfigure = { navController.navigate(Routes.MODELS_DETAIL) },
                    viewModel = vm,
                )
            }
            composable(Routes.MODELS_DETAIL) { entry ->
                val parentEntry = remember(entry) { navController.getBackStackEntry(Routes.MODELS) }
                val vm: ModelsViewModel = viewModel(parentEntry, factory = AppViewModelProvider.Factory)
                ModelsDetailScreen(onBack = { navController.popBackStack() }, viewModel = vm)
            }
        }

        navigation(startDestination = Routes.TTS_LIST, route = Routes.TTS) {
            composable(Routes.TTS_LIST) { entry ->
                val parentEntry = remember(entry) { navController.getBackStackEntry(Routes.TTS) }
                val vm: TtsViewModel = viewModel(parentEntry, factory = AppViewModelProvider.Factory)
                TtsListScreen(
                    onBack = { navController.popBackStack() },
                    onConfigure = { navController.navigate(Routes.TTS_DETAIL) },
                    viewModel = vm,
                )
            }
            composable(Routes.TTS_DETAIL) { entry ->
                val parentEntry = remember(entry) { navController.getBackStackEntry(Routes.TTS) }
                val vm: TtsViewModel = viewModel(parentEntry, factory = AppViewModelProvider.Factory)
                TtsDetailScreen(onBack = { navController.popBackStack() }, viewModel = vm)
            }
        }

        navigation(startDestination = Routes.STT_LIST, route = Routes.STT) {
            composable(Routes.STT_LIST) { entry ->
                val parentEntry = remember(entry) { navController.getBackStackEntry(Routes.STT) }
                val vm: SttViewModel = viewModel(parentEntry, factory = AppViewModelProvider.Factory)
                SttListScreen(
                    onBack = { navController.popBackStack() },
                    onConfigure = { navController.navigate(Routes.STT_DETAIL) },
                    viewModel = vm,
                )
            }
            composable(Routes.STT_DETAIL) { entry ->
                val parentEntry = remember(entry) { navController.getBackStackEntry(Routes.STT) }
                val vm: SttViewModel = viewModel(parentEntry, factory = AppViewModelProvider.Factory)
                SttDetailScreen(onBack = { navController.popBackStack() }, viewModel = vm)
            }
        }

        composable(Routes.SCHEDULE) {
            ScheduleScreen(onBack = { navController.popBackStack() })
        }
    }
}
