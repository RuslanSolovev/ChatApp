package com.example.chatapp.models

import java.text.SimpleDateFormat
import java.util.*

data class Message(
    val id: String = "",
    val text: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val timestamp: Long = System.currentTimeMillis(),



    val imageUrl: String? = null,
    val replyToMessageId: String? = null,
    val replyToMessageText: String? = null,
    val replyToSenderName: String? = null,
    val isEdited: Boolean = false,
    val status: MessageStatus = MessageStatus.SENT,
    val messageType: MessageType = MessageType.TEXT
) {
    // Пустой конструктор для Firebase
    constructor() : this("", "", "", "", 0L, null, null, null, null, false, MessageStatus.SENT, MessageType.TEXT)

    enum class MessageStatus {
        SENT, DELIVERED, READ
    }

    enum class MessageType {
        TEXT, IMAGE, VIDEO, AUDIO, FILE
    }

    fun getFormattedTime(): String {
        val date = Date(timestamp)
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        return format.format(date)
    }

    fun isMyMessage(currentUserId: String): Boolean {
        return senderId == currentUserId
    }
}