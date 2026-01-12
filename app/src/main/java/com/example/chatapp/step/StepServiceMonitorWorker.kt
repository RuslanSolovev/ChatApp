package com.example.chatapp.step

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import com.google.firebase.database.FirebaseDatabase

class StepServiceMonitorWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "StepServiceMonitorWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "StepServiceMonitorWorker started")

            // Проверяем состояние сервиса
            val isRunning = checkIfServiceRunning()

            if (!isRunning) {
                Log.w(TAG, "Step counter service is NOT running, restarting...")
                restartStepService()
                logRestartEvent()
            } else {
                Log.d(TAG, "Step counter service is running OK")
                // Синхронизируем данные
                syncStepsData()
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in StepServiceMonitorWorker", e)
            Result.retry()
        }
    }

    private fun checkIfServiceRunning(): Boolean {
        return try {
            val manager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
            manager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == StepCounterService::class.java.name }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking service status", e)
            false
        }
    }

    private suspend fun restartStepService() {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                Log.d(TAG, "Restarting step counter service...")
                StepCounterService.startService(applicationContext)
                delay(2000) // Даем время на запуск
                Log.d(TAG, "Step counter service restart command sent")
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting step service", e)
            }
        }
    }

    private suspend fun syncStepsData() {
        try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid
            if (userId != null) {
                // Здесь можно добавить синхронизацию данных
                Log.d(TAG, "Syncing steps data for user: $userId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing steps data", e)
        }
    }

    private fun logRestartEvent() {
        try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid
            if (userId != null) {
                val eventData = mapOf(
                    "timestamp" to System.currentTimeMillis(),
                    "event" to "service_restarted_by_worker"
                )
                FirebaseDatabase.getInstance().reference
                    .child("users").child(userId).child("serviceEvents")
                    .push()
                    .setValue(eventData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error logging restart event", e)
        }
    }
}