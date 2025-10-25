package com.agents.smith.viewmodel

import com.agents.smith.acp.AcpHttpTransport
import com.agents.smith.acp.AcpHttpTransportConfig
import com.agents.smith.acp.AcpMessageRole
import com.agents.smith.acp.AcpSessionEvent
import com.agents.smith.acp.AcpSessionUpdate
import com.agents.smith.state.SmithState
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

class SmithViewModel(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : Disposable {

    private val logger = Logger.getInstance(SmithViewModel::class.java)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val httpClient = OkHttpClient()
    private val mapper = jacksonObjectMapper()

    private val _state = MutableStateFlow(SmithState.initial())
    val state: StateFlow<SmithState> = _state.asStateFlow()

    private val agentMutex = Mutex()
    private var cachedAgentName: String? = null
    private var cachedEndpoint: String = ""
    private var authToken: String = ""
    private var sessionStreamJob: Job? = null
    private var activeStreamSessionId: String? = null
    private var streamingMessageIndex: Int? = null
    private var streamingBuffer: StringBuilder = StringBuilder()
    private var streamingHasContent: Boolean = false
    private var streamingSignal: CompletableDeferred<Boolean>? = null

    fun updateConnectionStatus(connected: Boolean) {
        _state.update { it.copy(connected = connected) }
    }

    fun updateSettings(settings: SmithState.Settings, token: String) {
        val normalized = settings.copy(acpEndpoint = settings.acpEndpoint.trim())
        authToken = token
        _state.update { it.copy(settings = normalized) }
        scope.launch {
            agentMutex.withLock {
                if (cachedEndpoint != normalized.acpEndpoint) {
                    cachedEndpoint = normalized.acpEndpoint
                    cachedAgentName = null
                    sessionStreamJob?.cancel()
                    sessionStreamJob = null
                    activeStreamSessionId = null
                }
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
            appendMessage(
                SmithState.Message(
                    role = SmithState.Role.USER,
                    content = trimmed,
                    timestamp = Instant.now()
                )
            )

            _state.update { it.copy(streaming = true) }

            val settings = _state.value.settings
            val clientSessionId = _state.value.sessionId
            try {
                val agentName = ensureAgent(settings)
                if (agentName == null) {
                    appendSystemMessage("No agents available at ${settings.acpEndpoint}.")
                    updateConnectionStatus(false)
                } else {
                    ensureSessionStream(settings, clientSessionId)
                    beginAssistantStream()

                    val result = runSync(agentName, trimmed, settings, clientSessionId)
                    val reply = result.reply
                    val sessionId = result.sessionId ?: clientSessionId

                    ensureSessionStream(settings, sessionId)

                    val streamStarted = streamingHasContent || streamingSignal?.isCompleted == true

                    if (!streamStarted) {
                        if (!reply.isNullOrBlank()) {
                            replaceAssistantStream(reply)
                            resolveStreamingSignal(false)
                            endAssistantStream()
                        } else {
                            removeEmptyAssistantMessage()
                        }
                    }

                    updateConnectionStatus(true)
                }
            } catch (ex: Exception) {
                removeEmptyAssistantMessage()
                appendSystemMessage("Failed to contact ACP agent: ${ex.message}")
                updateConnectionStatus(false)
            } finally {
                _state.update { it.copy(streaming = false) }
            }
        }
    }

    suspend fun testConnection(
        settings: SmithState.Settings,
        token: String
    ): AcpConnectionTestResult {
        val normalized = settings.copy(acpEndpoint = settings.acpEndpoint.trim())
        if (normalized.acpEndpoint.isBlank()) {
            return AcpConnectionTestResult(false, "Enter an ACP endpoint URL.")
        }

        return try {
            ping(normalized, token)
            val agents = fetchAgents(normalized, token)
            if (agents.isEmpty()) {
                AcpConnectionTestResult(false, "No agents reported by ${normalized.acpEndpoint}.")
            } else {
                val message = buildString {
                    append("Connected. Agents: ")
                    append(agents.joinToString(", "))
                }
                AcpConnectionTestResult(true, message)
            }
        } catch (ex: Exception) {
            AcpConnectionTestResult(false, ex.message ?: "Unknown error")
        }
    }

    private suspend fun ensureAgent(settings: SmithState.Settings): String? {
        return agentMutex.withLock {
            if (cachedAgentName != null && cachedEndpoint == settings.acpEndpoint) {
                return@withLock cachedAgentName
            }
            cachedEndpoint = settings.acpEndpoint
            val agents = fetchAgents(settings, authToken)
            cachedAgentName = agents.firstOrNull()
            cachedAgentName
        }
    }

    private suspend fun ping(settings: SmithState.Settings, token: String) {
        val request = Request.Builder()
            .url(buildUrl(settings.acpEndpoint, "/ping"))
            .get()
            .applyAuth(token)
            .build()

        executeRequest(request) { response ->
            if (!response.isSuccessful) {
                throw IOException("Ping failed with HTTP ${response.code}")
            }
        }
    }

    private suspend fun fetchAgents(settings: SmithState.Settings, token: String): List<String> {
        val request = Request.Builder()
            .url(buildUrl(settings.acpEndpoint, "/agents"))
            .get()
            .applyAuth(token)
            .build()

        return executeRequest(request) { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to list agents (HTTP ${response.code})")
            }
            val body = response.body?.string() ?: throw IOException("Empty response from /agents")
            val node = mapper.readTree(body)
            node["agents"]
                ?.takeIf { it.isArray }
                ?.mapNotNull { it["name"]?.asText() }
                ?: emptyList()
        }
    }

    private suspend fun runSync(
        agentName: String,
        message: String,
        settings: SmithState.Settings,
        clientSessionId: String
    ): RunSyncResult {
        val payload = mapper.createObjectNode().apply {
            put("agent_name", agentName)
            put("mode", "sync")
            put("session_id", clientSessionId)
            set<JsonNode>(
                "input",
                mapper.createArrayNode().add(createUserMessageNode(message))
            )
        }

        val request = Request.Builder()
            .url(buildUrl(settings.acpEndpoint, "/runs"))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .applyAuth(authToken)
            .build()

        return executeRequest(request) { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()?.take(200)
                throw IOException("Run failed (HTTP ${response.code}): ${errorBody ?: "no body"}")
            }
            val responseText = response.body?.string()
            if (responseText.isNullOrBlank()) {
                return@executeRequest RunSyncResult(null, null)
            }
            val root = mapper.readTree(responseText)
            val runNode = root["run"] ?: root

            val updatedSessionId = runNode["session_id"]?.asText()?.takeIf { it.isNotBlank() }
            updatedSessionId?.let { sessionId ->
                _state.update { it.copy(sessionId = sessionId) }
            }

            val reply = extractAssistantReply(runNode["output"])
            RunSyncResult(reply, updatedSessionId)
        }
    }

    private fun createUserMessageNode(message: String): JsonNode {
        return mapper.createObjectNode().apply {
            put("role", "user")
            set<JsonNode>(
                "parts",
                mapper.createArrayNode().add(
                    mapper.createObjectNode().apply {
                        put("content_type", "text/plain")
                        put("content", message)
                        put("content_encoding", "plain")
                    }
                )
            )
        }
    }

    private fun extractAssistantReply(outputNode: JsonNode?): String? {
        if (outputNode == null || !outputNode.isArray) return null
        val builder = StringBuilder()
        outputNode.forEach { messageNode ->
            val parts = messageNode["parts"]
            parts?.forEach { part ->
                val contentType = part["content_type"]?.asText() ?: ""
                if (contentType.startsWith("text")) {
                    val content = part["content"]?.asText()
                    if (!content.isNullOrBlank()) {
                        if (builder.isNotEmpty()) builder.append("\n")
                        builder.append(content)
                    }
                }
            }
        }
        return builder.toString().ifBlank { null }
    }

    private fun appendMessage(message: SmithState.Message) {
        _state.update { it.copy(history = it.history + message) }
    }

    private fun appendSystemMessage(text: String) {
        appendMessage(
            SmithState.Message(
                role = SmithState.Role.SYSTEM,
                content = text,
                timestamp = Instant.now()
            )
        )
    }

    override fun dispose() {
        sessionStreamJob?.cancel()
        sessionStreamJob = null
        scope.cancel("SmithViewModel disposed")
    }

    private suspend fun <T> executeRequest(request: Request, block: (okhttp3.Response) -> T): T {
        return withContext(dispatcher) {
            httpClient.newCall(request).execute().use(block)
        }
    }

    private fun Request.Builder.applyAuth(token: String = authToken): Request.Builder = apply {
        if (token.isNotBlank()) {
            header("Authorization", "Bearer $token")
        }
        header("Accept", "application/json")
    }

    private fun buildUrl(base: String, path: String): String {
        val sanitized = base.removeSuffix("/")
        return "$sanitized$path"
    }

    data class AcpConnectionTestResult(
        val success: Boolean,
        val message: String
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private data class RunSyncResult(
        val reply: String?,
        val sessionId: String?
    )

    private fun ensureSessionStream(settings: SmithState.Settings, sessionId: String) {
        if (sessionId.isBlank()) return
        if (sessionStreamJob?.isActive == true && activeStreamSessionId == sessionId) {
            return
        }

        sessionStreamJob?.cancel()
        val headers = mutableMapOf<String, String>()
        if (authToken.isNotBlank()) {
            headers["Authorization"] = "Bearer $authToken"
        }

        val transport = AcpHttpTransport(
            config = AcpHttpTransportConfig(
                baseUrl = settings.acpEndpoint,
                defaultHeaders = headers,
                readTimeoutSeconds = settings.acpReadTimeoutSeconds.toLong()
            )
        )

        sessionStreamJob = scope.launch {
            try {
                transport.sessionUpdates(sessionId).collect { update ->
                    handleSessionUpdate(update)
                }
            } catch (_: Exception) {
                // Ignore streaming errors; request flow will surface issues separately.
            }
        }
        activeStreamSessionId = sessionId
    }

    private fun handleSessionUpdate(update: AcpSessionUpdate) {
        logger.warn("Received session update: sessionId=${update.sessionId}, events=${update.events}")
        update.events.forEach { event ->
            when (event) {
                is AcpSessionEvent.MessageChunk -> handleMessageChunk(event)
                else -> {}
            }
        }
    }

    private fun handleMessageChunk(event: AcpSessionEvent.MessageChunk) {
        if (event.role != AcpMessageRole.ASSISTANT) return
        val text = event.content.text
        if (streamingMessageIndex == null) {
            beginAssistantStream()
        }
        logger.warn("Streaming chunk received (done=${event.done}): '$text'")
        streamingBuffer.append(text)
        if (text.isNotBlank()) {
            streamingHasContent = true
            streamingSignal?.let { signal ->
                if (!signal.isCompleted) {
                    signal.complete(true)
                }
            }
        }
        replaceAssistantStream(streamingBuffer.toString())
        if (event.done) {
            val hadContent = streamingHasContent
            resolveStreamingSignal(hadContent)
            endAssistantStream()
            _state.update { it.copy(streaming = false) }
        } else {
            _state.update { it.copy(streaming = true) }
        }
    }

    private fun beginAssistantStream() {
        streamingSignal?.cancel()
        streamingBuffer = StringBuilder()
        streamingHasContent = false
        streamingSignal = CompletableDeferred()
        var insertIndex = 0
        val timestamp = Instant.now()
        _state.update { current ->
            val newHistory = current.history + SmithState.Message(
                role = SmithState.Role.ASSISTANT,
                content = "",
                timestamp = timestamp
            )
            insertIndex = newHistory.lastIndex
            current.copy(history = newHistory)
        }
        streamingMessageIndex = insertIndex
    }

    private fun replaceAssistantStream(content: String) {
        val index = streamingMessageIndex ?: return
        if (content.isNotBlank()) {
            streamingHasContent = true
        }
        _state.update { current ->
            val history = current.history.toMutableList()
            if (index < history.size) {
                val existing = history[index]
                history[index] = existing.copy(content = content, timestamp = Instant.now())
                current.copy(history = history)
            } else {
                current
            }
        }
    }

    private fun endAssistantStream() {
        streamingSignal?.cancel()
        streamingSignal = null
        streamingMessageIndex = null
        streamingBuffer = StringBuilder()
        streamingHasContent = false
    }

    private fun removeEmptyAssistantMessage() {
        val index = streamingMessageIndex ?: return
        _state.update { current ->
            val history = current.history.toMutableList()
            if (index < history.size && history[index].content.isBlank()) {
                history.removeAt(index)
                current.copy(history = history)
            } else {
                current
            }
        }
        resolveStreamingSignal(false)
        endAssistantStream()
    }

    private fun resolveStreamingSignal(result: Boolean) {
        streamingSignal?.let { signal ->
            if (!signal.isCompleted) {
                signal.complete(result)
            }
        }
        streamingSignal = null
    }
}
