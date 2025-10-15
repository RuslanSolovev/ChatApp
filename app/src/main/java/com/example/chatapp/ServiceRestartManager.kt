// ServiceRestartManager.kt
package com.example.chatapp.managers

import android.content.Context
import android.util.Log
import com.example.chatapp.location.LocationServiceManager
import com.example.chatapp.step.StepCounterService
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object ServiceRestartManager {

    private const val TAG = "ServiceRestartManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Перезапуск всех сервисов приложения
     */
    fun restartAllServices(context: Context) {
        scope.launch {
            try {
                Log.d(TAG, "Restarting all services...")

                // Проверяем статус трекинга и перезапускаем сервисы
                val isTrackingActive = checkTrackingStatus(context)

                if (isTrackingActive) {
                    restartLocationService(context)
                }

                // Всегда перезапускаем сервис шагомера
                restartStepService(context)

                Log.d(TAG, "All services restarted successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting services", e)
            }
        }
    }

    /**
     * Остановка только сервиса локации (шагомер не останавливаем)
     */
    fun stopLocationService(context: Context) {
        scope.launch {
            try {
                LocationServiceManager.stopLocationService(context)
                Log.d(TAG, "Location service stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping location service", e)
            }
        }
    }

    // Остальные методы без изменений...
    private suspend fun checkTrackingStatus(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                suspendCoroutine { continuation ->
                    com.example.chatapp.location.LocationServiceManager
                        .isTrackingActive(context) { isTracking ->
                            continuation.resume(isTracking)
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking tracking status", e)
                false
            }
        }
    }

    private fun restartLocationService(context: Context) {
        try {
            LocationServiceManager.startLocationService(context)
            Log.d(TAG, "Location service restarted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart location service", e)
        }
    }

    private fun restartStepService(context: Context) {
        try {
            StepCounterService.startService(context)
            Log.d(TAG, "Step service restarted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart step service", e)
        }
    }
}