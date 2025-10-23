package com.agents.smith.viewmodel

import com.agents.smith.state.SmithState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.intellij.openapi.Disposable

/**
 * Handles transient Smith UI state and lightweight simulation of backend behaviour.
 * A real backend integration can replace the placeholder echo logic later on.
 */
class SmithViewModel : Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SmithState.initial())

    val state: StateFlow<SmithState> = _state.asStateFlow()

    fun updateConnectionStatus(connected: Boolean) {
        _state.update { it.copy(connected = connected) }
    }

    fun updateSettings(settings: SmithState.Settings) {
        _state.update { it.copy(settings = settings) }
    }

    fun updateConsent(key: String, allowed: Boolean) {
        _state.update { it.copy(consentMap = it.consentMap + (key to allowed)) }
    }

    fun sendUserMessage(rawText: String) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return

        scope.launch {
            appendMessage(
                SmithState.Message(
                    role = SmithState.Role.USER,
                    content = trimmed
                )
            )
            simulateAssistantResponse(trimmed)
        }
    }

    private suspend fun simulateAssistantResponse(sourceText: String) {
        _state.update { it.copy(streaming = true) }
        delay(250) // quick feedback so the UI feels responsive on first launch
        appendMessage(
            SmithState.Message(
                role = SmithState.Role.ASSISTANT,
                content = buildString {
                    appendLine("This is a placeholder response from Smith.")
                    append("Echoing your message: ").append(sourceText)
                }
            )
        )
        _state.update { it.copy(streaming = false) }
    }

    private fun appendMessage(message: SmithState.Message) {
        _state.update { it.copy(history = it.history + message) }
    }

    override fun dispose() {
        scope.cancel("SmithViewModel disposed")
    }
}
