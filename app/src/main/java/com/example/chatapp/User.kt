package com.example.chatapp.models

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class User(
    var uid: String = "",
    var email: String = "",
    var name: String = "",
    var firstName: String = "",
    var lastName: String = "",
    var middleName: String = "",
    var additionalInfo: String = "",
    var profileImageUrl: String? = null,
    var isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val isOnline: Boolean = false,
    val fcmToken: String? = null,
    val lastActive: Long? = null,

    var stepsData: Map<String, Int> = emptyMap(), // Формат: "yyyy-MM-dd" to steps
    val maxDailySteps: Int = 0,
    val lastStepsUpdate: Long = 0,
    var totalSteps: Int = 0,

    val locationSettings: LocationSettings? = null,
    val lastLocation: UserLocation? = null
){
    // Функция для получения полного имени
    fun getTodaySteps(): Int {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return stepsData[today] ?: 0
    }

    fun getFullName(): String {
        return if (firstName.isNotEmpty() && lastName.isNotEmpty()) {
            "$lastName $firstName ${middleName.takeIf { it.isNotEmpty() } ?: ""}".trim()
        } else {
            name
        }
    }
}