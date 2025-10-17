package com.example.chatapp


import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.chatapp.location.LocationUpdateService
import com.example.chatapp.step.StepCounterService

object ForegroundServiceLauncher {
    private const val TAG = "ForegroundServiceLauncher"

    /**
     * Безопасный запуск Foreground Service с проверкой разрешений
     */
    fun startStepCounterService(context: Context) {
        try {
            Log.d(TAG, "Attempting to start StepCounterService...")

            if (canStartForegroundService(context)) {
                StepCounterService.startService(context)
                Log.d(TAG, "StepCounterService started successfully")
            } else {
                Log.w(TAG, "Cannot start StepCounterService - foreground service not allowed")
                // Сохраняем состояние для запуска позже
                savePendingServiceState(context, "step", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start StepCounterService", e)
            savePendingServiceState(context, "step", false)
        }
    }

    fun startLocationService(context: Context) {
        try {
            Log.d(TAG, "Attempting to start LocationService...")

            if (canStartForegroundService(context)) {
                val intent = Intent(context, LocationUpdateService::class.java).apply {
                    action = LocationUpdateService.ACTION_START
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "LocationService started successfully")
            } else {
                Log.w(TAG, "Cannot start LocationService - foreground service not allowed")
                // Сохраняем состояние для запуска позже
                savePendingServiceState(context, "location", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LocationService", e)
            savePendingServiceState(context, "location", false)
        }
    }

    /**
     * Проверяем можно ли запустить Foreground Service
     */
    private fun canStartForegroundService(context: Context): Boolean {
        // На Android 12+ можно запускать только из определенных контекстов
        return when {
            // Если приложение в foreground - можно
            isAppInForeground(context) -> {
                Log.d(TAG, "App in foreground - can start service")
                true
            }
            // Если у пользователя есть активное уведомление - можно
            hasActiveNotification(context) -> {
                Log.d(TAG, "App has active notification - can start service")
                true
            }
            // Для старых версий Android всегда можно
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> {
                Log.d(TAG, "Android < 12 - can start service")
                true
            }
            else -> {
                Log.w(TAG, "Android 12+ background - cannot start service")
                false
            }
        }
    }

    private fun isAppInForeground(context: Context): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val processes = activityManager.runningAppProcesses ?: return false

            val packageName = context.packageName
            for (process in processes) {
                if (process.processName == packageName &&
                    process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking app foreground state", e)
            false
        }
    }

    private fun hasActiveNotification(context: Context): Boolean {
        // Проверяем есть ли активные уведомления от нашего приложения
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                notificationManager.activeNotifications.any {
                    it.notification != null
                }
            } else {
                // Для старых версий предполагаем что уведомление есть
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking notifications", e)
            false
        }
    }

    private fun savePendingServiceState(context: Context, serviceType: String, isPending: Boolean) {
        try {
            val prefs = context.getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            val key = when (serviceType) {
                "step" -> "step_service_pending"
                "location" -> "location_service_pending"
                else -> return
            }
            prefs.edit().putBoolean(key, isPending).apply()
            Log.d(TAG, "Pending service state saved: $serviceType = $isPending")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving pending service state", e)
        }
    }

    /**
     * Запускает pending сервисы когда приложение становится активным
     */
    fun startPendingServices(context: Context) {
        try {
            val prefs = context.getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            val stepPending = prefs.getBoolean("step_service_pending", false)
            val locationPending = prefs.getBoolean("location_service_pending", false)

            if (stepPending) {
                Log.d(TAG, "Starting pending step service...")
                startStepCounterService(context)
                prefs.edit().remove("step_service_pending").apply()
            }

            if (locationPending) {
                Log.d(TAG, "Starting pending location service...")
                startLocationService(context)
                prefs.edit().remove("location_service_pending").apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting pending services", e)
        }
    }
}