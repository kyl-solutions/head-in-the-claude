package com.kylsolutions.kylidescope

/**
 * Events received from the Anthropic API SSE stream.
 */
sealed class ClaudeEvent {
    /**
     * Message started - contains the message ID.
     */
    data class MessageStart(val id: String, val model: String, val role: String = "assistant") : ClaudeEvent()

    /**
     * Text content chunk from the assistant's response.
     */
    data class TextDelta(val text: String) : ClaudeEvent()

    /**
     * Tool use started - Claude is calling a tool.
     */
    data class ToolUseStart(val id: String, val name: String) : ClaudeEvent()

    /**
     * Tool use input chunk (JSON being built).
     */
    data class ToolInputDelta(val partialJson: String) : ClaudeEvent()

    /**
     * Tool use completed.
     */
    data class ToolUseEnd(val id: String) : ClaudeEvent()

    /**
     * Content block started (text or tool_use).
     */
    data class ContentBlockStart(val index: Int, val type: String) : ClaudeEvent()

    /**
     * Content block stopped.
     */
    data class ContentBlockStop(val index: Int) : ClaudeEvent()

    /**
     * Message completed with metadata.
     */
    data class MessageDelta(val stopReason: String?, val stopSequence: String?) : ClaudeEvent()

    /**
     * Message stream ended.
     */
    object MessageStop : ClaudeEvent()

    /**
     * Ping event (keep-alive).
     */
    object Ping : ClaudeEvent()

    /**
     * Error occurred during streaming.
     */
    data class Error(
        val message: String,
        val type: String? = null,
        val code: Int? = null
    ) : ClaudeEvent()
}
