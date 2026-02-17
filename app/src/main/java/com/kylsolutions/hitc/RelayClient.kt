package com.kylsolutions.hitc

import android.util.Log
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
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * HTTP + SSE client for communicating with the HITC relay server.
 * Replaces the SSH connection for Claude interactions.
 */
class RelayClient(
    private val baseUrl: String,
    private val authToken: String
) {
    companion object {
        private const val TAG = "RelayClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)     // SSE streams are indefinite — no read timeout
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)   // TCP-level keepalive every 20s
        .retryOnConnectionFailure(true)
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
            override fun onOpen(es: EventSource, response: Response) {
                Log.i(TAG, "SSE stream opened")
            }

            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                val event = parseEvent(type, data)
                trySend(event)
            }

            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = when {
                    t is SocketTimeoutException -> "Connection timed out — relay may be unreachable"
                    t is SocketException -> "Connection lost — network may have changed"
                    t is IOException && t.message?.contains("canceled") == true -> {
                        // Normal cancellation (abort, new session) — not a real error
                        Log.d(TAG, "SSE stream cancelled (normal)")
                        close()
                        return
                    }
                    response?.code == 409 -> "Session busy — another request is active"
                    response != null -> "Server error (${response.code})"
                    t != null -> t.message ?: "Connection failed"
                    else -> "Connection failed (unknown)"
                }
                Log.w(TAG, "SSE failure: $errorMsg", t)
                trySend(RelayEvent.Error(errorMsg))
                close()
            }

            override fun onClosed(es: EventSource) {
                Log.d(TAG, "SSE stream closed normally")
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
            override fun onOpen(es: EventSource, response: Response) {
                Log.i(TAG, "SSE image stream opened")
            }

            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                trySend(parseEvent(type, data))
            }

            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = when {
                    t is SocketTimeoutException -> "Connection timed out — relay may be unreachable"
                    t is SocketException -> "Connection lost — network may have changed"
                    t is IOException && t.message?.contains("canceled") == true -> {
                        Log.d(TAG, "SSE image stream cancelled (normal)")
                        close()
                        return
                    }
                    response?.code == 409 -> "Session busy — another request is active"
                    response != null -> "Server error (${response.code})"
                    t != null -> t.message ?: "Connection failed"
                    else -> "Connection failed (unknown)"
                }
                Log.w(TAG, "SSE image failure: $errorMsg", t)
                trySend(RelayEvent.Error(errorMsg))
                close()
            }

            override fun onClosed(es: EventSource) {
                Log.d(TAG, "SSE image stream closed normally")
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
