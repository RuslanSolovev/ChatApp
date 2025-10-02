package com.example.chatapp.models

data class DiscussionMessage(
    val messageId: String = "",
    val discussionId: String = "",
    val senderId: String = "",
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val replyToMessageId: String? = null // ID сообщения, на которое отвечаем
)