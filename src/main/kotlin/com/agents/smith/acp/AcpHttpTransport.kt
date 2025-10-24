package com.agents.smith.acp

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.util.UUID
import kotlin.sequences.asSequence

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

data class AcpHttpTransportConfig(
    val baseUrl: String,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val sessionUpdatesPathTemplate: String = "session/%s/updates"
)

class AcpHttpTransport(
    private val config: AcpHttpTransportConfig,
    private val client: OkHttpClient = OkHttpClient(),
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper = jacksonObjectMapper(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : AcpTransport {

    private val baseUrl: HttpUrl = config.baseUrl.trimEnd('/').toHttpUrl()

    override suspend fun <Params : Any> post(
        path: String,
        payload: AcpJsonRpcRequest<Params>
    ): AcpJsonRpcResponse<RawJson> = withContext(dispatcher) {
        val url = baseUrl.newBuilder()
            .addEncodedPathSegments(path.trimStart('/'))
            .build()

        val envelope = if (payload.id.isBlank()) {
            payload.copy(id = UUID.randomUUID().toString())
        } else {
            payload
        }

        val requestBody = objectMapper.writeValueAsString(envelope)
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .apply {
                config.defaultHeaders.forEach { (key, value) ->
                    header(key, value)
                }
            }
            .build()

        client.newCall(request).execute().use { response ->
            val rawBody = response.body?.string()
                ?: throw AcpTransportException("ACP response body was empty for $path")

            val root = objectMapper.readTree(rawBody)

            val rpcResponse = AcpJsonRpcResponse(
                jsonrpc = root.readText("jsonrpc") ?: "2.0",
                result = root.readNode("result"),
                error = root.readNode("error")?.let { node ->
                    objectMapper.treeToValue(node, AcpJsonRpcError::class.java)
                },
                id = root.readText("id")
            )

            if (!response.isSuccessful) {
                val statusMessage = "HTTP ${response.code} when calling $path"
                val message = rpcResponse.error?.let { "$statusMessage: ${it.message}" } ?: statusMessage
                throw AcpTransportException(message)
            }

            rpcResponse
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun sessionUpdates(sessionId: String): Flow<AcpSessionUpdate> = callbackFlow {
        val path = config.sessionUpdatesPathTemplate.format(sessionId)
        val url = baseUrl.newBuilder()
            .addEncodedPathSegments(path.trimStart('/'))
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "text/event-stream")
            .apply {
                config.defaultHeaders.forEach { (key, value) ->
                    header(key, value)
                }
            }
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body ?: run {
                        close(AcpTransportException("ACP session updates response was empty for $sessionId"))
                        return
                    }

                    try {
                        consumeEventStream(body.charStream().buffered()) { payload ->
                            val update = parseSessionUpdate(objectMapper.readTree(payload))
                            if (!isClosedForSend) {
                                trySend(update).isSuccess
                            }
                        }

                        if (!isClosedForSend) {
                            close()
                        }
                    } catch (ex: Exception) {
                        close(ex)
                    }
                }
            }
        })

        awaitClose {
            call.cancel()
        }
    }

    private fun parseSessionUpdate(node: JsonNode): AcpSessionUpdate {
        val sessionId = node.readText("sessionId")
            ?: throw AcpTransportException("Missing sessionId in update payload")
        val promptId = node.readText("promptId")
        val events = node.readNode("events")
            .arrayElements()
            .map { parseSessionEvent(it) }
            .toList()

        return AcpSessionUpdate(
            sessionId = sessionId,
            promptId = promptId,
            events = events
        )
    }

    private fun parseSessionEvent(node: JsonNode): AcpSessionEvent {
        return parseMessageEvent(node)
            ?: parseToolCallStarted(node)
            ?: parseToolCallCompleted(node)
            ?: parsePlanUpdated(node)
            ?: parseModeChanged(node)
            ?: AcpSessionEvent.Unknown(
                type = node.readText("type"),
                raw = convertToMap(node)
            )
    }

    private fun parseMessageEvent(node: JsonNode): AcpSessionEvent? {
        val messageNode = node.readNode("message") ?: return null
        val roleText = messageNode.readText("role") ?: return null
        val textContent = messageNode.readNode("content").firstTextChunk() ?: return null
        val role = runCatching { AcpMessageRole.valueOf(roleText.uppercase()) }
            .getOrDefault(AcpMessageRole.ASSISTANT)
        val done = node.readBoolean("done") ?: false
        return AcpSessionEvent.MessageChunk(
            role = role,
            content = AcpMessagePart.Text(textContent),
            done = done
        )
    }

    private fun parseToolCallStarted(node: JsonNode): AcpSessionEvent? {
        val callId = node.readText("callId") ?: return null
        val name = node.readText("name") ?: return null
        val arguments = convertToMap(node.readNode("arguments"))
        return AcpSessionEvent.ToolCallStarted(callId = callId, name = name, arguments = arguments)
    }

    private fun parseToolCallCompleted(node: JsonNode): AcpSessionEvent? {
        val callId = node.readText("callId") ?: return null
        val result = convertToMap(node.readNode("result"))
        return AcpSessionEvent.ToolCallCompleted(callId = callId, result = result)
    }

    private fun parsePlanUpdated(node: JsonNode): AcpSessionEvent? {
        val summary = node.readText("summary") ?: return null
        val steps = node.readNode("steps")
            .arrayElements()
            .map { it.asText() }
            .toList()
        return AcpSessionEvent.PlanUpdated(summary = summary, steps = steps)
    }

    private fun parseModeChanged(node: JsonNode): AcpSessionEvent? {
        val nextMode = node.readText("nextMode") ?: return null
        val previousMode = node.readText("previousMode")
        return AcpSessionEvent.ModeChanged(previousMode = previousMode, nextMode = nextMode)
    }

    private fun convertToMap(node: JsonNode?): Map<String, Any?> {
        if (node == null) return emptyMap()
        return objectMapper.convertValue(node, object : TypeReference<Map<String, Any?>>() {})
    }

    private fun consumeEventStream(reader: BufferedReader, onData: (String) -> Unit) {
        val dataBuilder = StringBuilder()

        fun flushEvent() {
            if (dataBuilder.isNotEmpty()) {
                onData(dataBuilder.toString().trimEnd())
                dataBuilder.setLength(0)
            }
        }

        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    flushEvent()
                    continue
                }
                if (line.startsWith("data:")) {
                    val payload = line.removePrefix("data:").trimStart()
                    dataBuilder.append(payload).append('\n')
                }
            }
        } catch (ex: IOException) {
            throw AcpTransportException("Failed to read ACP SSE stream", ex)
        }

        if (dataBuilder.isNotEmpty()) {
            onData(dataBuilder.toString().trimEnd())
        }
    }
}

class AcpTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

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
