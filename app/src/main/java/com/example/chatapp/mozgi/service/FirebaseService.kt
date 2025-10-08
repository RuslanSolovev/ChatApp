package com.example.chatapp.mozgi.service

import com.example.chatapp.mozgi.data.UserResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class FirebaseService {
    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    fun saveUserResult(result: UserResult, categoryId: String = "general", onComplete: (Boolean) -> Unit) {
        val userId = auth.currentUser?.uid ?: return

        // Получаем текущий лучший результат пользователя для этой категории
        val userBestResultRef = database.getReference("user_best_results_$categoryId").child(userId)

        userBestResultRef.get().addOnSuccessListener { snapshot ->
            val currentBest = snapshot.getValue(UserResult::class.java)
            var shouldUpdate = false

            println("DEBUG: Сохранение результата для категории $categoryId")
            println("DEBUG: Новый результат: ${result.correctAnswers}/${result.totalQuestions}")
            println("DEBUG: Текущий лучший: ${currentBest?.correctAnswers ?: "нет"}/${currentBest?.totalQuestions ?: "нет"}")

            if (currentBest == null) {
                // Нет предыдущих результатов
                shouldUpdate = true
                println("DEBUG: Нет предыдущих результатов - сохраняем")
            } else {
                // Сравниваем по количеству правильных ответов и времени
                if (result.correctAnswers > currentBest.correctAnswers) {
                    shouldUpdate = true
                    println("DEBUG: Больше правильных ответов - обновляем")
                } else if (result.correctAnswers == currentBest.correctAnswers) {
                    // Если правильных ответов поровну, смотрим время (меньше - лучше)
                    if (result.timeTaken < currentBest.timeTaken) {
                        shouldUpdate = true
                        println("DEBUG: Столько же правильных, но быстрее - обновляем")
                    } else {
                        println("DEBUG: Не лучше предыдущего результата")
                    }
                } else {
                    println("DEBUG: Меньше правильных ответов - не обновляем")
                }
            }

            if (shouldUpdate) {
                userBestResultRef.setValue(result) { error, _ ->
                    println("DEBUG: Результат сохранен с ошибкой: $error")
                    onComplete(error == null)
                }
            } else {
                onComplete(true)
            }
        }.addOnFailureListener {
            println("DEBUG: Ошибка получения предыдущих результатов: ${it.message}")
            // Если ошибка получения, сохраняем как новый результат
            userBestResultRef.setValue(result) { error, _ ->
                println("DEBUG: Результат сохранен с ошибкой: $error")
                onComplete(error == null)
            }
        }
    }

    fun getLeaderboard(limit: Int = 10, categoryId: String = "general", onComplete: (List<UserResult>) -> Unit) {
        val ref = database.getReference("user_best_results_$categoryId")

        ref.get().addOnSuccessListener { snapshot ->
            val results = mutableListOf<UserResult>()

            for (child in snapshot.children) {
                try {
                    child.getValue(UserResult::class.java)?.let { result ->
                        results.add(result)
                    }
                } catch (e: Exception) {
                    // Игнорируем некорректные данные
                }
            }

            // Сортировка: сначала по количеству правильных ответов (по убыванию),
            // затем по времени (по возрастанию)
            results.sortWith(
                compareByDescending<UserResult> { it.correctAnswers }
                    .thenBy { it.timeTaken }
            )

            println("DEBUG: Загружено ${results.size} результатов для категории $categoryId")
            onComplete(results.take(limit))
        }.addOnFailureListener {
            println("DEBUG: Ошибка загрузки лидерборда: ${it.message}")
            onComplete(emptyList())
        }
    }
}