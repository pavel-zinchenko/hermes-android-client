package com.hermes.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.hermes.android.local.AppAction
import com.hermes.android.ui.HermesNavHost
import com.hermes.android.ui.theme.HermesTheme

class MainActivity : ComponentActivity() {

    // Registered before onCreate runs (field initializer), as the contract requires.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Ask for notification permission up front so reminders created via chat
        // (not just from the Schedule screen) can actually post on API 33+.
        maybeRequestNotificationPermission()
        setContent {
            HermesTheme {
                val snackbarHostState = remember { SnackbarHostState() }

                // Drive the snackbar from actions the Hermes agent pushes through the
                // loopback API server. A plain overlay (not an outer Scaffold) keeps the
                // inner screens' own Scaffolds/insets authoritative under edge-to-edge.
                LaunchedEffect(Unit) {
                    (application as HermesApp).actionFlow.collect { action ->
                        when (action) {
                            is AppAction.ShowSnackbar -> snackbarHostState.showSnackbar(action.message)
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        HermesNavHost()
                        SnackbarHost(
                            hostState = snackbarHostState,
                            // The host is a sibling of the nav host (not inside an
                            // inner Scaffold), so apply the system insets here or the
                            // snackbar renders behind the navigation bar / keyboard.
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .imePadding(),
                        )
                    }
                }
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
