package com.example.chatapp

// NetworkManager.kt
import com.example.chatapp.models.User
import com.example.chatapp.models.UserLocation
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object NetworkManager {
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Кэш для пользователей
    private val userCache = mutableMapOf<String, User?>()

    suspend fun loadUserData(userId: String): User? = withContext(Dispatchers.IO) {
        // Проверяем кэш
        userCache[userId]?.let { cachedUser ->
            return@withContext cachedUser
        }

        try {
            val snapshot = FirebaseDatabase.getInstance()
                .reference
                .child("users")
                .child(userId)
                .get()
                .await()
            val user = snapshot.getValue(User::class.java)
            // Сохраняем в кэш
            userCache[userId] = user
            user
        } catch (e: Exception) {
            userCache[userId] = null
            null
        }
    }

    // Метод для очистки кэша (например, при выходе из аккаунта)
    fun clearUserCache() {
        userCache.clear()
    }

    suspend fun loadUserLocations(userId: String): List<UserLocation> = withContext(Dispatchers.IO) {
        try {
            val snapshot = FirebaseDatabase.getInstance()
                .reference
                .child("user_locations")
                .get()
                .await()

            val locations = mutableListOf<UserLocation>()
            for (child in snapshot.children) {
                val location = child.getValue(UserLocation::class.java)
                location?.let { locations.add(it) }
            }
            locations
        } catch (e: Exception) {
            emptyList()
        }
    }
}