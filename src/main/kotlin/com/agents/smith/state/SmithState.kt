package com.agents.smith.state

import java.time.Instant
import java.util.UUID

/**
 * Represents the runtime state of the Smith tool window.
 */
data class SmithState(
    val sessionId: String = UUID.randomUUID().toString(),
    val connected: Boolean = false,
    val history: List<Message> = emptyList(),
    val streaming: Boolean = false,
    val settings: Settings = Settings(),
    val consentMap: Map<String, Boolean> = emptyMap()
) {

    data class Message(
        val role: Role,
        val content: String,
        val timestamp: Instant = Instant.now()
    )

    enum class Role {
        SYSTEM,
        USER,
        ASSISTANT
    }

    data class Settings(
        val model: String = "gpt-4o-mini",
        val stream: Boolean = true,
        val maxTokens: Int = 1024
    )

    companion object {
        fun initial(): SmithState {
            val welcomeMessage = Message(
                role = Role.SYSTEM,
                content = "Welcome to Smith. Configure your endpoint via the settings button to get started."
            )

            return SmithState(
                connected = false,
                history = listOf(welcomeMessage)
            )
        }
    }
}
