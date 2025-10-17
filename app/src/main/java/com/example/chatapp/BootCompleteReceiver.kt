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
import java.util.concurrent.TimeUnit

class BootCompleteReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootCompleteReceiver"

        // Имена для WorkManager
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

                // 2. ПЛАНИРУЕМ ЗАПУСК СЕРВИСОВ С ЗАДЕРЖКОЙ
                scheduleDelayedServiceStart(context)

                // 3. ЗАПУСКАЕМ WORKERS ДЛЯ ГАРАНТИРОВАННОЙ РАБОТЫ
                scheduleBackupWorkersAfterBoot(context)

                Log.d(TAG, "BootCompleteReceiver: Все задачи запланированы")
            }
        }
    }

    /**
     * Планирует отложенный запуск сервисов
     */
    private fun scheduleDelayedServiceStart(context: Context) {
        try {
            Log.d(TAG, "scheduleDelayedServiceStart: Планируем запуск сервисов через 30 секунд")

            // Запускаем через 30 секунд для стабильности системы
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "scheduleDelayedServiceStart: Запускаем сервисы после задержки")
                restartServicesAfterBoot(context)
            }, 30000) // 30 секунд

        } catch (e: Exception) {
            Log.e(TAG, "scheduleDelayedServiceStart: Ошибка планирования", e)
            // При ошибке пробуем запустить сразу
            restartServicesAfterBoot(context)
        }
    }

    /**
     * Перезапуск сервисов после загрузки системы
     */
    private fun restartServicesAfterBoot(context: Context) {
        try {
            Log.d(TAG, "=== STARTING SERVICES AFTER BOOT ===")

            // 1. ПРОВЕРЯЕМ БЫЛИ ЛИ СЕРВИСЫ АКТИВНЫ ДО ПЕРЕЗАГРУЗКИ
            val prefs = context.getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            val wasStepActive = prefs.getBoolean("step_service_active", false)
            val wasLocationActive = prefs.getBoolean("location_tracking_active", false)

            Log.d(TAG, "Предыдущее состояние - Step: $wasStepActive, Location: $wasLocationActive")

            // 2. ИСПОЛЬЗУЕМ БЕЗОПАСНЫЙ ЗАПУСК ДЛЯ ANDROID 12+
            if (wasStepActive) {
                Log.d(TAG, "Safely starting step service after boot...")
                ForegroundServiceLauncher.startStepCounterService(context)
            } else {
                Log.d(TAG, "Step service не был активен до перезагрузки, пропускаем")
            }

            if (wasLocationActive) {
                Log.d(TAG, "Safely starting location service after boot...")
                ForegroundServiceLauncher.startLocationService(context)
            } else {
                Log.d(TAG, "Location service не был активен до перезагрузки, пропускаем")
            }

            // 3. СОХРАНЯЕМ ЧТО ПЫТАЛИСЬ ЗАПУСТИТЬСЯ ПОСЛЕ BOOT
            saveServiceRestartAttempt(context)

            Log.d(TAG, "=== SERVICES RESTART ATTEMPT COMPLETED ===")

        } catch (e: Exception) {
            Log.e(TAG, "restartServicesAfterBoot: Критическая ошибка", e)

            // Fallback: пробуем Workers если сервисы не запускаются
            scheduleEmergencyWorkers(context)
        }
    }

    /**
     * Запускает резервные Workers для дополнительной надежности
     */
    private fun scheduleBackupWorkersAfterBoot(context: Context) {
        try {
            Log.d(TAG, "scheduleBackupWorkersAfterBoot: Планируем резервные workers")

            // Step Sync Worker для синхронизации данных
            val syncRequest = PeriodicWorkRequestBuilder<StepSyncWorker>(
                30, TimeUnit.MINUTES,
                10, TimeUnit.MINUTES
            ).setInitialDelay(10, TimeUnit.MINUTES).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                STEP_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )

            // Step Counter Worker как резерв
            val stepWorkRequest = OneTimeWorkRequestBuilder<StepCounterWorker>()
                .setInitialDelay(15, TimeUnit.MINUTES) // Через 15 минут после boot
                .addTag(BOOT_STEP_WORK_NAME)
                .build()

            // Location Worker как резерв
            val locationWorkRequest = OneTimeWorkRequestBuilder<LocationWorker>()
                .setInitialDelay(20, TimeUnit.MINUTES) // Через 20 минут после boot
                .addTag(BOOT_LOCATION_WORK_NAME)
                .build()

            WorkManager.getInstance(context).apply {
                enqueue(stepWorkRequest)
                enqueue(locationWorkRequest)
            }

            Log.d(TAG, "Backup workers scheduled successfully")

        } catch (e: Exception) {
            Log.e(TAG, "scheduleBackupWorkersAfterBoot: Ошибка планирования workers", e)
        }
    }

    /**
     * Аварийные Workers если сервисы не запустились
     */
    private fun scheduleEmergencyWorkers(context: Context) {
        try {
            Log.w(TAG, "scheduleEmergencyWorkers: Планируем аварийные workers")

            val stepWorkRequest = OneTimeWorkRequestBuilder<StepCounterWorker>()
                .setInitialDelay(5, TimeUnit.MINUTES)
                .addTag("emergency_step")
                .build()

            val locationWorkRequest = OneTimeWorkRequestBuilder<LocationWorker>()
                .setInitialDelay(7, TimeUnit.MINUTES)
                .addTag("emergency_location")
                .build()

            WorkManager.getInstance(context).apply {
                enqueue(stepWorkRequest)
                enqueue(locationWorkRequest)
            }

            Log.d(TAG, "Emergency workers scheduled as fallback")

        } catch (e: Exception) {
            Log.e(TAG, "scheduleEmergencyWorkers: Ошибка планирования аварийных workers", e)
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

    /**
     * Сохраняет факт попытки перезапуска сервисов
     */
    private fun saveServiceRestartAttempt(context: Context) {
        try {
            val prefs = context.getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("service_restart_attempted", true)
                putLong("last_restart_attempt", System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "Service restart attempt saved")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving service restart attempt", e)
        }
    }

    /**
     * Прямой запуск сервисов (старый метод для совместимости)
     */
    private fun restartStepService(context: Context) {
        try {
            Log.d(TAG, "restartStepService: Прямой запуск step service...")
            StepCounterService.startService(context)
            Log.d(TAG, "Step service start command sent")

            // Сохраняем состояние
            saveServiceState(context, "step", true)
        } catch (e: Exception) {
            Log.e(TAG, "restartStepService: Ошибка запуска step service", e)
            saveServiceState(context, "step", false)
        }
    }

    /**
     * Прямой запуск сервисов (старый метод для совместимости)
     */
    private fun restartLocationService(context: Context) {
        try {
            Log.d(TAG, "restartLocationService: Прямой запуск location service...")
            LocationServiceManager.startLocationService(context)
            Log.d(TAG, "Location service start command sent")

            // Сохраняем состояние
            saveServiceState(context, "location", true)
        } catch (e: Exception) {
            Log.e(TAG, "restartLocationService: Ошибка запуска location service", e)
            saveServiceState(context, "location", false)
        }
    }
}