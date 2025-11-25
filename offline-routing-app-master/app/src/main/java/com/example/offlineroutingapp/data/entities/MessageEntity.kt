package com.example.offlineroutingapp.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val chatId: String,
    val text: String,
    val isSentByMe: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isImage: Boolean = false,
    val imageData: String? = null,
    val isDelivered: Boolean = false,
    val isSeen: Boolean = false
)