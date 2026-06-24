package com.hermes.android.local

/**
 * An action the [LocalApiServer] can trigger inside the running app on behalf of
 * Hermes. The agent (running in Termux) reaches actions it cannot perform from the
 * shell — anything needing this app's Compose UI or Android SDK access — by POSTing
 * to the loopback server, which maps the request onto one of these and forwards it
 * over `HermesApp.actionFlow` to the UI layer.
 */
sealed interface AppAction {
    /** Show a Snackbar with [message] in the app UI. The smoke-test capability. */
    data class ShowSnackbar(val message: String) : AppAction
}
