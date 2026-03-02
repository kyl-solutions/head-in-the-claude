package com.kylsolutions.kylidescope.repository

import com.kylsolutions.kylidescope.ApiMessage
import com.kylsolutions.kylidescope.ImageContent
import com.kylsolutions.kylidescope.database.Conversation
import com.kylsolutions.kylidescope.database.ConversationDatabase
import com.kylsolutions.kylidescope.database.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repository for managing conversations and messages.
 * Handles all database operations and data transformations.
 */
class SessionRepository(private val db: ConversationDatabase) {

    private val conversationDao = db.conversationDao()
    private val messageDao = db.messageDao()

    /**
     * Create a new conversation.
     * If title is null, it will be auto-generated from the first user message.
     */
    suspend fun createConversation(
        title: String = "New Conversation",
        model: String = "claude-sonnet-4-5-20250929"
    ): Conversation = withContext(Dispatchers.IO) {
        val conversation = Conversation(
            title = title,
            model = model
        )
        conversationDao.insertConversation(conversation)
        // Keep max 3 conversations — prune oldest after creating new one
        pruneOldConversations(MAX_CONVERSATIONS)
        conversation
    }

    companion object {
        const val MAX_CONVERSATIONS = 3
    }

    /**
     * Get a conversation by ID.
     */
    suspend fun getConversation(id: String): Conversation? = withContext(Dispatchers.IO) {
        conversationDao.getConversation(id)
    }

    /**
     * Get the most recent conversation (for auto-load on app start).
     */
    suspend fun getMostRecentConversation(): Conversation? = withContext(Dispatchers.IO) {
        conversationDao.getMostRecentConversation()
    }

    /**
     * Get all conversations as a Flow (for conversation list UI).
     */
    fun getAllConversations(): Flow<List<Conversation>> {
        return conversationDao.getAllConversations()
    }

    /**
     * Delete a conversation and all its messages.
     */
    suspend fun deleteConversation(id: String) = withContext(Dispatchers.IO) {
        conversationDao.deleteConversationById(id)
    }

    /**
     * Update conversation title.
     */
    suspend fun updateTitle(conversationId: String, title: String) = withContext(Dispatchers.IO) {
        val conversation = conversationDao.getConversation(conversationId)
        conversation?.let {
            conversationDao.updateConversation(it.copy(
                title = title,
                updatedAt = System.currentTimeMillis()
            ))
        }
    }

    /**
     * Add a user message to a conversation.
     */
    suspend fun addUserMessage(
        conversationId: String,
        content: String,
        imagePath: String? = null,
        mediaType: String? = null
    ): Message = withContext(Dispatchers.IO) {
        val message = Message(
            conversationId = conversationId,
            role = "user",
            content = content,
            hasImage = imagePath != null,
            imagePath = imagePath,
            mediaType = mediaType
        )
        messageDao.insertMessage(message)

        // Update conversation metadata
        updateConversationMetadata(conversationId)

        // Auto-generate title from first user message
        autoGenerateTitle(conversationId, content)

        message
    }

    /**
     * Add an assistant message to a conversation.
     */
    suspend fun addAssistantMessage(
        conversationId: String,
        content: String
    ): Message = withContext(Dispatchers.IO) {
        val message = Message(
            conversationId = conversationId,
            role = "assistant",
            content = content
        )
        messageDao.insertMessage(message)

        // Update conversation metadata
        updateConversationMetadata(conversationId)

        message
    }

    /**
     * Get all messages for a conversation as a Flow.
     */
    fun getMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesForConversation(conversationId)
    }

    /**
     * Get message history in API format (for sending to Claude).
     * Returns the most recent N messages.
     */
    suspend fun getMessageHistory(
        conversationId: String,
        limit: Int = 50
    ): List<ApiMessage> = withContext(Dispatchers.IO) {
        val messages = messageDao.getRecentMessages(conversationId, limit)
            .asReversed() // Room returns DESC, we need ASC for API

        messages.map { msg ->
            val imageContent = if (msg.hasImage && msg.imagePath != null) {
                val file = File(msg.imagePath)
                if (file.exists()) {
                    val imageBytes = file.readBytes()
                    val base64 = android.util.Base64.encodeToString(
                        imageBytes,
                        android.util.Base64.NO_WRAP
                    )
                    ImageContent(base64 = base64, mediaType = msg.mediaType ?: "image/jpeg")
                } else {
                    null
                }
            } else {
                null
            }

            ApiMessage(
                role = msg.role,
                textContent = msg.content,
                imageContent = imageContent
            )
        }
    }

    /**
     * Update conversation metadata (updatedAt, messageCount).
     */
    private suspend fun updateConversationMetadata(conversationId: String) {
        val conversation = conversationDao.getConversation(conversationId)
        val messageCount = messageDao.getMessageCount(conversationId)

        conversation?.let {
            conversationDao.updateConversation(it.copy(
                updatedAt = System.currentTimeMillis(),
                messageCount = messageCount
            ))
        }
    }

    /**
     * Auto-generate conversation title from the first user message.
     */
    private suspend fun autoGenerateTitle(conversationId: String, userMessage: String) {
        val conversation = conversationDao.getConversation(conversationId)
        if (conversation?.title == "New Conversation") {
            // Truncate to 40 chars and clean up
            val title = userMessage
                .take(40)
                .trim()
                .let { if (userMessage.length > 40) "$it..." else it }

            conversationDao.updateConversation(conversation.copy(title = title))
        }
    }

    /**
     * Delete all conversations and their messages.
     */
    suspend fun deleteAllConversations() = withContext(Dispatchers.IO) {
        conversationDao.deleteAllConversations()
    }

    /**
     * Prune old conversations, keeping only the most recent N.
     * Cascade delete removes all associated messages.
     */
    suspend fun pruneOldConversations(keepCount: Int = 3) = withContext(Dispatchers.IO) {
        val idsToDelete = conversationDao.getConversationIdsToprune(keepCount)
        if (idsToDelete.isNotEmpty()) {
            conversationDao.deleteConversationsByIds(idsToDelete)
        }
    }

    /**
     * Check if there are any conversations.
     */
    suspend fun hasConversations(): Boolean = withContext(Dispatchers.IO) {
        conversationDao.getConversationCount() > 0
    }

    /**
     * Get message count for a conversation.
     */
    suspend fun getMessageCount(conversationId: String): Int = withContext(Dispatchers.IO) {
        messageDao.getMessageCount(conversationId)
    }
}
