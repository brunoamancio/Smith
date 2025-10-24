package com.agents.smith.viewmodel

import com.agents.smith.acp.AcpAuthenticateParams
import com.agents.smith.acp.AcpClient
import com.agents.smith.acp.AcpClientCapabilities
import com.agents.smith.acp.AcpClientDescriptor
import com.agents.smith.acp.AcpHttpClient
import com.agents.smith.acp.AcpHttpTransport
import com.agents.smith.acp.AcpHttpTransportConfig
import com.agents.smith.acp.AcpInitializeParams
import com.agents.smith.acp.AcpMessage
import com.agents.smith.acp.AcpMessagePart
import com.agents.smith.acp.AcpMessageRole
import com.agents.smith.acp.AcpPromptRequest
import com.agents.smith.acp.AcpSessionEvent
import com.agents.smith.acp.AcpSessionOpenRequest
import com.agents.smith.acp.AcpSessionUpdate
import com.agents.smith.acp.DEFAULT_ACP_PROTOCOL_VERSION
import com.agents.smith.state.SmithState
import com.intellij.openapi.Disposable
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.Instant
import java.util.UUID
import kotlin.sequences.asSequence

/**
 * Handles Smith UI state and bridges user interactions to the ACP backend.
 */
class SmithViewModel(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : Disposable {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val httpClient = OkHttpClient()

    private val _state = MutableStateFlow(SmithState.initial())
    val state: StateFlow<SmithState> = _state.asStateFlow()

    private val sessionMutex = Mutex()
    private var acpClient: AcpClient? = null
    private var acpSessionId: String? = null
    private var sessionUpdatesJob: Job? = null
    private var acpToken: String = ""

    private val responseBuilders = mutableMapOf<String, StringBuilder>()
    private val responseMessageIndex = mutableMapOf<String, Int>()

    fun updateConnectionStatus(connected: Boolean) {
        _state.update { it.copy(connected = connected) }
    }

    fun updateSettings(settings: SmithState.Settings, token: String) {
        val normalizedSettings = settings.copy(acpEndpoint = settings.acpEndpoint.trim())
        val previous = _state.value.settings
        val tokenChanged = acpToken != token
        acpToken = token
        _state.update { it.copy(settings = normalizedSettings) }
        if (normalizedSettings != previous || tokenChanged) {
            scope.launch {
                reconnect(normalizedSettings)
            }
        }
    }

    fun updateConsent(key: String, allowed: Boolean) {
        _state.update { it.copy(consentMap = it.consentMap + (key to allowed)) }
    }

    fun sendUserMessage(rawText: String) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return

        scope.launch {
            val message = SmithState.Message(
                role = SmithState.Role.USER,
                content = trimmed,
                timestamp = Instant.now()
            )
            appendMessage(message)

            val sessionId = ensureSessionReady()
            if (sessionId == null) {
                appendSystemMessage("Smith is not connected. Configure the ACP endpoint in Settings.")
                return@launch
            }

            val promptId = UUID.randomUUID().toString()
            val messages = buildMessagePayload()
            val prompt = AcpPromptRequest(
                sessionId = sessionId,
                promptId = promptId,
                messages = messages,
                metadata = buildPromptMetadata(_state.value.settings)
            )

            _state.update { it.copy(streaming = true) }
            responseBuilders.remove(promptId)
            responseMessageIndex.remove(promptId)

            try {
                withContext(dispatcher) {
                    acpClient?.sendPrompt(prompt)
                }
            } catch (ex: Exception) {
                _state.update { it.copy(streaming = false) }
                appendSystemMessage("Failed to send prompt: ${ex.message}")
            }
        }
    }

    private suspend fun ensureSessionReady(): String? {
        return sessionMutex.withLock {
            val settings = _state.value.settings
            if (settings.acpEndpoint.isBlank()) {
                updateConnectionStatus(false)
                return@withLock null
            }

            if (acpClient != null && acpSessionId != null) {
                return@withLock acpSessionId
            }

            reconnect(settings)
            acpSessionId
        }
    }

    private suspend fun reconnect(settings: SmithState.Settings) {
        sessionMutex.withLock {
            sessionUpdatesJob?.cancel()
            sessionUpdatesJob = null
            acpClient = null
            acpSessionId = null
            responseBuilders.clear()
            responseMessageIndex.clear()
            updateConnectionStatus(false)

            if (settings.acpEndpoint.isBlank()) {
                return@withLock
            }

            try {
                val client = buildClient(settings)
                val initParams = AcpInitializeParams(
                    protocolVersion = DEFAULT_ACP_PROTOCOL_VERSION,
                    client = AcpClientDescriptor(
                        name = "Smith IntelliJ Plugin",
                        version = "1.0",
                        vendor = "YourCompany",
                        capabilities = mapOf(
                            "ide" to "IntelliJ",
                            "pluginVersion" to "1.0-SNAPSHOT"
                        )
                    ),
                    capabilities = AcpClientCapabilities(
                        supportsFileSystem = settings.acpCapabilities.allowFileSystem,
                        supportsTerminal = settings.acpCapabilities.allowTerminal,
                        supportsApplyPatch = settings.acpCapabilities.allowApplyPatch
                    )
                )

                withContext(dispatcher) {
                    client.initialize(initParams)
                    if (acpToken.isNotBlank()) {
                        client.authenticate(AcpAuthenticateParams(acpToken))
                    }
                }

                val sessionHandle = withContext(dispatcher) {
                    client.openSession(
                        AcpSessionOpenRequest(
                            clientSessionId = _state.value.sessionId,
                            metadata = buildSessionMetadata(settings)
                        )
                    )
                }

                acpClient = client
                acpSessionId = sessionHandle.sessionId
                updateConnectionStatus(true)

                sessionUpdatesJob = scope.launch {
                    client.observeSession(sessionHandle.sessionId).collect { update ->
                        handleSessionUpdate(update)
                    }
                }
            } catch (ex: Exception) {
                acpClient = null
                acpSessionId = null
                appendSystemMessage("Failed to connect to ACP: ${ex.message}")
            }
        }
    }

    private fun buildClient(settings: SmithState.Settings): AcpClient {
        val headers = mutableMapOf<String, String>()
        if (acpToken.isNotBlank()) {
            headers["Authorization"] = "Bearer $acpToken"
        }

        val transport = AcpHttpTransport(
            config = AcpHttpTransportConfig(
                baseUrl = settings.acpEndpoint,
                defaultHeaders = headers
            ),
            client = httpClient,
            dispatcher = dispatcher
        )

        return AcpHttpClient(transport)
    }

    private fun handleSessionUpdate(update: AcpSessionUpdate) {
        update.events.forEach { event ->
            when (event) {
                is AcpSessionEvent.MessageChunk -> handleMessageChunk(update.promptId, event)
                is AcpSessionEvent.ToolCallStarted -> appendSystemMessage(
                    "Tool call '${event.name}' started (${event.callId})."
                )
                is AcpSessionEvent.ToolCallCompleted -> appendSystemMessage(
                    "Tool call '${event.callId}' completed."
                )
                is AcpSessionEvent.PlanUpdated -> appendSystemMessage(
                    buildString {
                        append("Plan updated: ").append(event.summary)
                        if (event.steps.isNotEmpty()) {
                            append("\n- ").append(event.steps.joinToString("\n- "))
                        }
                    }
                )
                is AcpSessionEvent.ModeChanged -> appendSystemMessage(
                    "Agent mode changed to '${event.nextMode}'."
                )
                is AcpSessionEvent.Unknown -> appendSystemMessage(
                    "Received ACP event: ${event.type ?: "unknown"}"
                )
            }
        }
    }

    private fun handleMessageChunk(promptId: String?, chunk: AcpSessionEvent.MessageChunk) {
        val key = promptId ?: ""
        val builder = responseBuilders.getOrPut(key) { StringBuilder() }
        builder.append(chunk.content.text)
        upsertAssistantMessage(key, builder.toString())

        if (chunk.done) {
            responseBuilders.remove(key)
            responseMessageIndex.remove(key)
            _state.update { it.copy(streaming = false) }
        } else {
            _state.update { it.copy(streaming = true) }
        }
    }

    private fun upsertAssistantMessage(promptKey: String, content: String) {
        responseMessageIndex[promptKey]?.let { index ->
            _state.update { state ->
                val updatedHistory = state.history.toMutableList()
                val original = updatedHistory[index]
                updatedHistory[index] = original.copy(content = content, timestamp = Instant.now())
                state.copy(history = updatedHistory)
            }
        } ?: run {
            _state.update { state ->
                val newIndex = state.history.size
                responseMessageIndex[promptKey] = newIndex
                state.copy(
                    history = state.history + SmithState.Message(
                        role = SmithState.Role.ASSISTANT,
                        content = content,
                        timestamp = Instant.now()
                    )
                )
            }
        }
    }

    private fun appendMessage(message: SmithState.Message) {
        _state.update { it.copy(history = it.history + message) }
    }

    private fun appendSystemMessage(text: String) {
        _state.update { state ->
            state.copy(
                history = state.history + SmithState.Message(
                    role = SmithState.Role.SYSTEM,
                    content = text,
                    timestamp = Instant.now()
                )
            )
        }
    }

    private fun buildMessagePayload(): List<AcpMessage> {
        return _state.value.history.map { message ->
            AcpMessage(
                role = when (message.role) {
                    SmithState.Role.SYSTEM -> AcpMessageRole.SYSTEM
                    SmithState.Role.USER -> AcpMessageRole.USER
                    SmithState.Role.ASSISTANT -> AcpMessageRole.ASSISTANT
                },
                content = listOf(AcpMessagePart.Text(message.content)),
                timestamp = message.timestamp
            )
        }
    }

    private fun buildPromptMetadata(settings: SmithState.Settings): Map<String, String> {
        val caps = settings.acpCapabilities
        return buildMap {
            put("model", settings.model)
            put("stream", settings.stream.toString())
            put("maxTokens", settings.maxTokens.toString())
            put("allowFileSystem", caps.allowFileSystem.toString())
            put("allowTerminal", caps.allowTerminal.toString())
            put("allowApplyPatch", caps.allowApplyPatch.toString())
        }
    }

    private fun buildSessionMetadata(settings: SmithState.Settings): Map<String, String> {
        val caps = settings.acpCapabilities
        return buildMap {
            put("model", settings.model)
            put("stream", settings.stream.toString())
            put("allowFileSystem", caps.allowFileSystem.toString())
            put("allowTerminal", caps.allowTerminal.toString())
            put("allowApplyPatch", caps.allowApplyPatch.toString())
        }
    }

    override fun dispose() {
        sessionUpdatesJob?.cancel()
        sessionUpdatesJob = null
        scope.cancel("SmithViewModel disposed")
    }
}

private fun JsonNode.readNode(field: String): JsonNode? {
    val child = get(field)
    return if (child == null || child.isNull) null else child
}

private fun JsonNode.readText(field: String): String? = readNode(field)?.asText()

private fun JsonNode.readBoolean(field: String): Boolean? = readNode(field)?.asBoolean()

private fun JsonNode?.arrayElements(): Sequence<JsonNode> =
    if (this != null && this.isArray) this.elements().asSequence() else emptySequence()

private fun JsonNode?.firstTextChunk(): String? {
    if (this == null) return null
    for (element in arrayElements()) {
        if (element.readText("type") == "text") {
            return element.readText("text")
        }
    }
    return null
}
