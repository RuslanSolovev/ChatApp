package com.example.chatapp.mozgi.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.R
import com.example.chatapp.mozgi.data.QuizSession
import com.example.chatapp.mozgi.data.UserResult
import com.example.chatapp.mozgi.service.FirebaseService
import com.example.chatapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class ResultActivity : AppCompatActivity() {
    private val firebaseService = FirebaseService()
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result_mozgi)

        displayResults()
        saveResult()

        findViewById<Button>(R.id.btnLeaderboard).setOnClickListener {
            val intent = Intent(this, LeaderboardActivity::class.java)
            intent.putExtra("category_id", QuizSession.currentCategory)
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnRestart).setOnClickListener {
            val intent = Intent(this, QuizActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun displayResults() {
        // Используем вопросы из сессии для правильного подсчета
        val (correct, total) = QuizSession.calculateScore()
        val totalTime = QuizSession.totalTime
        val timeInSeconds = totalTime / 1000
        val iq = calculateIQ(correct, total, timeInSeconds)

        findViewById<TextView>(R.id.resultText).text =
            "Правильно: $correct из $total\nВремя: ${timeInSeconds} сек"
        findViewById<TextView>(R.id.iqText).text = "Примерный IQ: $iq"

        // Отладочный вывод
        QuizSession.debugPrintAnswers()
    }

    private fun calculateIQ(correct: Int, total: Int, timeSeconds: Long): Int {
        if (timeSeconds == 0L || total == 0) return 100

        // Базовый IQ: процент правильных ответов * 2 (максимум 200)
        val accuracyPercentage = (correct.toDouble() / total.toDouble()) * 100
        val baseIQ = (accuracyPercentage * 2).toInt()

        // Бонус за скорость: чем быстрее, тем выше IQ
        // Максимальный бонус: 50 пунктов
        val maxTimeForBonus = total * 6L // 6 секунд на вопрос
        val speedBonus = if (timeSeconds < maxTimeForBonus) {
            ((maxTimeForBonus - timeSeconds).toDouble() / maxTimeForBonus.toDouble() * 50).toInt()
        } else {
            0
        }

        // Итоговый IQ: базовый + бонус за скорость
        val finalIQ = baseIQ + speedBonus

        // Ограничиваем диапазон от 50 до 200
        return finalIQ.coerceIn(50, 200)
    }

    private fun saveResult() {
        val currentUser = auth.currentUser ?: return
        val (correct, total) = QuizSession.calculateScore()
        val totalTime = QuizSession.totalTime
        val timeInSeconds = totalTime / 1000
        val iq = calculateIQ(correct, total, timeInSeconds)

        // Получаем данные пользователя из Firebase
        database.reference.child("users").child(currentUser.uid).get()
            .addOnSuccessListener { snapshot ->
                val user = snapshot.getValue(User::class.java)
                val userName = user?.getFullName()?.ifEmpty { currentUser.displayName ?: "Пользователь" }
                    ?: currentUser.displayName ?: "Пользователь"
                val profileImageUrl = user?.profileImageUrl

                val userResult = UserResult(
                    userId = currentUser.uid,
                    userName = userName,
                    userEmail = currentUser.email ?: "",
                    correctAnswers = correct,
                    totalQuestions = total,
                    timeTaken = totalTime,
                    iq = iq,
                    profileImageUrl = profileImageUrl
                )

                firebaseService.saveUserResult(userResult, QuizSession.currentCategory) { success ->
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this, "Результат сохранен!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Ошибка сохранения результата", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .addOnFailureListener {
                // Если не удалось получить данные пользователя, сохраняем с базовой информацией
                val userResult = UserResult(
                    userId = currentUser.uid,
                    userName = currentUser.displayName ?: "Пользователь",
                    userEmail = currentUser.email ?: "",
                    correctAnswers = correct,
                    totalQuestions = total,
                    timeTaken = totalTime,
                    iq = iq
                )

                firebaseService.saveUserResult(userResult, QuizSession.currentCategory) { success ->
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this, "Результат сохранен!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Ошибка сохранения результата", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
    }
}