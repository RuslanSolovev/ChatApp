package com.example.chatapp.models

import android.graphics.Color
import com.google.firebase.database.PropertyName

data class UserLocation(
    @PropertyName("lat")
    val lat: Double = 0.0,

    @PropertyName("lng")
    val lng: Double = 0.0,

    @PropertyName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @PropertyName("color")
    val color: Int = Color.BLUE,

    @PropertyName("accuracy")
    val accuracy: Float = 0f,

    // Добавьте поля которые видны в логах
    @PropertyName("speed")
    val speed: Float = 0f,

    // Другие возможные поля
    @PropertyName("bearing")
    val bearing: Float = 0f,

    @PropertyName("altitude")
    val altitude: Double = 0.0,

    @PropertyName("provider")
    val provider: String = ""
) {
    // Пустой конструктор для Firebase
    constructor() : this(0.0, 0.0, 0L, Color.BLUE, 0f, 0f, 0f, 0.0, "")

    // Функция для проверки валидности локации
    fun isValid(): Boolean {
        return lat != 0.0 && lng != 0.0 && timestamp > 0
    }

    // Функция для получения точки
    fun toPoint(): com.yandex.mapkit.geometry.Point {
        return com.yandex.mapkit.geometry.Point(lat, lng)
    }
}