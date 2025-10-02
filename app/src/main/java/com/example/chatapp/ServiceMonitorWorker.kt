package com.example.chatapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.chatapp.location.LocationUpdateService // Убедитесь, что путь правильный
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await

class ServiceMonitorWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "ServiceMonitorWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "ServiceMonitorWorker запущен - проверка и перезапуск сервисов")

            val context = applicationContext
            val auth = FirebaseAuth.getInstance()
            val userId = auth.currentUser?.uid

            // Проверяем и запускаем сервис шагомера
            ensureStepServiceRunning(context)

            // Проверяем и запускаем сервис локации (если пользователь авторизован)
            if (userId != null) {
                ensureLocationServiceRunning(context, userId)
            }

            Log.d(TAG, "Проверка сервисов завершена")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в ServiceMonitorWorker", e)
            // Возвращаем retry(), чтобы WorkManager попытался выполнить работу позже
            Result.retry()
        }
    }

    /**
     * Проверяет, запущен ли StepCounterService, и запускает его, если нет.
     */
    private fun ensureStepServiceRunning(context: Context) {
        try {
            if (!isServiceRunning(context, StepCounterService::class.java)) {
                Log.d(TAG, "StepCounterService не запущен - перезапускаем")
                StepCounterService.startService(context)
                Log.d(TAG, "Запрос на запуск StepCounterService отправлен")
            } else {
                Log.d(TAG, "StepCounterService уже запущен")
            }
        } catch (e: Exception) {
            // Ловим любые ошибки при запуске сервиса
            Log.w(TAG, "Не удалось перезапустить StepCounterService. Причина: ${e.message}", e)
            // Не возвращаем Result.failure(), так как это остановит периодическую работу.
            // WorkManager продолжит планирование.
        }
    }

    /**
     * Проверяет, запущен ли LocationUpdateService, и запускает его, если он включен в настройках.
     * Также проверяет необходимые разрешения перед запуском.
     */
    private suspend fun ensureLocationServiceRunning(context: Context, userId: String) {
        try {
            // 1. Проверяем основные разрешения на местоположение (необходимы для запуска foreground service)
            val hasForegroundLocationPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasForegroundLocationPermission) {
                Log.d(TAG, "Основные разрешения на местоположение не предоставлены. Пропускаем запуск LocationUpdateService.")
                return
            }

            // 2. Для Android 10+ (API 29) проверяем разрешение на фоновое местоположение
            // Это НЕ обязательно для запуска foreground service, но необходимо для его полноценной работы.
            // Сервис сам должен проверять это разрешение.
            // val hasBackgroundLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            //     ContextCompat.checkSelfPermission(
            //         context,
            //         Manifest.permission.ACCESS_BACKGROUND_LOCATION
            //     ) == PackageManager.PERMISSION_GRANTED
            // } else {
            //     true // Для более старых версий это не требуется
            // }

            // 3. Получаем настройки пользователя из Firebase
            val database = Firebase.database.reference
            val settingsSnapshot = database.child("location_settings").child(userId).get().await()
            val settings = settingsSnapshot.getValue(com.example.chatapp.models.LocationSettings::class.java)

            // 4. Если настройки разрешают работу сервиса
            if (settings?.enabled == true) {
                // 5. Проверяем, запущен ли сервис
                if (!isServiceRunning(context, LocationUpdateService::class.java)) {
                    Log.d(TAG, "LocationUpdateService не запущен, но включен в настройках - перезапускаем")
                    val serviceIntent = Intent(context, LocationUpdateService::class.java).apply {
                        action = LocationUpdateService.ACTION_START // Используем константу из вашего сервиса
                    }
                    try {
                        // 6. Запускаем сервис
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                        Log.d(TAG, "Запрос на запуск LocationUpdateService отправлен")
                    } catch (e: Exception) {
                        // Может возникнуть ForegroundServiceStartNotAllowedException на Android 12+ (S+)
                        // если приложение не в "активном" состоянии, или другие ошибки.
                        Log.w(TAG, "Не удалось запустить LocationUpdateService. Причина: ${e.message}", e)
                    }
                } else {
                    Log.d(TAG, "LocationUpdateService уже запущен и включен в настройках")
                }
            } else {
                Log.d(TAG, "LocationUpdateService отключен в настройках пользователя")
                // Опционально: можно остановить сервис, если он каким-то образом запущен, но не должен быть
                // try {
                //     context.stopService(Intent(context, LocationUpdateService::class.java))
                //     Log.d(TAG, "LocationUpdateService остановлен, так как отключен в настройках")
                // } catch (e: Exception) {
                //     Log.w(TAG, "Не удалось остановить LocationUpdateService", e)
                // }
            }
        } catch (e: Exception) {
            // Ловим любые ошибки при проверке настроек или запуске сервиса
            Log.e(TAG, "Ошибка проверки или перезапуска LocationUpdateService", e)
        }
    }

    /**
     * Проверяет, запущен ли указанный сервис.
     * @param context Контекст приложения.
     * @param serviceClass Класс сервиса для проверки.
     * @return true, если сервис запущен, иначе false.
     */
    @Suppress("DEPRECATION") // getRunningServices устарел, но для наших целей подходит
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            // Получаем список всех запущенных сервисов
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            // Проверяем, содержится ли наш сервис в этом списке
            runningServices.any { it.service.className == serviceClass.name }
        } catch (e: Exception) {
            // Ловим любые ошибки при получении списка сервисов
            Log.e(TAG, "Ошибка проверки запущенных сервисов (${serviceClass.simpleName})", e)
            // В случае ошибки считаем, что сервис не запущен, чтобы попытаться его перезапустить
            false
        }
    }
}