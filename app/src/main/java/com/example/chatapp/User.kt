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

    @get:PropertyName("online")
    @set:PropertyName("online")
    var online: Boolean = false,

    val fcmToken: String? = null,
    val lastActive: Long? = null,

    var stepsData: Map<String, Any> = emptyMap(),
    val maxDailySteps: Int = 0,
    val lastStepsUpdate: Long = 0,
    var totalSteps: Int = 0,
    var position: Int = 0,
    val lastLocation: UserLocation? = null,

    // СТАТИСТИКА ИГР
    @get:PropertyName("rating")
    @set:PropertyName("rating")
    var rating: Int = 0, // ЕДИНЫЙ РЕЙТИНГ ДЛЯ ВСЕХ УРОВНЕЙ

    @get:PropertyName("gamesPlayed")
    @set:PropertyName("gamesPlayed")
    var gamesPlayed: Int = 0,

    @get:PropertyName("gamesWon")
    @set:PropertyName("gamesWon")
    var gamesWon: Int = 0,

    @get:PropertyName("totalScore")
    @set:PropertyName("totalScore")
    var totalScore: Int = 0, // СУММА ВСЕХ ОЧКОВ

    @get:PropertyName("bestScore")
    @set:PropertyName("bestScore")
    var bestScore: Int = 0, // ЛУЧШИЙ СЧЕТ ЗА ОДНУ ИГРУ

    @get:PropertyName("bestLevel")
    @set:PropertyName("bestLevel")
    var bestLevel: Int = 0, // ЛУЧШИЙ УРОВЕНЬ

    @get:PropertyName("achievements")
    @set:PropertyName("achievements")
    var achievements: List<String> = emptyList(),

    @get:PropertyName("preferredDifficulty")
    @set:PropertyName("preferredDifficulty")
    var preferredDifficulty: String = "medium",

    @get:PropertyName("lastGameScore")
    @set:PropertyName("lastGameScore")
    var lastGameScore: Int = 0,

    @get:PropertyName("lastGameLevel")
    @set:PropertyName("lastGameLevel")
    var lastGameLevel: Int = 0,

    @get:PropertyName("lastGameDate")
    @set:PropertyName("lastGameDate")
    var lastGameDate: Long = 0

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

    fun getWinRate(): Double {
        return if (gamesPlayed > 0) {
            (gamesWon.toDouble() / gamesPlayed * 100)
        } else {
            0.0
        }
    }

    fun getAverageScore(): Int {
        return if (gamesPlayed > 0) {
            totalScore / gamesPlayed
        } else {
            0
        }
    }

    fun getLevel(): String {
        return when {
            rating >= 10000 -> "Легенда 🏆"
            rating >= 7000 -> "Мастер 💎"
            rating >= 5000 -> "Эксперт 🔥"
            rating >= 3000 -> "Опытный ⭐"
            rating >= 1500 -> "Новичок 🌱"
            else -> "Начинающий 🎯"
        }
    }

    fun getLastGameDateFormatted(): String {
        return if (lastGameDate > 0) {
            val date = Date(lastGameDate)
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(date)
        } else {
            "Еще не играл"
        }
    }

    // Проверяет, является ли текущий результат лучшим
    fun isNewBestScore(score: Int, level: Int): Boolean {
        return score > bestScore || (score == bestScore && level > bestLevel)
    }

    // Обновление рейтинга (единый для всех уровней)
    fun calculateNewRating(score: Int, level: Int, isWin: Boolean): Int {
        val baseRating = if (score > bestScore) score else bestScore
        val winBonus = if (isWin) 300 else 0
        val levelBonus = level * 20

        return baseRating + winBonus + levelBonus
    }
}