package com.kylsolutions.kylidescope

/**
 * Message format for API history. Used by SessionRepository
 * to convert Room messages into sendable format.
 */
data class ApiMessage(
    val role: String,
    val textContent: String?,
    val imageContent: ImageContent? = null
)

data class ImageContent(
    val base64: String,
    val mediaType: String
)
