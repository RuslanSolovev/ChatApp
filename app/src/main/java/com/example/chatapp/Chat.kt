

package com.example.chatapp.models

data class Chat(
    val id: String = "",
    val name: String = "",
    val lastMessage: String = "",
    val participants: Map<String, Boolean> = hashMapOf(),
    val creatorId: String = "",
    val creatorName: String? = null,
    val creatorAvatar: String? = null,
    val imageUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val replyToMessageId: String? = null,  // ID сообщения, на которое отвечаем
    val replyToMessageText: String? = null, // Текст сообщения, на которое отвечаем
    val replyToSenderName: String? = null   // Имя отправителя исходного сообщения

)