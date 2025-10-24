package com.agents.smith.acp

import kotlinx.coroutines.flow.Flow

/**
 * Contract for interacting with an Agent Client Protocol endpoint.
 * Implementations will translate these high-level calls into JSON-RPC exchanges.
 */
interface AcpClient {

    /**
     * Perform the `initialize` handshake and return the agent descriptor plus negotiated capabilities.
     */
    suspend fun initialize(params: AcpInitializeParams): AcpInitializeResult

    /**
     * Optionally authenticate the client before session work begins.
     */
    suspend fun authenticate(params: AcpAuthenticateParams): AcpAuthenticateResult

    /**
     * Open a new conversation session. Returns a handle you can use to track updates.
     */
    suspend fun openSession(request: AcpSessionOpenRequest): AcpSessionHandle

    /**
     * Send a prompt (list of messages) to the agent. Returns true if the agent accepted the prompt.
     */
    suspend fun sendPrompt(request: AcpPromptRequest): AcpPromptResult

    /**
     * Cancel an in-flight prompt if the user aborts the request.
     */
    suspend fun cancelPrompt(request: AcpCancelRequest)

    /**
     * Observe streamed updates for a given session.
     */
    fun observeSession(sessionId: String): Flow<AcpSessionUpdate>
}

data class AcpSessionHandle(
    val sessionId: String,
    val negotiatedMode: String?
)

/**
 * A lower-level abstraction for making JSON-RPC calls.
 * Separating it keeps the client testable and lets us swap transports (HTTP, WebSocket, etc.).
 */
interface AcpTransport {
    suspend fun <Params : Any> post(
        path: String,
        payload: AcpJsonRpcRequest<Params>
    ): AcpJsonRpcResponse<RawJson>

    fun sessionUpdates(sessionId: String): Flow<AcpSessionUpdate>
}
