package com.example.chatapp.step

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.chatapp.R
import java.util.*

class MotivationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "motivation_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Мотивация и напоминания",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Напоминания о шагах и мотивационные сообщения"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showMotivationNotification(
        currentSteps: Int,
        goal: Int,
        comparison: String,
        streakDays: Int = 0
    ) {
        val title = getMotivationTitle(currentSteps, goal, streakDays)
        val message = getMotivationMessage(currentSteps, goal, comparison)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_steps)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun getMotivationTitle(currentSteps: Int, goal: Int, streakDays: Int): String {
        val progress = currentSteps.toFloat() / goal

        return when {
            streakDays >= 7 -> "🔥 Невероятная серия! $streakDays дней подряд!"
            streakDays >= 3 -> "🎯 Отличная серия! $streakDays дня подряд!"
            progress >= 1.0 -> "🎉 Цель достигнута!"
            progress >= 0.9 -> "🏁 Почти у цели!"
            progress >= 0.5 -> "💪 Хороший темп!"
            else -> "👣 Не забывайте про активность!"
        }
    }

    private fun getMotivationMessage(currentSteps: Int, goal: Int, comparison: String): String {
        val remaining = goal - currentSteps
        val percent = (currentSteps.toFloat() / goal * 100).toInt()

        val messages = mutableListOf<String>()

        if (remaining > 0) {
            messages.add("Осталось $remaining шагов до цели ($percent%)")
        } else {
            messages.add("Вы достигли цели на ${-remaining} шагов больше!")
        }

        messages.add(comparison)

        // Добавляем случайные мотивационные фразы
        val motivationalPhrases = listOf(
            "Каждый шаг приближает вас к здоровью!",
            "Вы делаете это лучше, чем вчера!",
            "Продолжайте в том же духе!",
            "Ваше тело скажет вам спасибо!",
            "Маленькие шаги ведут к большим результатам!"
        )

        messages.add(motivationalPhrases.random())

        return messages.joinToString("\n")
    }

    fun checkForMilestones(steps: Int): List<String> {
        val milestones = listOf(1000, 5000, 10000, 15000, 20000, 25000, 30000)
        val achieved = mutableListOf<String>()

        milestones.forEach { milestone ->
            val key = "milestone_$milestone"
            val prefs = context.getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
            val alreadyAchieved = prefs.getBoolean(key, false)

            if (!alreadyAchieved && steps >= milestone) {
                achieved.add("🎉 Вы достигли $milestone шагов!")
                prefs.edit().putBoolean(key, true).apply()
            }
        }

        return achieved
    }

    fun getDailyTip(): String {
        val tips = listOf(
            "💡 Совет: Поднимайтесь по лестнице вместо лифта",
            "💡 Совет: Прогуляйтесь во время обеденного перерыва",
            "💡 Совет: Паркуйтесь дальше от входа",
            "💡 Совет: Разговаривайте по телефону стоя",
            "💡 Совет: Делайте короткие прогулки каждые 2 часа"
        )
        return tips.random()
    }
}