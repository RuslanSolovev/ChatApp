package com.example.chatapp


data class NotificationRequest(
    val app_id: String,
    val include_player_ids: List<String>,
    val contents: Map<String, String>,
    val headings: Map<String, String>? = null,
    val android_channel_id: String? = null,
    val small_icon: String? = null,
    val data: Map<String, String>? = null
)