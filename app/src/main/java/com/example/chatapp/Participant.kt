package com.example.chatapp

data class Participant(
    val userId: String,
    val name: String,
    var profileImageUrl: String? = null,
)