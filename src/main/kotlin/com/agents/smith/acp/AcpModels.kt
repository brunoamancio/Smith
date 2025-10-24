package com.agents.smith.acp

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/**
 * Data structures that mirror the core payloads in the Agent Client Protocol (ACP).
 * These models are intentionally minimal while we experiment with the protocol.
 * The shapes can be tightened once we wire in real serialization.
 */

const val DEFAULT_ACP_PROTOCOL_VERSION: String = "2024-09-18"

data class AcpClientDescriptor(
    val name: String,
    val version: String,
    val vendor: String? = null,
    val capabilities: Map<String, String> = emptyMap()
)

data class AcpAgentDescriptor(
    val name: String,
    val version: String,
    val capabilities: Map<String, String> = emptyMap()
)

data class AcpClientCapabilities(
    val supportsFileSystem: Boolean = false,
    val supportsTerminal: Boolean = false,
    val supportsApplyPatch: Boolean = false,
    val extra: Map<String, String> = emptyMap()
)

data class AcpInitializeParams(
    val protocolVersion: String = DEFAULT_ACP_PROTOCOL_VERSION,
    val client: AcpClientDescriptor,
    val capabilities: AcpClientCapabilities = AcpClientCapabilities()
)

data class AcpInitializeResult(
    val agent: AcpAgentDescriptor,
    val negotiatedProtocolVersion: String,
    val capabilities: Map<String, Any?> = emptyMap()
)

data class AcpAuthenticateParams(
    val token: String
)

data class AcpAuthenticateResult(
    val authenticated: Boolean,
    val expiresAt: Instant? = null
)

data class AcpSessionOpenRequest(
    val clientSessionId: String,
    val metadata: Map<String, String> = emptyMap(),
    val mode: String? = null
)

data class AcpSessionOpenResult(
    val sessionId: String,
    val acceptedMode: String? = null
)

data class AcpPromptRequest(
    val sessionId: String,
    val promptId: String,
    val messages: List<AcpMessage>,
    val metadata: Map<String, String> = emptyMap()
)

data class AcpPromptResult(
    val sessionId: String,
    val promptId: String,
    val accepted: Boolean
)

data class AcpCancelRequest(
    val sessionId: String,
    val promptId: String
)

data class AcpMessage(
    val role: AcpMessageRole,
    val content: List<AcpMessagePart>,
    val timestamp: Instant? = null
)

enum class AcpMessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
    THOUGHT
}

sealed interface AcpMessagePart {
    data class Text(val text: String) : AcpMessagePart
    data class Code(val language: String?, val text: String) : AcpMessagePart
    data class Json(val payload: Map<String, Any?>) : AcpMessagePart
}

data class AcpSessionUpdate(
    val sessionId: String,
    val promptId: String?,
    val events: List<AcpSessionEvent>
)

sealed interface AcpSessionEvent {
    data class MessageChunk(
        val role: AcpMessageRole,
        val content: AcpMessagePart.Text,
        val done: Boolean = false
    ) : AcpSessionEvent

    data class ToolCallStarted(
        val callId: String,
        val name: String,
        val arguments: Map<String, Any?> = emptyMap()
    ) : AcpSessionEvent

    data class ToolCallCompleted(
        val callId: String,
        val result: Map<String, Any?> = emptyMap()
    ) : AcpSessionEvent

    data class PlanUpdated(
        val summary: String,
        val steps: List<String> = emptyList()
    ) : AcpSessionEvent

    data class ModeChanged(
        val previousMode: String?,
        val nextMode: String
    ) : AcpSessionEvent

    data class Unknown(
        val type: String?,
        val raw: Map<String, Any?>
    ) : AcpSessionEvent
}

/**
 * Lightweight envelope for JSON-RPC requests and responses.
 * We will plug in proper (de)serialisation later.
 */
data class AcpJsonRpcRequest<T>(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: T?,
    val id: String
)

data class AcpJsonRpcResponse<T>(
    val jsonrpc: String = "2.0",
    val result: T?,
    val error: AcpJsonRpcError? = null,
    val id: String?
)

data class AcpJsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null
)

typealias RawJson = JsonNode
