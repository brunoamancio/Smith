package com.agents.smith.acp

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSource.Factory
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.UUID

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

data class AcpHttpTransportConfig(
    val baseUrl: String,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val sessionUpdatesPathTemplate: String = "session/%s/updates"
)

class AcpHttpTransport(
    private val config: AcpHttpTransportConfig,
    private val client: OkHttpClient = OkHttpClient(),
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper = jacksonObjectMapper()
) : AcpTransport {

    private val baseUrl: HttpUrl = config.baseUrl.trimEnd('/').toHttpUrl()
    private val eventSourceFactory: Factory = EventSources.createFactory(client)

    override suspend fun <Params : Any> post(
        path: String,
        payload: AcpJsonRpcRequest<Params>
    ): AcpJsonRpcResponse<RawJson> = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder()
            .addEncodedPathSegments(path.trimStart('/'))
            .build()

        val envelope = if (payload.id.isBlank()) {
            payload.copy(id = UUID.randomUUID().toString())
        } else {
            payload
        }
        val body = objectMapper.writeValueAsString(envelope)
            .toRequestBody(JSON_MEDIA_TYPE)

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())

        config.defaultHeaders.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val rawBody = response.body?.string()
                ?: throw AcpTransportException("ACP response body was empty for $path")

            val root = objectMapper.readTree(rawBody)

            val rpcResponse = AcpJsonRpcResponse(
                jsonrpc = root["jsonrpc"]?.asText() ?: "2.0",
                result = root["result"],
                error = root["error"]?.takeIf { !it.isNull }?.let {
                    objectMapper.treeToValue(it, AcpJsonRpcError::class.java)
                },
                id = root["id"]?.takeIf { !it.isNull }?.asText()
            )

            if (!response.isSuccessful) {
            val statusMessage = "HTTP ${response.code} when calling $path"
            val message = rpcResponse.error?.let { "$statusMessage: ${it.message}" } ?: statusMessage
            throw AcpTransportException(message)
        }

        rpcResponse
        }
    }

    override fun sessionUpdates(sessionId: String): Flow<AcpSessionUpdate> = callbackFlow {
        val path = config.sessionUpdatesPathTemplate.format(sessionId)
        val url = baseUrl.newBuilder()
            .addEncodedPathSegments(path.trimStart('/'))
            .build()

        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "text/event-stream")

        config.defaultHeaders.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                try {
                    val update = parseSessionUpdate(objectMapper.readTree(data))
                    trySend(update).isSuccess
                } catch (ex: Exception) {
                    close(ex)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                val problem = t ?: IOException("Unknown SSE failure for session $sessionId")
                close(problem)
            }
        }

        val eventSource = eventSourceFactory.newEventSource(requestBuilder.build(), listener)

        awaitClose {
            eventSource.cancel()
        }
    }

    private fun parseSessionUpdate(node: JsonNode): AcpSessionUpdate {
        val sessionId = node["sessionId"]?.asText()
            ?: throw AcpTransportException("Missing sessionId in update payload")
        val promptId = node["promptId"]?.takeIf { !it.isNull }?.asText()
        val eventsNode = node["events"] ?: objectMapper.createArrayNode()

        val events = eventsNode.map { parseSessionEvent(it) }

        return AcpSessionUpdate(
            sessionId = sessionId,
            promptId = promptId,
            events = events
        )
    }

    private fun parseSessionEvent(node: JsonNode): AcpSessionEvent {
        val type = node["type"]?.asText()
        if (type == "message") {
            val messageNode = node["message"]
            val roleText = messageNode?.get("role")?.asText()
            val firstText = messageNode
                ?.get("content")
                ?.firstOrNull { it["type"]?.asText() == "text" }
                ?.get("text")
                ?.asText()

            if (roleText != null && firstText != null) {
                val role = runCatching { AcpMessageRole.valueOf(roleText.uppercase()) }
                    .getOrDefault(AcpMessageRole.ASSISTANT)
                val done = node["done"]?.asBoolean() ?: false
                return AcpSessionEvent.MessageChunk(
                    role = role,
                    content = AcpMessagePart.Text(firstText),
                    done = done
                )
            }
        }

        if (type == "tool_call_started") {
            val callId = node["callId"]?.asText()
            val name = node["name"]?.asText()
            if (callId != null && name != null) {
                val args = convertToMap(node["arguments"])
                return AcpSessionEvent.ToolCallStarted(
                    callId = callId,
                    name = name,
                    arguments = args
                )
            }
        }

        if (type == "tool_call_completed") {
            val callId = node["callId"]?.asText()
            if (callId != null) {
                val result = convertToMap(node["result"])
                return AcpSessionEvent.ToolCallCompleted(
                    callId = callId,
                    result = result
                )
            }
        }

        if (type == "plan_updated") {
            val summary = node["summary"]?.asText()
            if (summary != null) {
                val steps = node["steps"]?.map { it.asText() } ?: emptyList()
                return AcpSessionEvent.PlanUpdated(
                    summary = summary,
                    steps = steps
                )
            }
        }

        if (type == "mode_changed") {
            val nextMode = node["nextMode"]?.asText()
            if (nextMode != null) {
                val previousMode = node["previousMode"]?.asText()
                return AcpSessionEvent.ModeChanged(
                    previousMode = previousMode,
                    nextMode = nextMode
                )
            }
        }

        val raw = convertToMap(node)
        return AcpSessionEvent.Unknown(type = type, raw = raw)
    }

    private fun convertToMap(node: JsonNode?): Map<String, Any?> {
        if (node == null || node.isNull) return emptyMap()
        return objectMapper.convertValue(node, object : TypeReference<Map<String, Any?>>() {})
    }
}

class AcpTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
