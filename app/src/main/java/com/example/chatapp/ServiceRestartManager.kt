// ServiceRestartManager.kt
package com.example.chatapp.managers

import android.content.Context
import android.util.Log
import com.example.chatapp.location.LocationServiceManager
import com.example.chatapp.step.StepCounterService
import kotlinx.coroutines.*

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

                // Всегда перезапускаем сервис локации (трекинг всегда активен)
                restartLocationService(context)

                // Всегда перезапускаем сервис шагомера
                restartStepService(context)

                Log.d(TAG, "All services restarted successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting services", e)
            }
        }
    }

    /**
     * Перезапуск только сервиса локации
     */
    fun restartLocationServiceOnly(context: Context) {
        scope.launch {
            try {
                restartLocationService(context)
                Log.d(TAG, "Location service restarted")
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting location service", e)
            }
        }
    }

    /**
     * Перезапуск только сервиса шагомера
     */
    fun restartStepServiceOnly(context: Context) {
        scope.launch {
            try {
                restartStepService(context)
                Log.d(TAG, "Step service restarted")
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting step service", e)
            }
        }
    }

    /**
     * Удаляем методы остановки сервисов, так как они теперь всегда должны работать
     * Оставьте только перезапуск
     */

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

    /**
     * Упрощенная версия - просто запускаем все сервисы
     */
    fun ensureAllServicesRunning(context: Context) {
        scope.launch {
            try {
                // Всегда запускаем оба сервиса
                restartLocationService(context)
                restartStepService(context)
                Log.d(TAG, "All services ensured to be running")
            } catch (e: Exception) {
                Log.e(TAG, "Error ensuring services running", e)
            }
        }
    }

    /**
     * Универсальный метод для перезапуска сервисов
     */
    fun restartServices(context: Context, includeLocation: Boolean = true, includeStep: Boolean = true) {
        scope.launch {
            try {
                if (includeLocation) {
                    restartLocationService(context)
                }
                if (includeStep) {
                    restartStepService(context)
                }
                Log.d(TAG, "Services restarted: location=$includeLocation, step=$includeStep")
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting services", e)
            }
        }
    }

    /**
     * Метод для очистки ресурсов при завершении работы приложения
     */
    fun cleanup() {
        try {
            scope.coroutineContext.cancelChildren()
            Log.d(TAG, "ServiceRestartManager cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up ServiceRestartManager", e)
        }
    }
}