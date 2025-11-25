package com.example.offlineroutingapp

/**
 * Data class representing a chat message in the UI
 * This is separate from MessageEntity which is the database entity
 */
data class Message(
    val id: Long = 0,
    val text: String = "",
    val isSentByMe: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val isImage: Boolean = false,
    val imageData: String? = null,
    val isDelivered: Boolean = false,
    val isSeen: Boolean = false,
    val isFailed: Boolean = false
)