package com.kylsolutions.hitc

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * Direct client for the Anthropic Messages API.
 * Handles streaming responses via Server-Sent Events (SSE).
 */
class AnthropicClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-5-20250929"
) {
    companion object {
        private const val BASE_URL = "https://api.anthropic.com/v1"
        private const val API_VERSION = "2023-06-01"
        private const val MAX_TOKENS = 4096
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)   // Claude responses can be very long
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val sseFactory = EventSources.createFactory(client)
    private var activeEventSource: EventSource? = null

    /**
     * Send a text message to Claude and stream the response.
     *
     * @param messages Conversation history (role + content pairs)
     * @param systemPrompt Optional system prompt to guide Claude's behavior
     * @return Flow of ClaudeEvent (text chunks, tool use, etc.)
     */
    fun sendMessage(
        messages: List<ApiMessage>,
        systemPrompt: String? = null
    ): Flow<ClaudeEvent> = callbackFlow {
        val requestBody = buildRequestBody(messages, systemPrompt, null)
        val request = buildRequest(requestBody)

        activeEventSource = sseFactory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                // Connection established
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                val event = parseEvent(type, data)
                trySend(event)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = when {
                    response != null -> {
                        val body = response.body?.string() ?: ""
                        val errorJson = try {
                            JsonParser.parseString(body).asJsonObject
                        } catch (e: Exception) {
                            null
                        }
                        errorJson?.get("error")?.asJsonObject?.get("message")?.asString
                            ?: "HTTP ${response.code}: ${response.message}"
                    }
                    t != null -> t.message ?: "Connection failed"
                    else -> "Unknown error"
                }

                trySend(ClaudeEvent.Error(
                    message = errorMsg,
                    code = response?.code
                ))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        })

        awaitClose {
            activeEventSource?.cancel()
            activeEventSource = null
        }
    }

    /**
     * Send a message with an image attachment.
     *
     * @param messages Conversation history
     * @param imageBase64 Base64-encoded image data
     * @param mediaType Image MIME type (e.g., "image/jpeg", "image/png")
     * @param prompt Text prompt to accompany the image
     * @return Flow of ClaudeEvent
     */
    fun sendMessageWithImage(
        messages: List<ApiMessage>,
        imageBase64: String,
        mediaType: String = "image/jpeg",
        prompt: String = "What's in this image?"
    ): Flow<ClaudeEvent> = callbackFlow {
        // Add the image to the last user message
        val imageContent = ImageContent(
            base64 = imageBase64,
            mediaType = mediaType
        )

        val requestBody = buildRequestBody(messages, null, imageContent)
        val request = buildRequest(requestBody)

        activeEventSource = sseFactory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                trySend(parseEvent(type, data))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = t?.message ?: "HTTP ${response?.code ?: "unknown"}"
                trySend(ClaudeEvent.Error(errorMsg, code = response?.code))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        })

        awaitClose {
            activeEventSource?.cancel()
            activeEventSource = null
        }
    }

    /**
     * Abort the currently active request.
     */
    fun abort() {
        activeEventSource?.cancel()
        activeEventSource = null
    }

    /**
     * Build the JSON request body for the Anthropic API.
     */
    private fun buildRequestBody(
        messages: List<ApiMessage>,
        systemPrompt: String?,
        imageContent: ImageContent?
    ): String {
        val body = mutableMapOf<String, Any>(
            "model" to model,
            "max_tokens" to MAX_TOKENS,
            "stream" to true
        )

        if (systemPrompt != null) {
            body["system"] = systemPrompt
        }

        // Convert messages to API format
        val apiMessages = messages.map { msg ->
            val content = mutableListOf<Map<String, Any>>()

            // Add text content
            msg.textContent?.let {
                content.add(mapOf("type" to "text", "text" to it))
            }

            // Add image or document content if present
            msg.imageContent?.let { img ->
                if (img.mediaType == "application/pdf") {
                    // PDFs use the "document" content block type
                    content.add(mapOf(
                        "type" to "document",
                        "source" to mapOf(
                            "type" to "base64",
                            "media_type" to img.mediaType,
                            "data" to img.base64
                        )
                    ))
                } else {
                    // Images use the "image" content block type
                    content.add(mapOf(
                        "type" to "image",
                        "source" to mapOf(
                            "type" to "base64",
                            "media_type" to img.mediaType,
                            "data" to img.base64
                        )
                    ))
                }
            }

            mapOf(
                "role" to msg.role,
                "content" to content
            )
        }

        body["messages"] = apiMessages

        return gson.toJson(body)
    }

    /**
     * Build the HTTP request with headers.
     */
    private fun buildRequest(jsonBody: String): Request {
        return Request.Builder()
            .url("$BASE_URL/messages")
            .header("anthropic-version", API_VERSION)
            .header("x-api-key", apiKey)
            .header("content-type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
    }

    /**
     * Parse SSE event into ClaudeEvent.
     */
    private fun parseEvent(type: String?, data: String): ClaudeEvent {
        return try {
            when (type) {
                "message_start" -> {
                    val json = JsonParser.parseString(data).asJsonObject
                    val message = json.getAsJsonObject("message")
                    ClaudeEvent.MessageStart(
                        id = message.get("id").asString,
                        model = message.get("model").asString,
                        role = message.get("role").asString
                    )
                }

                "content_block_start" -> {
                    val json = JsonParser.parseString(data).asJsonObject
                    val index = json.get("index").asInt
                    val contentBlock = json.getAsJsonObject("content_block")
                    val blockType = contentBlock.get("type").asString
                    ClaudeEvent.ContentBlockStart(index, blockType)
                }

                "content_block_delta" -> {
                    val json = JsonParser.parseString(data).asJsonObject
                    val delta = json.getAsJsonObject("delta")
                    val deltaType = delta.get("type").asString

                    when (deltaType) {
                        "text_delta" -> {
                            val text = delta.get("text").asString
                            ClaudeEvent.TextDelta(text)
                        }
                        "input_json_delta" -> {
                            val partialJson = delta.get("partial_json").asString
                            ClaudeEvent.ToolInputDelta(partialJson)
                        }
                        else -> ClaudeEvent.Ping
                    }
                }

                "content_block_stop" -> {
                    val json = JsonParser.parseString(data).asJsonObject
                    val index = json.get("index").asInt
                    ClaudeEvent.ContentBlockStop(index)
                }

                "message_delta" -> {
                    val json = JsonParser.parseString(data).asJsonObject
                    val delta = json.getAsJsonObject("delta")
                    ClaudeEvent.MessageDelta(
                        stopReason = delta.get("stop_reason")?.takeIf { !it.isJsonNull }?.asString,
                        stopSequence = delta.get("stop_sequence")?.takeIf { !it.isJsonNull }?.asString
                    )
                }

                "message_stop" -> ClaudeEvent.MessageStop

                "ping" -> ClaudeEvent.Ping

                "error" -> {
                    val json = JsonParser.parseString(data).asJsonObject
                    val error = json.getAsJsonObject("error")
                    ClaudeEvent.Error(
                        message = error.get("message").asString,
                        type = error.get("type")?.asString
                    )
                }

                else -> ClaudeEvent.Ping // Unknown event, ignore
            }
        } catch (e: Exception) {
            ClaudeEvent.Error("Failed to parse event: ${e.message}")
        }
    }
}

/**
 * Message format for the Anthropic API.
 */
data class ApiMessage(
    val role: String, // "user" or "assistant"
    val textContent: String? = null,
    val imageContent: ImageContent? = null
)

/**
 * Image content for vision requests.
 */
data class ImageContent(
    val base64: String,
    val mediaType: String = "image/jpeg"
)
