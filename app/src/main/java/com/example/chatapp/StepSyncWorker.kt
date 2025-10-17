package com.example.chatapp

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class StepSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "StepSyncWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "StepSyncWorker started")

            val prefs = applicationContext.getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
            val todayDateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val todaySteps = prefs.getInt(todayDateKey, 0)

            if (todaySteps > 0) {
                syncWithFirebase(todayDateKey, todaySteps)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "StepSyncWorker failed", e)
            Result.retry()
        }
    }

    private suspend fun syncWithFirebase(date: String, steps: Int) {
        try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return

            FirebaseDatabase.getInstance().reference
                .child("users")
                .child(userId)
                .child("stepsData")
                .child(date)
                .setValue(steps)
                .await()

            Log.d(TAG, "Steps synced: $steps for $date")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing steps", e)
            throw e
        }
    }
}