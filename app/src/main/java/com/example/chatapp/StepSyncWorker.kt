package com.example.chatapp

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StepSyncWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        try {
            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val prefs = applicationContext.getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
            val steps = prefs.getInt(todayKey, 0)

            FirebaseAuth.getInstance().currentUser?.let { user ->
                FirebaseDatabase.getInstance().reference
                    .child("users")
                    .child(user.uid)
                    .child("stepsData")
                    .child(todayKey)
                    .setValue(steps)
                    .await()
            }

            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
    }
}