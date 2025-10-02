package com.example.chatapp.models

data class Discussion(
    val discussionId: String = "",
    val title: String = "", // переименуем в name для консистентности
    val creatorId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var lastMessageTimestamp: Long? = null,
    var lastMessageText: String? = null, // добавим новое поле
    var participantCount: Int = 0,
    var messageCount: Int = 0
)