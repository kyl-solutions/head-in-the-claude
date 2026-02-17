package com.kylsolutions.hitc

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HTTP + SSE client for communicating with the HITC relay server.
 * Replaces the SSH connection for Claude interactions.
 */
class RelayClient(
    private val baseUrl: String,
    private val authToken: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)   // Claude responses can be long
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val sseFactory = EventSources.createFactory(client)
    private var activeEventSource: EventSource? = null

    /**
     * Send a text message to Claude via the relay. Returns a Flow of events.
     */
    fun sendMessage(sessionId: String?, message: String): Flow<RelayEvent> = callbackFlow {
        val body = gson.toJson(mapOf(
            "sessionId" to sessionId,
            "message" to message
        ))

        val request = Request.Builder()
            .url("$baseUrl/api/chat")
            .header("Authorization", "Bearer $authToken")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        activeEventSource = sseFactory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                val event = parseEvent(type, data)
                trySend(event)
            }

            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                trySend(RelayEvent.Error(t?.message ?: "Connection failed (${response?.code ?: "unknown"})"))
                close()
            }

            override fun onClosed(es: EventSource) {
                close()
            }
        })

        awaitClose {
            activeEventSource?.cancel()
            activeEventSource = null
        }
    }

    /**
     * Send a message with an image (screenshot) to Claude via the relay.
     */
    fun sendMessageWithImage(sessionId: String?, message: String, imageFile: File): Flow<RelayEvent> = callbackFlow {
        val imageBytes = imageFile.readBytes()
        val imageBase64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)

        val body = gson.toJson(mapOf(
            "sessionId" to sessionId,
            "message" to message,
            "image" to imageBase64
        ))

        val request = Request.Builder()
            .url("$baseUrl/api/chat")
            .header("Authorization", "Bearer $authToken")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        activeEventSource = sseFactory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                trySend(parseEvent(type, data))
            }

            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                trySend(RelayEvent.Error(t?.message ?: "Connection failed"))
                close()
            }

            override fun onClosed(es: EventSource) {
                close()
            }
        })

        awaitClose {
            activeEventSource?.cancel()
            activeEventSource = null
        }
    }

    /**
     * Health check — returns true if relay is reachable.
     */
    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/health")
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Create a new conversation session.
     */
    suspend fun createSession(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/sessions")
                .header("Authorization", "Bearer $authToken")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val json = JsonParser.parseString(response.body?.string()).asJsonObject
            json.get("sessionId")?.asString
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Abort the currently running Claude request for a session.
     */
    suspend fun abort(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            activeEventSource?.cancel()
            activeEventSource = null

            val body = gson.toJson(mapOf("sessionId" to sessionId))
            val request = Request.Builder()
                .url("$baseUrl/api/chat/abort")
                .header("Authorization", "Bearer $authToken")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fetch project list from relay server.
     */
    suspend fun getProjects(): List<ProjectInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/projects")
                .header("Authorization", "Bearer $authToken")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            val json = JsonParser.parseString(body).asJsonObject
            val projectsArray = json.getAsJsonArray("projects") ?: return@withContext emptyList()

            projectsArray.map { elem ->
                val obj = elem.asJsonObject
                val lastCommit = obj.get("lastCommit")?.let {
                    if (it.isJsonObject) it.asJsonObject else null
                }
                ProjectInfo(
                    name = obj.get("name").asString,
                    path = obj.get("path").asString,
                    type = obj.get("type")?.asString ?: "unknown",
                    lastCommitTimestamp = lastCommit?.get("timestamp")?.asLong,
                    lastCommitMessage = lastCommit?.get("message")?.asString
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseEvent(type: String?, data: String): RelayEvent {
        return try {
            when (type) {
                "session" -> {
                    val json = JsonParser.parseString(data).asJsonObject
                    RelayEvent.Session(
                        sessionId = json.get("sessionId").asString,
                        isNew = json.get("isNew").asBoolean
                    )
                }
                "chunk" -> {
                    val json = JsonParser.parseString(data).asJsonObject
                    val text = json.get("text")?.asString ?: ""
                    val chunkType = json.get("type")?.asString ?: "text"
                    RelayEvent.TextChunk(text, chunkType)
                }
                "tool" -> {
                    val json = JsonParser.parseString(data).asJsonObject
                    RelayEvent.ToolUse(
                        name = json.get("name")?.asString ?: "unknown"
                    )
                }
                "done" -> RelayEvent.Done
                "error" -> {
                    val json = JsonParser.parseString(data).asJsonObject
                    RelayEvent.Error(json.get("message")?.asString ?: "Unknown error")
                }
                else -> RelayEvent.TextChunk(data, "raw")
            }
        } catch (e: Exception) {
            RelayEvent.TextChunk(data, "raw")
        }
    }
}

/**
 * Events received from the relay server SSE stream.
 */
sealed class RelayEvent {
    data class Session(val sessionId: String, val isNew: Boolean) : RelayEvent()
    data class TextChunk(val text: String, val chunkType: String = "text") : RelayEvent()
    data class ToolUse(val name: String) : RelayEvent()
    data class Error(val message: String) : RelayEvent()
    object Done : RelayEvent()
}

/**
 * A project folder discovered by the relay server.
 */
data class ProjectInfo(
    val name: String,
    val path: String,
    val type: String,
    val lastCommitTimestamp: Long? = null,
    val lastCommitMessage: String? = null
)
