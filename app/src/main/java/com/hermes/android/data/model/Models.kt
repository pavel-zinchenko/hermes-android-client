package com.hermes.android.data.model

/** App-facing domain models, decoupled from the wire DTOs. */

data class ChatSession(
    val id: String,
    val title: String,
    val messageCount: Int,
    val lastActive: String?,
    val preview: String?,
)

enum class Sender { USER, ASSISTANT }

data class ChatMessage(
    val id: String,
    val sender: Sender,
    val text: String,
)
