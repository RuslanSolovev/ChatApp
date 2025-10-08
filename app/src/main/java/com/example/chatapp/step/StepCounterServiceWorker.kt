package com.example.chatapp.step

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class StepCounterServiceWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val sharedPreferences = context.getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
    private val firebaseDatabase = FirebaseDatabase.getInstance()

    override suspend fun doWork(): Result {
        return try {
            Log.d("StepCounterWorker", "Запуск периодической синхронизации шагов")

            // Синхронизируем сегодняшние шаги
            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val todaySteps = sharedPreferences.getInt(todayKey, 0)

            if (todaySteps > 0) {
                synchronizeWithFirebase(todayKey, todaySteps)
            }

            // Синхронизируем исторические данные
            synchronizeHistoricalData()

            Log.d("StepCounterWorker", "Периодическая синхронизация завершена успешно")
            Result.success()
        } catch (e: Exception) {
            Log.e("StepCounterWorker", "Ошибка периодической синхронизации", e)
            Result.retry()
        }
    }

    private suspend fun synchronizeWithFirebase(date: String, steps: Int) {
        FirebaseAuth.getInstance().currentUser?.let { user ->
            firebaseDatabase.reference
                .child("users")
                .child(user.uid)
                .child("stepsData")
                .child(date)
                .setValue(steps)
                .await()
        }
    }

    private suspend fun synchronizeHistoricalData() {
        val allEntries = sharedPreferences.all
        FirebaseAuth.getInstance().currentUser?.let { user ->
            val batchUpdates = hashMapOf<String, Any>()

            allEntries.forEach { entry ->
                if (entry.key is String && (entry.key as String).matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    val date = entry.key as String
                    val steps = entry.value as? Int ?: 0
                    batchUpdates["users/${user.uid}/stepsData/$date"] = steps
                }
            }

            if (batchUpdates.isNotEmpty()) {
                firebaseDatabase.reference.updateChildren(batchUpdates).await()
            }
        }
    }
}