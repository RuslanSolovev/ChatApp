// LocationSettings.kt
package com.example.chatapp.models

data class LocationSettings(
    val enabled: Boolean = false,
    val visibility: String = "friends", // everyone, friends, none
    val updateInterval: Int = 1 // minutes
)