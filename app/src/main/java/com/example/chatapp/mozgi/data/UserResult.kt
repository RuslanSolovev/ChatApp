package com.example.chatapp.mozgi.data

data class UserResult(
    val userId: String = "",
    val userName: String = "",
    val userEmail: String = "",
    val correctAnswers: Int = 0,
    val totalQuestions: Int = 50,
    val timeTaken: Long = 0, // в миллисекундах
    val iq: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val profileImageUrl: String? = null
) {
    // Обязательный пустой конструктор для Firebase
    constructor() : this("", "", "", 0, 50, 0, 0, System.currentTimeMillis(), null)
}