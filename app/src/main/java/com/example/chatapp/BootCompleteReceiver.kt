package com.example.chatapp.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.chatapp.ForegroundServiceLauncher
import com.example.chatapp.LocationWorker
import com.example.chatapp.StepCounterWorker
import com.example.chatapp.StepSyncWorker
import com.example.chatapp.location.LocationServiceManager
import com.example.chatapp.step.StepCounterService
import com.example.chatapp.step.StepCounterServiceWorker // ИМПОРТИРУЕМ ПРАВИЛЬНЫЙ WORKER
import java.util.concurrent.TimeUnit

class BootCompleteReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootCompleteReceiver"

        // Имена для WorkManager
        private const val PERIODIC_STEP_MONITOR_WORK = "periodic_step_monitor_work"
        private const val BOOT_STEP_WORK_NAME = "boot_step_worker"
        private const val BOOT_LOCATION_WORK_NAME = "boot_location_worker"
        private const val PERIODIC_STEP_WORK_NAME = "periodic_step_after_boot"
        private const val PERIODIC_LOCATION_WORK_NAME = "periodic_location_after_boot"
        private const val STEP_SYNC_WORK_NAME = "step_sync_work"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "BootCompleteReceiver triggered with action: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "Device boot completed, планируем запуск сервисов...")

                // 1. СОХРАНЯЕМ СОСТОЯНИЕ ЧТО УСТРОЙСТВО ПЕРЕЗАГРУЖЕНО
                saveBootCompletedState(context)

                // 2. НЕМЕДЛЕННЫЙ ЗАПУСК СЕРВИСА (без задержки)
                startServicesImmediately(context)

                // 3. ПЛАНИРУЕМ ОТЛОЖЕННЫЙ ЗАПУСК (на всякий случай)
                scheduleDelayedServiceStart(context)

                // 4. ЗАПУСКАЕМ WORKER ДЛЯ МОНИТОРИНГА СЕРВИСА
                scheduleStepServiceMonitorWork(context)

                Log.d(TAG, "BootCompleteReceiver: Все задачи запланированы")
            }
        }
    }

    /**
     * Немедленный запуск сервисов
     */
    private fun startServicesImmediately(context: Context) {
        try {
            Log.d(TAG, "=== НЕМЕДЛЕННЫЙ ЗАПУСК СЕРВИСОВ ПОСЛЕ BOOT ===")

            // Прямой запуск шагомера
            StepCounterService.startService(context)
            saveServiceState(context, "step", true)

            Log.d(TAG, "Сервисы запущены немедленно")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка немедленного запуска сервисов", e)
        }
    }

    /**
     * Планирует отложенный запуск сервисов
     */
    private fun scheduleDelayedServiceStart(context: Context) {
        try {
            Log.d(TAG, "scheduleDelayedServiceStart: Дополнительный запуск через 1 минуту")

            // Запускаем через 1 минуту для гарантии
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "scheduleDelayedServiceStart: Дополнительный запуск сервисов")
                restartServicesAfterBoot(context)
            }, 60000) // 1 минута

        } catch (e: Exception) {
            Log.e(TAG, "scheduleDelayedServiceStart: Ошибка планирования", e)
        }
    }

    /**
     * Перезапуск сервисов после загрузки системы
     */
    private fun restartServicesAfterBoot(context: Context) {
        try {
            Log.d(TAG, "=== ДОПОЛНИТЕЛЬНЫЙ ЗАПУСК СЕРВИСОВ ПОСЛЕ BOOT ===")

            // Проверяем, запущен ли сервис
            if (!isServiceRunning(context, StepCounterService::class.java)) {
                Log.d(TAG, "Сервис шагомера не запущен, запускаем...")
                StepCounterService.startService(context)
                saveServiceState(context, "step", true)
            } else {
                Log.d(TAG, "Сервис шагомера уже запущен")
            }

        } catch (e: Exception) {
            Log.e(TAG, "restartServicesAfterBoot: Ошибка", e)
        }
    }

    /**
     * Запускает Worker для мониторинга сервиса шагомера
     */
    private fun scheduleStepServiceMonitorWork(context: Context) {
        try {
            Log.d(TAG, "scheduleStepServiceMonitorWork: Планируем мониторинг сервиса")

            // StepCounterServiceWorker - для мониторинга и перезапуска сервиса
            val monitorRequest = PeriodicWorkRequestBuilder<StepCounterServiceWorker>(
                15, TimeUnit.MINUTES, // Проверяем каждые 15 минут
                5, TimeUnit.MINUTES // Гибкость
            ).setInitialDelay(5, TimeUnit.MINUTES).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_STEP_MONITOR_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                monitorRequest
            )

            // StepSyncWorker - для синхронизации данных
            val syncRequest = PeriodicWorkRequestBuilder<StepSyncWorker>(
                30, TimeUnit.MINUTES,
                10, TimeUnit.MINUTES
            ).setInitialDelay(10, TimeUnit.MINUTES).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                STEP_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )

            Log.d(TAG, "Workers мониторинга запланированы")

        } catch (e: Exception) {
            Log.e(TAG, "scheduleStepServiceMonitorWork: Ошибка планирования workers", e)
        }
    }

    /**
     * Проверяет, запущен ли сервис
     */
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            manager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == serviceClass.name }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Сохраняет состояние сервиса
     */
    private fun saveServiceState(context: Context, serviceType: String, isActive: Boolean) {
        try {
            val prefs = context.getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                when (serviceType) {
                    "step" -> putBoolean("step_service_active", isActive)
                    "location" -> putBoolean("location_tracking_active", isActive)
                }
                putLong("last_service_state_change", System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "Service state saved: $serviceType = $isActive")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving service state", e)
        }
    }

    /**
     * Сохраняет состояние что устройство перезагружено
     */
    private fun saveBootCompletedState(context: Context) {
        try {
            val prefs = context.getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("device_rebooted", true)
                putLong("last_boot_time", System.currentTimeMillis())
                putBoolean("services_need_restart", true)
                apply()
            }
            Log.d(TAG, "Boot completed state saved")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving boot completed state", e)
        }
    }
}