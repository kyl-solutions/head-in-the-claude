package com.kylsolutions.kylidescope

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * HTTP + SSE client for communicating with the KYLIDESCOPE relay server.
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
     * Optional workingDir overrides the relay default WORKING_DIR for this message.
     */
    fun sendMessage(
        sessionId: String?,
        message: String,
        workingDir: String? = null
    ): Flow<RelayEvent> = callbackFlow {
        val bodyMap = mutableMapOf(
            "sessionId" to (sessionId ?: ""),
            "message" to message
        )
        if (workingDir != null) bodyMap["workingDir"] = workingDir
        val body = gson.toJson(bodyMap)

        val request = Request.Builder()
            .url("$baseUrl/api/relay/chat")
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
     * Compress an image file to max 1568px on longest side, JPEG 85%.
     * Returns base64-encoded string.
     */
    private fun compressImageToBase64(file: File): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val origW = options.outWidth
        val origH = options.outHeight
        val maxDim = 1568

        // Calculate sample size for memory-efficient decoding
        val sampleSize = if (origW > maxDim || origH > maxDim) {
            val longest = maxOf(origW, origH)
            var sample = 1
            while (longest / sample > maxDim * 2) sample *= 2
            sample
        } else 1

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampled = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: run {
            // Fallback: send raw bytes
            return android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
        }

        // Scale down if still too large
        val bitmap = if (sampled.width > maxDim || sampled.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(sampled.width, sampled.height)
            val newW = (sampled.width * scale).toInt()
            val newH = (sampled.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(sampled, newW, newH, true)
            if (scaled !== sampled) sampled.recycle()
            scaled
        } else sampled

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /**
     * Send a message with an image (screenshot) to Claude via the relay.
     */
    fun sendMessageWithImage(sessionId: String?, message: String, imageFile: File): Flow<RelayEvent> = callbackFlow {
        val imageBase64 = compressImageToBase64(imageFile)

        val body = gson.toJson(mapOf(
            "sessionId" to sessionId,
            "message" to message,
            "image" to imageBase64,
            "mediaType" to "image/jpeg"
        ))

        val request = Request.Builder()
            .url("$baseUrl/api/relay/chat")
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
     * Send a message with multiple image attachments.
     * Each attachment is compressed and sent as part of the images array.
     */
    fun sendMessageWithImages(
        sessionId: String?,
        message: String,
        attachments: List<AttachmentResult>
    ): Flow<RelayEvent> = callbackFlow {
        val imageList = attachments.map { att ->
            val base64 = if (att.mediaType.startsWith("image/")) {
                compressImageToBase64(att.file)
            } else {
                // PDF or other — send raw
                android.util.Base64.encodeToString(att.file.readBytes(), android.util.Base64.NO_WRAP)
            }
            val mediaType = if (att.mediaType.startsWith("image/")) "image/jpeg" else att.mediaType
            mapOf("data" to base64, "mediaType" to mediaType)
        }

        val body = gson.toJson(mapOf(
            "sessionId" to sessionId,
            "message" to message,
            "images" to imageList
        ))

        val request = Request.Builder()
            .url("$baseUrl/api/relay/chat")
            .header("Authorization", "Bearer $authToken")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        activeEventSource = sseFactory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(es: EventSource, response: Response) {
                Log.i(TAG, "SSE multi-image stream opened")
            }

            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                trySend(parseEvent(type, data))
            }

            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = when {
                    t is SocketTimeoutException -> "Connection timed out"
                    t is SocketException -> "Connection lost"
                    t is IOException && t.message?.contains("canceled") == true -> {
                        close(); return
                    }
                    response?.code == 409 -> "Session busy"
                    response != null -> "Server error (${response.code})"
                    t != null -> t.message ?: "Connection failed"
                    else -> "Connection failed"
                }
                Log.w(TAG, "SSE multi-image failure: $errorMsg", t)
                trySend(RelayEvent.Error(errorMsg))
                close()
            }

            override fun onClosed(es: EventSource) { close() }
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
                .url("$baseUrl/api/relay/health")
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
                .url("$baseUrl/api/relay/sessions")
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
                .url("$baseUrl/api/relay/chat/abort")
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
                .url("$baseUrl/api/relay/projects")
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

    suspend fun getProjectContext(projectName: String): ProjectContext? {
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$baseUrl/api/relay/projects/$projectName/context")
                    .header("Authorization", "Bearer $authToken")
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val txt = resp.body?.string() ?: return@withContext null
                val json = JsonParser.parseString(txt).asJsonObject
                if (json.get("found")?.asBoolean == true) {
                    ProjectContext(
                        command = json.get("command")?.asString ?: "",
                        content = json.get("content")?.asString ?: "",
                        path = json.get("path")?.asString ?: ""
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ─── Filesystem Methods ────────────────────────────────────────────

    suspend fun listDirectory(dirPath: String): List<FsEntry> {
        return withContext(Dispatchers.IO) {
            try {
                val url = if (dirPath.isNotEmpty()) {
                    val encodedPath = java.net.URLEncoder.encode(dirPath, "UTF-8")
                    "$baseUrl/api/relay/fs/list?path=$encodedPath"
                } else {
                    "$baseUrl/api/relay/fs/list"  // Let server use its default root
                }
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $authToken")
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val txt = resp.body?.string() ?: return@withContext emptyList()
                val json = JsonParser.parseString(txt).asJsonObject
                val entriesArray = json.getAsJsonArray("entries") ?: return@withContext emptyList()
                parseFsEntries(entriesArray)
            } catch (e: Exception) {
                Log.e(TAG, "listDirectory failed", e)
                emptyList()
            }
        }
    }

    suspend fun readFile(filePath: String): FileContent? {
        return withContext(Dispatchers.IO) {
            try {
                val encodedPath = java.net.URLEncoder.encode(filePath, "UTF-8")
                val req = Request.Builder()
                    .url("$baseUrl/api/relay/fs/read?path=$encodedPath")
                    .header("Authorization", "Bearer $authToken")
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val txt = resp.body?.string() ?: return@withContext null
                val json = JsonParser.parseString(txt).asJsonObject
                if (json.has("error")) return@withContext null
                FileContent(
                    path = json.get("path")?.asString ?: filePath,
                    name = json.get("name")?.asString ?: "",
                    ext = json.get("ext")?.asString ?: "",
                    content = json.get("content")?.asString ?: "",
                    size = json.get("size")?.asLong ?: 0
                )
            } catch (e: Exception) {
                Log.e(TAG, "readFile failed", e)
                null
            }
        }
    }

    private fun parseFsEntries(array: com.google.gson.JsonArray): List<FsEntry> {
        return array.map { elem ->
            val obj = elem.asJsonObject
            FsEntry(
                name = obj.get("name").asString,
                path = obj.get("path").asString,
                type = obj.get("type").asString,
                children = obj.getAsJsonArray("children")?.let { parseFsEntries(it) }
            )
        }
    }

    suspend fun clearAllSessions(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$baseUrl/api/relay/sessions")
                    .header("Authorization", "Bearer $authToken")
                    .delete()
                    .build()
                req.let { client.newCall(it).execute().isSuccessful }
            } catch (e: Exception) {
                false
            }
        }
    }

    // ─── Session Methods ────────────────────────────────────────────

    suspend fun getSessions(): List<SessionInfo> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/relay/sessions")
                .header("Authorization", "Bearer $authToken")
                .get()
                .build()
            val resp = client.newCall(req).execute()
            val txt = resp.body?.string() ?: return@withContext emptyList()
            val json = JsonParser.parseString(txt).asJsonObject
            val arr = json.getAsJsonArray("sessions") ?: return@withContext emptyList()
            arr.map { elem ->
                val obj = elem.asJsonObject
                SessionInfo(
                    id = obj.get("id").asString,
                    title = obj.get("title")?.asString ?: "Untitled",
                    model = obj.get("model")?.asString,
                    messageCount = obj.get("messageCount")?.asInt ?: 0,
                    createdAt = obj.get("createdAt")?.asString ?: "",
                    updatedAt = obj.get("updatedAt")?.asString ?: ""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSessions failed", e)
            emptyList()
        }
    }

    suspend fun getSessionMessages(sessionId: String): List<SessionMessage> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/relay/sessions/$sessionId")
                .header("Authorization", "Bearer $authToken")
                .get()
                .build()
            val resp = client.newCall(req).execute()
            val txt = resp.body?.string() ?: return@withContext emptyList()
            val json = JsonParser.parseString(txt).asJsonObject
            val arr = json.getAsJsonArray("messages") ?: return@withContext emptyList()
            arr.map { elem ->
                val obj = elem.asJsonObject
                SessionMessage(
                    role = obj.get("role")?.asString ?: "user",
                    text = obj.get("text")?.asString ?: ""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSessionMessages failed", e)
            emptyList()
        }
    }

    suspend fun deleteSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/relay/sessions/$sessionId")
                .header("Authorization", "Bearer $authToken")
                .delete()
                .build()
            client.newCall(req).execute().isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "deleteSession failed", e)
            false
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
                "tool_use" -> {
                    val json = JsonParser.parseString(data).asJsonObject
                    val inputMap = mutableMapOf<String, Any?>()
                    json.getAsJsonObject("input")?.entrySet()?.forEach { entry ->
                        inputMap[entry.key] = entry.value?.asString
                    }
                    RelayEvent.ToolUse(
                        name = json.get("name")?.asString ?: "unknown",
                        input = inputMap
                    )
                }
                "tool_result" -> {
                    val json = JsonParser.parseString(data).asJsonObject
                    RelayEvent.ToolResult(
                        name = json.get("name")?.asString ?: "unknown",
                        content = json.get("content")?.asString ?: "",
                        isError = json.get("is_error")?.asBoolean ?: false
                    )
                }
                "tool" -> {
                    // Legacy format fallback
                    val json = JsonParser.parseString(data).asJsonObject
                    RelayEvent.ToolUse(
                        name = json.get("name")?.asString ?: "unknown"
                    )
                }
                "thinking" -> {
                    val json = JsonParser.parseString(data).asJsonObject
                    RelayEvent.Thinking(
                        activity = json.get("activity")?.asString ?: "Processing..."
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
    data class ToolUse(val name: String, val input: Map<String, Any?> = emptyMap()) : RelayEvent()
    data class ToolResult(val name: String, val content: String, val isError: Boolean) : RelayEvent()
    data class Thinking(val activity: String) : RelayEvent()
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

data class ProjectContext(
    val command: String,
    val content: String,
    val path: String
)

/**
 * A file or directory entry from the relay FS endpoints.
 */
data class FsEntry(
    val name: String,
    val path: String,
    val type: String,  // "file" or "directory"
    val children: List<FsEntry>? = null
)

/**
 * File content returned from /relay/fs/read.
 */
data class FileContent(
    val path: String,
    val name: String,
    val ext: String,
    val content: String,
    val size: Long
)

/**
 * Session summary from /relay/sessions listing.
 */
data class SessionInfo(
    val id: String,
    val title: String,
    val model: String?,
    val messageCount: Int,
    val createdAt: String,
    val updatedAt: String
)

/**
 * A single message from session history.
 */
data class SessionMessage(
    val role: String,
    val text: String
)
