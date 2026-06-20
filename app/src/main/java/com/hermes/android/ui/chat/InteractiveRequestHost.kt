package com.hermes.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.hermes.android.data.gateway.InteractiveRequest

/**
 * Renders the head of the interactive-request queue as a modal. The agent is
 * blocked until one of the callbacks fires, so every dialog has an explicit
 * negative action (Deny / Cancel sends the safe answer) and can't be dismissed by
 * tapping outside.
 */
@Composable
fun InteractiveRequestHost(
    request: InteractiveRequest?,
    onApproval: (choice: String) -> Unit,
    onClarify: (answer: String) -> Unit,
    onSudo: (password: String) -> Unit,
    onSecret: (value: String) -> Unit,
) {
    if (request == null) return
    // Re-key on the request *instance* so per-dialog input (typed answer /
    // password) and the single-shot latch reset whenever the head advances —
    // including to a same-type request whose fields happen to be equal, which
    // data-class equality alone wouldn't distinguish. Without this a queued
    // second sudo/secret prompt would inherit the previous one's typed value.
    key(System.identityHashCode(request)) {
        // The agent blocks on exactly one response and AlertDialog buttons are
        // not debounced, so a fast double-tap could otherwise dispatch twice
        // (resolving a second queued approval the user never saw). Latch the
        // first action; ignore the rest until this dialog is replaced.
        var answered by remember { mutableStateOf(false) }
        val once: (() -> Unit) -> Unit = { send -> if (!answered) { answered = true; send() } }
        when (request) {
            is InteractiveRequest.Approval -> ApprovalDialog(request) { c -> once { onApproval(c) } }
            is InteractiveRequest.Clarify -> ClarifyDialog(request) { a -> once { onClarify(a) } }
            is InteractiveRequest.Sudo -> SudoDialog { p -> once { onSudo(p) } }
            is InteractiveRequest.Secret -> SecretDialog(request) { v -> once { onSecret(v) } }
        }
    }
}

/** A non-dismissable dialog: the agent is blocked, so force an explicit choice. */
private val blockingDialog = DialogProperties(
    dismissOnBackPress = false,
    dismissOnClickOutside = false,
)

@Composable
private fun ApprovalDialog(
    request: InteractiveRequest.Approval,
    onApproval: (choice: String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        properties = blockingDialog,
        title = { Text("Run this command?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    request.description.ifBlank { "dangerous command" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (request.command.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = request.command,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        },
        // Three approval choices stacked as buttons; "always" is intentionally omitted.
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onApproval("once") }) { Text("Run once") }
                TextButton(onClick = { onApproval("session") }) { Text("Allow for session") }
            }
        },
        dismissButton = {
            TextButton(onClick = { onApproval("deny") }) {
                Text("Deny", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun ClarifyDialog(
    request: InteractiveRequest.Clarify,
    onClarify: (answer: String) -> Unit,
) {
    var answer by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        properties = blockingDialog,
        title = { Text("Hermes needs input") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (request.question.isNotBlank()) {
                    Text(request.question, style = MaterialTheme.typography.bodyMedium)
                }
                request.choices.forEach { choice ->
                    OutlinedButton(
                        onClick = { onClarify(choice) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(choice, modifier = Modifier.fillMaxWidth())
                    }
                }
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type an answer…") },
                    singleLine = false,
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onClarify(answer) },
                enabled = answer.isNotBlank(),
            ) { Text("Send") }
        },
        dismissButton = {
            TextButton(onClick = { onClarify("") }) { Text("Cancel") }
        },
    )
}

@Composable
private fun SudoDialog(onSudo: (password: String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        properties = blockingDialog,
        title = { Text("Password required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Hermes needs your sudo password to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSudo(password) },
                enabled = password.isNotEmpty(),
            ) { Text("Submit") }
        },
        dismissButton = {
            TextButton(onClick = { onSudo("") }) { Text("Cancel") }
        },
    )
}

@Composable
private fun SecretDialog(
    request: InteractiveRequest.Secret,
    onSecret: (value: String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        properties = blockingDialog,
        title = { Text("Secret required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    request.prompt.ifBlank { "Hermes needs a credential to continue." },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(request.envVar.ifBlank { "Value" }) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSecret(value) },
                enabled = value.isNotEmpty(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onSecret("") }) { Text("Cancel") }
        },
    )
}
