package com.example.chatapp

import com.example.chatapp.models.GigaMessage
import java.io.Serializable

data class SavedDialog(
    val id: String = System.currentTimeMillis().toString(),
    val title: String,
    val messages: List<GigaMessage>,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable