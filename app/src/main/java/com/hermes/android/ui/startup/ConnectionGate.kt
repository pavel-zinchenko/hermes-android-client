package com.hermes.android.ui.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.android.ui.AppViewModelProvider

@Composable
fun ConnectionGate(
    onConnected: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ConnectionViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val s = state) {
        ConnectionState.Checking -> CheckingView()
        ConnectionState.Connected -> {
            // Hand off to the sessions screen exactly once.
            androidx.compose.runtime.LaunchedEffect(Unit) { onConnected() }
            CheckingView()
        }
        is ConnectionState.Unreachable -> UnreachableView(
            message = s.message,
            onRetry = viewModel::probe,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun CheckingView() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = "Connecting to Hermes…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun UnreachableView(
    message: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Can't reach Hermes",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "To connect, in the Termux app:\n\n" +
                "Pick a token — at least 8 characters.\n" +
                "Then start Hermes one of two ways:\n\n" +
                "A) Each launch (not saved):\n" +
                "   HERMES_DASHBOARD_SESSION_TOKEN=your-token \\\n" +
                "       hermes dashboard\n\n" +
                "B) Save it once — add this line to the file\n" +
                "   ~/.hermes/.env, then run  hermes dashboard:\n" +
                "   HERMES_DASHBOARD_SESSION_TOKEN=your-token\n\n" +
                "Finally, open Settings and paste the same\n" +
                "token into the token field.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp),
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) {
            Text("Retry")
        }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) {
            Text("Open Settings")
        }
    }
}
