package com.example.chatapp.models

import com.google.firebase.database.IgnoreExtraProperties
import com.google.firebase.database.PropertyName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@IgnoreExtraProperties
data class User(
    var uid: String = "",
    var email: String = "",
    var name: String = "",
    var lastName: String = "",
    var middleName: String = "",
    var additionalInfo: String = "",
    var profileImageUrl: String? = null,
    var isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),




    @get:PropertyName("isPlaying")
    @set:PropertyName("isPlaying")
    var isPlaying: Boolean = false,



    // Исправление: сопоставление с Firebase-полем "online"
    @get:PropertyName("online")
    @set:PropertyName("online")
    var online: Boolean = false,

    val fcmToken: String? = null,
    val lastActive: Long? = null,

    // Ключевое изменение: тип Any вместо Int
    var stepsData: Map<String, Any> = emptyMap(),
    val maxDailySteps: Int = 0,
    val lastStepsUpdate: Long = 0,
    var totalSteps: Int = 0,
    var position: Int = 0,
    val lastLocation: UserLocation? = null
) {
    fun getTodaySteps(): Int {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val value = stepsData[today]
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            else -> 0
        }
    }

    fun getFullName(): String {
        return listOf(lastName, name, middleName)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }
}