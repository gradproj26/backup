package com.example.offlineroutingapp

data class Message(
    val text: String,
    val isSentByMe: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isImage: Boolean = false,
    val imageData: String? = null,
    val isDelivered: Boolean = false,
    val isSeen: Boolean = false
)