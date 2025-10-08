package com.example.chatapp.models

import android.graphics.Color

data class UserLocation(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val timestamp: Long = 0,
    val accuracy: Float = 0f,
    val speed: Float = 0f,
    val color: Int = Color.BLUE
)