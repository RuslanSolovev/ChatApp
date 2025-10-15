package com.example.chatapp.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.chatapp.managers.ServiceRestartManager

class ServiceMonitorWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Log.d("ServiceMonitorWorker", "Checking services status...")

            // Проверяем и перезапускаем сервисы если нужно
            ServiceRestartManager.restartAllServices(applicationContext)

            Result.success()
        } catch (e: Exception) {
            Log.e("ServiceMonitorWorker", "Error monitoring services", e)
            Result.retry()
        }
    }
}