package com.agents.smith.acp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class AcpHttpClient(
    private val transport: AcpTransport,
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
) : AcpClient {

    override suspend fun initialize(params: AcpInitializeParams): AcpInitializeResult {
        val request = AcpJsonRpcRequest(
            method = "initialize",
            params = params,
            id = ""
        )
        val response = transport.post("initialize", request)
        return parseResult(response, AcpInitializeResult::class.java)
    }

    override suspend fun authenticate(params: AcpAuthenticateParams): AcpAuthenticateResult {
        val request = AcpJsonRpcRequest(
            method = "authenticate",
            params = params,
            id = ""
        )
        val response = transport.post("authenticate", request)
        return parseResult(response, AcpAuthenticateResult::class.java)
    }

    override suspend fun openSession(request: AcpSessionOpenRequest): AcpSessionHandle {
        val rpcRequest = AcpJsonRpcRequest(
            method = "session/new",
            params = request,
            id = ""
        )
        val response = transport.post("session/new", rpcRequest)
        val result = parseResult(response, AcpSessionOpenResult::class.java)
        return AcpSessionHandle(
            sessionId = result.sessionId,
            negotiatedMode = result.acceptedMode
        )
    }

    override suspend fun sendPrompt(request: AcpPromptRequest): AcpPromptResult {
        val rpcRequest = AcpJsonRpcRequest(
            method = "session/prompt",
            params = request,
            id = ""
        )
        val response = transport.post("session/prompt", rpcRequest)
        return parseResult(response, AcpPromptResult::class.java)
    }

    override suspend fun cancelPrompt(request: AcpCancelRequest) {
        val rpcRequest = AcpJsonRpcRequest(
            method = "session/cancel",
            params = request,
            id = ""
        )
        val response = transport.post("session/cancel", rpcRequest)
        handleEmptyResult(response)
    }

    override fun observeSession(sessionId: String) = transport.sessionUpdates(sessionId)

    private fun handleEmptyResult(response: AcpJsonRpcResponse<RawJson>) {
        response.error?.let { throw AcpRpcException(it.code, it.message) }
    }

    private fun <T> parseResult(response: AcpJsonRpcResponse<RawJson>, clazz: Class<T>): T {
        response.error?.let { throw AcpRpcException(it.code, it.message) }
        val resultNode = response.result
            ?: throw AcpRpcException(-32603, "Missing result for ${clazz.simpleName}")
        return objectMapper.treeToValue(resultNode, clazz)
    }
}

class AcpRpcException(val errorCode: Int, message: String) : Exception(message)
