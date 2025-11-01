package com.example.chatapp.privetstvie_giga

data class GigaMessage(
    val text: String = "",
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis() // ДОБАВЛЯЕМ ТАЙМСТАМП
)