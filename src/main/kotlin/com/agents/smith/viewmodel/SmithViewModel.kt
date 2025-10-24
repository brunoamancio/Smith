package com.agents.smith.viewmodel

import com.agents.smith.state.SmithState
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.Disposable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

class SmithViewModel(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : Disposable {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val httpClient = OkHttpClient()
    private val mapper = jacksonObjectMapper()

    private val _state = MutableStateFlow(SmithState.initial())
    val state: StateFlow<SmithState> = _state.asStateFlow()

    private val agentMutex = Mutex()
    private var cachedAgentName: String? = null
    private var cachedEndpoint: String = ""
    private var authToken: String = ""

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
            try {
                val agentName = ensureAgent(settings)
                if (agentName == null) {
                    appendSystemMessage("No agents available at ${settings.acpEndpoint}.")
                    updateConnectionStatus(false)
                } else {
                    val response = runSync(agentName, trimmed, settings)
                    if (response != null) {
                        appendMessage(
                            SmithState.Message(
                                role = SmithState.Role.ASSISTANT,
                                content = response,
                                timestamp = Instant.now()
                            )
                        )
                        updateConnectionStatus(true)
                    } else {
                        appendSystemMessage("Agent returned an empty response.")
                    }
                }
            } catch (ex: Exception) {
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

    private suspend fun runSync(agentName: String, message: String, settings: SmithState.Settings): String? {
        val payload = mapper.createObjectNode().apply {
            put("agent_name", agentName)
            put("mode", "sync")
            put("session_id", _state.value.sessionId)
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
            val responseText = response.body?.string() ?: return@executeRequest null
            val root = mapper.readTree(responseText)
            val runNode = root["run"] ?: root

            runNode["session_id"]?.asText()?.takeIf { it.isNotBlank() }?.let { sessionId ->
                _state.update { it.copy(sessionId = sessionId) }
            }

            extractAssistantReply(runNode["output"])
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
}
