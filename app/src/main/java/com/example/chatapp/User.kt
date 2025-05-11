package com.example.chatapp.models

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
    val lastActive: Long? = null
){
    // Функция для получения полного имени
    fun getFullName(): String {
        return if (firstName.isNotEmpty() && lastName.isNotEmpty()) {
            "$lastName $firstName ${middleName.takeIf { it.isNotEmpty() } ?: ""}".trim()
        } else {
            name
        }
    }
}