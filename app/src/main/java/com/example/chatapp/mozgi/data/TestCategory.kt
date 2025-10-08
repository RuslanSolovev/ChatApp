package com.example.chatapp.mozgi.data

data class TestCategory(
    val id: String,
    val name: String,
    val description: String,
    val icon: Int, // Ресурс иконки
    val questionCount: Int = 50,
    val timePerQuestion: Int = 6 // секунд
)