package com.example.chatapp.models

data class Chat(
    val chatId: String = "",
    val lastMessageTimestamp: Long? = null,
    val name: String = "",
    val lastMessage: String = "",
    val participants: Map<String, Boolean> = hashMapOf(),
    val creatorId: String = "",
    val creatorName: String? = null,
    val creatorAvatar: String? = null,
    val imageUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastMessageSenderId: String? = null,
    val lastMessageSenderName: String? = null,
    val lastMessageText: String? = null

)