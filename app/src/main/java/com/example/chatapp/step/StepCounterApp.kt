package com.example.chatapp.step

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.chatapp.ForegroundServiceLauncher
import com.example.chatapp.LocationWorker
import com.example.chatapp.StepCounterWorker
import com.example.chatapp.StepSyncWorker
import com.onesignal.OneSignal
import com.yandex.mapkit.MapKitFactory
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

const val ONESIGNAL_APP_ID = "0083de8f-7ca0-4824-ac88-9c037278237e"

class StepCounterApp : Application() {

    companion object {
        private const val TAG = "StepCounterApp"
    }

    // Корутин scope для асинхронной инициализации
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // Проверка App ID
        if (ONESIGNAL_APP_ID == "0083de8f-7ca0-4824-ac88-9c037278237e") {
            Log.w(TAG, "ONESIGNAL_APP_ID не изменён! Используйте свой реальный App ID.")
        }

        // Создаем каналы уведомлений
        createNotificationChannels()

        // ЗАПУСКАЕМ ВСЕ АСИНХРОННО И ПАРАЛЛЕЛЬНО
        initializeAppAsync()
        Log.d(TAG, "Application initialization started")
    }

    /**
     * АСИНХРОННАЯ ИНИЦИАЛИЗАЦИЯ ВСЕХ КОМПОНЕНТОВ
     */
    private fun initializeAppAsync() {
        appScope.launch {
            // 1. КРИТИЧЕСКИЕ КОМПОНЕНТЫ - запускаем сразу параллельно
            val criticalJobs = listOf(
                async { initializeMapKit() },
                async { setupPeriodicStepSync() },
                async { initializeOneSignalLite() }
            )
            criticalJobs.awaitAll()

            // 2. БЫСТРЫЙ ЗАПУСК СЕРВИСОВ - через 2 секунды
            delay(2000)

            // ИСПОЛЬЗУЕМ БЕЗОПАСНЫЙ ЗАПУСК
            safeRestartServices()

            Log.d(TAG, "Application initialization COMPLETED - все сервисы запущены")
        }
    }

    /**
     * БЕЗОПАСНЫЙ перезапуск сервисов с учетом ограничений Android 12+
     */
    private fun safeRestartServices() {
        appScope.launch {
            try {
                val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
                val wasStepActive = prefs.getBoolean("step_service_active", false)
                val wasLocationActive = prefs.getBoolean("location_tracking_active", false)

                Log.d(TAG, "=== SAFE SERVICE RESTART ===")
                Log.d(TAG, "Step was active: $wasStepActive, Location was active: $wasLocationActive")

                // ИСПОЛЬЗУЕМ БЕЗОПАСНЫЙ ЗАПУСК
                if (wasStepActive) {
                    Log.d(TAG, "Safely starting step service...")
                    ForegroundServiceLauncher.startStepCounterService(this@StepCounterApp)
                }

                if (wasLocationActive) {
                    Log.d(TAG, "Safely starting location service...")
                    ForegroundServiceLauncher.startLocationService(this@StepCounterApp)
                }

                Log.d(TAG, "=== SAFE RESTART COMPLETED ===")

            } catch (e: Exception) {
                Log.e(TAG, "Safe restart error", e)
            }
        }
    }

    /**
     * УНИВЕРСАЛЬНЫЙ метод перезапуска сервисов после краша для всех версий Android
     */
    fun restartServicesAfterCrash() {
        Log.d(TAG, "restartServicesAfterCrash: Перезапуск сервисов после краша")

        appScope.launch {
            try {
                val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
                val wasStepActive = prefs.getBoolean("step_service_active", false)
                val wasLocationActive = prefs.getBoolean("location_tracking_active", false)

                Log.d(TAG, "Предыдущее состояние - Step: $wasStepActive, Location: $wasLocationActive")

                // ИСПОЛЬЗУЕМ БЕЗОПАСНЫЙ ЗАПУСК ДЛЯ ANDROID 12+
                if (wasStepActive) {
                    Log.d(TAG, "Safely restarting step service after crash...")
                    ForegroundServiceLauncher.startStepCounterService(this@StepCounterApp)
                }

                if (wasLocationActive) {
                    Log.d(TAG, "Safely restarting location service after crash...")
                    ForegroundServiceLauncher.startLocationService(this@StepCounterApp)
                }

                Log.d(TAG, "Перезапуск сервисов после краша завершен")

            } catch (e: Exception) {
                Log.e(TAG, "Критическая ошибка при перезапуске сервисов", e)
                // Аварийный перезапуск через Workers
                scheduleEmergencyWorkersAfterCrash()
            }
        }
    }

    /**
     * Аварийные Workers после краша
     */
    private fun scheduleEmergencyWorkersAfterCrash() {
        try {
            Log.w(TAG, "Scheduling emergency workers after crash")

            val stepWorkRequest = OneTimeWorkRequestBuilder<StepCounterWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .addTag("crash_recovery_step")
                .build()

            val locationWorkRequest = OneTimeWorkRequestBuilder<LocationWorker>()
                .setInitialDelay(2, TimeUnit.MINUTES)
                .addTag("crash_recovery_location")
                .build()

            WorkManager.getInstance(this).apply {
                enqueue(stepWorkRequest)
                enqueue(locationWorkRequest)
            }

            Log.d(TAG, "Emergency workers scheduled after crash")

        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling emergency workers after crash", e)
        }
    }

    /**
     * Инициализация MapKit в главном потоке
     */
    private suspend fun initializeMapKit() = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "Initializing Yandex MapKit")
            MapKitFactory.setApiKey("6b7f7e6b-d322-42b2-8471-d8aecc6570d1")
            MapKitFactory.initialize(this@StepCounterApp)
            Log.d(TAG, "MapKit initialization successful")
        } catch (e: Exception) {
            Log.e(TAG, "MapKit initialization failed", e)
        }
    }

    /**
     * Настройка периодической синхронизации
     */
    private fun setupPeriodicStepSync() {
        try {
            val syncRequest = PeriodicWorkRequestBuilder<StepSyncWorker>(
                30, TimeUnit.MINUTES,
                10, TimeUnit.MINUTES
            ).setInitialDelay(5, TimeUnit.MINUTES).build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "step_sync_work",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
            Log.d(TAG, "Periodic step sync work enqueued")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up periodic step sync", e)
        }
    }

    /**
     * УПРОЩЕННЫЙ OneSignal - БЕЗ ГЕОЛОКАЦИИ чтобы избежать таймаутов
     */
    private fun initializeOneSignalLite() {
        try {
            Log.d(TAG, "Initializing OneSignal LITE...")

            OneSignal.initWithContext(this)
            OneSignal.setAppId(ONESIGNAL_APP_ID)

            // КРИТИЧЕСКИЕ НАСТРОЙКИ для избежания таймаутов:
            OneSignal.setLocationShared(false) // ОТКЛЮЧАЕМ геолокацию
            OneSignal.disablePush(false)

            // Минимальная конфигурация
            OneSignal.setNotificationWillShowInForegroundHandler { event ->
                event.complete(event.notification)
            }

            Log.d(TAG, "OneSignal LITE initialized - без геолокации")
        } catch (e: Exception) {
            Log.e(TAG, "OneSignal LITE init error - SILENT", e)
        }
    }

    /**
     * Сохраняет состояние сервисов
     */
    fun saveServiceState(serviceType: String, isActive: Boolean) {
        try {
            val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
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
     * Получает текущее состояние сервиса
     */
    fun getServiceState(serviceType: String): Boolean {
        return try {
            val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            when (serviceType) {
                "step" -> prefs.getBoolean("step_service_active", false)
                "location" -> prefs.getBoolean("location_tracking_active", false)
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting service state", e)
            false
        }
    }

    fun updateStepsInViewModel(steps: Int) {
        try {
            val viewModel = ViewModelProvider.AndroidViewModelFactory.getInstance(this)
                .create(StepCounterViewModel::class.java)
            viewModel.updateTodaySteps(steps)
            Log.d(TAG, "Steps updated in ViewModel: $steps")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating steps in ViewModel", e)
        }
    }

    /**
     * ДОБАВЛЯЕМ МЕТОД ДЛЯ РУЧНОГО ЗАПУСКА СЕРВИСОВ ИЗ АКТИВНОСТИ
     */
    fun startServicesFromActivity() {
        Log.d(TAG, "Starting services from activity...")

        // Запускаем pending сервисы
        ForegroundServiceLauncher.startPendingServices(this)

        appScope.launch {
            // Также запускаем обычные сервисы если они не активны
            if (!getServiceState("step")) {
                Log.d(TAG, "Starting step service from activity...")
                ForegroundServiceLauncher.startStepCounterService(this@StepCounterApp)
            }

            if (!getServiceState("location")) {
                Log.d(TAG, "Starting location service from activity...")
                ForegroundServiceLauncher.startLocationService(this@StepCounterApp)
            }
        }
    }

    /**
     * Проверяет есть ли pending сервисы
     */
    fun hasPendingServices(): Boolean {
        val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("step_service_pending", false) ||
                prefs.getBoolean("location_service_pending", false)
    }

    /**
     * Создает каналы уведомлений для Workers и сервисов
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Каналы для сервисов
            val stepServiceChannel = NotificationChannel(
                "step_counter_channel",
                "Счетчик шагов",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Отслеживает ваши шаги в фоне"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val milestoneChannel = NotificationChannel(
                "step_milestone_channel",
                "Достижения в ходьбе",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о достижении целей"
                setShowBadge(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val locationServiceChannel = NotificationChannel(
                "location_service_channel",
                "Отслеживание маршрута",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Канал для сервиса отслеживания перемещений"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(false)
                enableLights(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(stepServiceChannel)
            notificationManager.createNotificationChannel(milestoneChannel)
            notificationManager.createNotificationChannel(locationServiceChannel)

            Log.d(TAG, "All notification channels created")
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }
}