package com.example.chatapp.mozgi.data

data class Question(
    val id: Int = 0,
    val subject: String = "",
    val questionText: String = "",
    val options: List<String> = emptyList(),
    val correctAnswerIndex: Int = 0
)