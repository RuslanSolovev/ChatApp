package com.example.chatapp.step

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class StepSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val stepDataManager = StepDataManager.getInstance(applicationContext)
            val database = Firebase.database
            val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid

            if (userId != null) {
                // Синхронизируем последние 7 дней
                val calendar = Calendar.getInstance()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

                repeat(7) {
                    val dateKey = dateFormat.format(calendar.time)
                    val steps = stepDataManager.getSteps(dateKey)

                    if (steps > 0) {
                        database.reference
                            .child("users")
                            .child(userId)
                            .child("stepsData")
                            .child(dateKey)
                            .setValue(steps)
                            .await()
                    }

                    calendar.add(Calendar.DATE, -1)
                }

                Log.d("StepSyncWorker", "Синхронизация завершена")
                Result.success()
            } else {
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e("StepSyncWorker", "Ошибка синхронизации", e)
            Result.retry()
        }
    }
}