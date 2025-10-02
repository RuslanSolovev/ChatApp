package com.example.chatapp.receivers

// BootReceiver.kt
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.chatapp.location.LocationUpdateService // Убедитесь, что импорт правильный
import com.example.chatapp.StepCounterService
import com.google.firebase.auth.FirebaseAuth
import androidx.work.*
import com.example.chatapp.ServiceMonitorWorker
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.d(TAG, "Device booted, starting services")

            try {
                val auth = FirebaseAuth.getInstance()
                val userId = auth.currentUser?.uid

                // Всегда запускаем шагомер
                StepCounterService.startService(context)
                Log.d(TAG, "StepCounterService started after boot")

                // Для локации: Запускаем сервис НЕЗАВИСИМО от настроек при загрузке
                // Пусть сам сервис проверяет настройки при запуске
                if (userId != null) {
                    // Проверим разрешения перед запуском (приблизительно)
                    val hasForegroundLocation =
                        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

                    // val hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    //     context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                    // } else true

                    if (hasForegroundLocation) { // Достаточно для старта сервиса
                        // --- ВАЖНО: Используем КОНСТАНТУ из LocationUpdateService ---
                        val serviceIntent = Intent(context, LocationUpdateService::class.java).apply {
                            // action = "START_FOREGROUND" // <-- СТАРОЕ ЗНАЧЕНИЕ, УДАЛИТЕ ЭТО
                            action = LocationUpdateService.ACTION_START // <-- НОВОЕ ЗНАЧЕНИЕ
                        }
                        // --- КОНЕЦ ВАЖНО ---
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                            Log.d(TAG, "LocationUpdateService start intent sent after boot with action: ${LocationUpdateService.ACTION_START}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not start LocationUpdateService after boot", e)
                            // Не критично, WorkManager попробует позже
                        }
                    } else {
                        Log.d(TAG, "Location permissions not granted, skipping LocationUpdateService start")
                    }
                } else {
                    Log.d(TAG, "User not logged in, skipping LocationUpdateService start")
                }

                // Планируем периодический мониторинг
                scheduleServiceMonitorWork(context)

            } catch (e: Exception) {
                Log.e(TAG, "Error during service startup in BootReceiver", e)
            }
        }
    }

    private fun scheduleServiceMonitorWork(context: Context) {
        try {
            val workManager = WorkManager.getInstance(context)

            // Меньше ограничений для большей надежности
            val constraints = Constraints.Builder()
                // .setRequiresBatteryNotLow(true) // Можно убрать
                .build()

            // Частота проверки (например, каждые 15-30 минут)
            val periodicWorkRequest = PeriodicWorkRequestBuilder<ServiceMonitorWorker>(
                30, TimeUnit.MINUTES // Каждые 30 минут
            ).setConstraints(constraints).build()

            workManager.enqueueUniquePeriodicWork(
                "ServiceMonitorWork", // Имя должно совпадать с тем, что в LocationFragment/MainActivity
                ExistingPeriodicWorkPolicy.REPLACE, // REPLACE для перепланирования
                periodicWorkRequest
            )
            Log.d(TAG, "Service monitor scheduled after boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule service monitor in BootReceiver", e)
        }
    }
}