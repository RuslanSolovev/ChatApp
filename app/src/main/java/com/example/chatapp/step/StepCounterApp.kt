package com.example.chatapp.step

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.chatapp.location.LocationServiceManager
import com.onesignal.OneSignal
import com.yandex.mapkit.MapKitFactory
import org.json.JSONObject
import java.util.concurrent.TimeUnit

const val ONESIGNAL_APP_ID = "0083de8f-7ca0-4824-ac88-9c037278237e"

class StepCounterApp : Application() {

    companion object {
        private const val TAG = "StepCounterApp"
        private const val DELAY_AFTER_APP_START = 2000L // Уменьшил до 2 секунд
    }

    override fun onCreate() {
        super.onCreate()

        // Проверка App ID
        if (ONESIGNAL_APP_ID == "0083de8f-7ca0-4824-ac88-9c037278237e") {
            Log.w(TAG, "ONESIGNAL_APP_ID не изменён! Используйте свой реальный App ID.")
        }

        // Инициализация OneSignal для версии 4.x.x
        initializeOneSignal()

        // Инициализация Yandex MapKit
        initializeMapKit()

        // АВТОМАТИЧЕСКИЙ ПЕРЕЗАПУСК СЕРВИСОВ ПОСЛЕ ПЕРЕЗАГРУЗКИ - ЗАПУСКАЕМ БЫСТРЕЕ
        checkAndRestartServicesAfterBoot()

        // Настройка периодической синхронизации шагов
        setupPeriodicStepSync()

        Log.d(TAG, "Application initialization completed")
    }

    /**
     * Проверяет и перезапускает сервисы после перезагрузки устройства
     */
    private fun checkAndRestartServicesAfterBoot() {
        Log.d(TAG, "Checking if services need to be restarted after boot...")

        // Запускаем с минимальной задержкой для стабильности
        android.os.Handler(mainLooper).postDelayed({
            restartServicesIfNeeded()
        }, DELAY_AFTER_APP_START)
    }

    /**
     * Перезапускает сервисы если они были активны до перезагрузки
     */
    private fun restartServicesIfNeeded() {
        try {
            val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)

            // Проверяем состояние сервисов перед выключением
            val wasStepServiceActive = prefs.getBoolean("step_service_active", false)
            val wasLocationTrackingActive = prefs.getBoolean("location_tracking_active", false)
            val lastStateChange = prefs.getLong("last_service_state_change", 0L)

            // Время с последнего изменения состояния (в часах)
            val hoursSinceLastStateChange = (System.currentTimeMillis() - lastStateChange) / (1000 * 60 * 60)

            Log.d(TAG, "=== SERVICE STATE CHECK ===")
            Log.d(TAG, "Step service was active: $wasStepServiceActive")
            Log.d(TAG, "Location tracking was active: $wasLocationTrackingActive")
            Log.d(TAG, "Hours since last state change: $hoursSinceLastStateChange")

            // Если состояние изменялось недавно (менее 24 часов назад), считаем его актуальным
            val isStateRecent = hoursSinceLastStateChange < 24

            if (isStateRecent) {
                // ВСЕГДА перезапускаем сервис шагомера если он был активен
                if (wasStepServiceActive) {
                    Log.d(TAG, "Restarting step counter service...")
                    restartStepService()
                } else {
                    Log.d(TAG, "Step service was not active, skipping restart")
                }

                // Перезапускаем сервис локации если он был активен
                if (wasLocationTrackingActive) {
                    Log.d(TAG, "Restarting location tracking service...")
                    restartLocationService()
                } else {
                    Log.d(TAG, "Location tracking was not active, skipping restart")
                }
            } else {
                Log.d(TAG, "Service state is too old (${hoursSinceLastStateChange}h), assuming fresh start")

                // При первом запуске или старом состоянии запускаем только шагомер
                restartStepService()
            }

            Log.d(TAG, "=== SERVICE RESTART COMPLETED ===")

        } catch (e: Exception) {
            Log.e(TAG, "Error restarting services", e)
        }
    }

    /**
     * Перезапускает сервис шагомера
     */
    private fun restartStepService() {
        try {
            Log.d(TAG, "Starting StepCounterService...")
            StepCounterService.startService(this)

            // Сохраняем состояние что сервис запущен
            val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("step_service_active", true).apply()

            Log.d(TAG, "StepCounterService start command sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start StepCounterService", e)
        }
    }

    /**
     * Перезапускает сервис отслеживания локации
     */
    private fun restartLocationService() {
        try {
            Log.d(TAG, "Starting LocationUpdateService...")
            LocationServiceManager.startLocationService(this)
            Log.d(TAG, "LocationUpdateService start command sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LocationUpdateService", e)
        }
    }

    /**
     * Сохраняет состояние сервисов (вызывается из сервисов при остановке)
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

    private fun initializeOneSignal() {
        try {
            Log.d(TAG, "Initializing OneSignal...")

            OneSignal.setLogLevel(OneSignal.LOG_LEVEL.VERBOSE, OneSignal.LOG_LEVEL.NONE)
            OneSignal.initWithContext(this)
            OneSignal.setAppId(ONESIGNAL_APP_ID)

            // Обработчики уведомлений
            OneSignal.setNotificationWillShowInForegroundHandler { event ->
                Log.d(TAG, "Foreground notification: ${event.notification.title}")
                event.complete(event.notification)
            }

            OneSignal.setNotificationOpenedHandler { result ->
                Log.d(TAG, "Notification clicked: ${result.notification.title}")
                handleNotificationDeepLink(result.notification.additionalData)
            }

            // Запрос разрешений БЕЗ callback
            OneSignal.promptForPushNotifications()

            Log.d(TAG, "OneSignal initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "OneSignal initialization failed", e)
        }
    }

    private fun initializeMapKit() {
        try {
            Log.d(TAG, "Initializing Yandex MapKit")
            MapKitFactory.setApiKey("6b7f7e6b-d322-42b2-8471-d8aecc6570d1")
            MapKitFactory.initialize(this)
            Log.d(TAG, "MapKit initialization successful")
        } catch (e: Exception) {
            Log.e(TAG, "MapKit initialization failed", e)
        }
    }

    private fun handleNotificationDeepLink(additionalData: JSONObject?) {
        Log.d(TAG, "Processing notification deep link with data: $additionalData")

        additionalData?.let { data ->
            when {
                data.has("chatId") -> {
                    val chatId = data.optString("chatId")
                    Log.d(TAG, "Opening chat: $chatId")
                    openChat(chatId)
                }
                data.has("newsId") -> {
                    val newsId = data.optString("newsId")
                    Log.d(TAG, "Opening news: $newsId")
                    openNews(newsId)
                }
                data.has("action") -> {
                    val action = data.optString("action")
                    Log.d(TAG, "Performing action: $action")
                    handleCustomAction(action, data)
                }
                else -> {
                    Log.d(TAG, "Generic notification clicked")
                    openMainActivity()
                }
            }
        } ?: run {
            Log.d(TAG, "No additional data, opening main activity")
            openMainActivity()
        }
    }

    private fun openChat(chatId: String) {
        Log.d(TAG, "Opening chat screen for: $chatId")
        // Реализация открытия чата
        // startActivity(Intent(this, ChatActivity::class.java).apply {
        //     putExtra("chatId", chatId)
        //     flags = Intent.FLAG_ACTIVITY_NEW_TASK
        // })
    }

    private fun openNews(newsId: String) {
        Log.d(TAG, "Opening news screen for: $newsId")
        // Реализация открытия новости
        // startActivity(Intent(this, NewsActivity::class.java).apply {
        //     putExtra("newsId", newsId)
        //     flags = Intent.FLAG_ACTIVITY_NEW_TASK
        // })
    }

    private fun handleCustomAction(action: String, data: JSONObject) {
        when (action) {
            "update_steps" -> {
                val steps = data.optInt("steps", 0)
                Log.d(TAG, "Updating steps from notification: $steps")
                updateStepsInViewModel(steps)
            }
            "open_profile" -> {
                Log.d(TAG, "Opening profile from notification")
                // openProfileActivity()
            }
            "open_step_counter" -> {
                Log.d(TAG, "Opening step counter from notification")
                // openStepCounterActivity()
            }
            // Добавьте другие действия по необходимости
        }
    }

    private fun openMainActivity() {
        // Открытие главной активности
        Log.d(TAG, "Opening main activity")
        // val intent = Intent(this, MainActivity::class.java).apply {
        //     flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        // }
        // startActivity(intent)
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

    private fun setupPeriodicStepSync() {
        try {
            val syncRequest: PeriodicWorkRequest = PeriodicWorkRequestBuilder<StepSyncWorker>(
                15, // Интервал повторения
                TimeUnit.MINUTES
            )
                .setInitialDelay(5, TimeUnit.MINUTES) // Начальная задержка
                .build()

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
     * Очищает состояние сервисов (например, при выходе из приложения)
     */
    fun clearServiceStates() {
        try {
            val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                remove("step_service_active")
                remove("location_tracking_active")
                remove("last_service_state_change")
                apply()
            }
            Log.d(TAG, "Service states cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing service states", e)
        }
    }

    /**
     * Принудительно останавливает все сервисы
     */
    fun stopAllServices() {
        try {
            Log.d(TAG, "Stopping all services...")

            // Останавливаем сервис локации
            LocationServiceManager.stopLocationService(this)

            // Останавливаем сервис шагомера
            val intent = Intent(this, StepCounterService::class.java)
            stopService(intent)

            // Очищаем состояния
            clearServiceStates()

            Log.d(TAG, "All services stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping services", e)
        }
    }

    /**
     * Проверяет, активны ли сервисы в данный момент
     */
    fun areServicesRunning(): Boolean {
        return try {
            val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            val stepActive = prefs.getBoolean("step_service_active", false)
            val locationActive = prefs.getBoolean("location_tracking_active", false)

            stepActive || locationActive
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if services are running", e)
            false
        }
    }

    /**
     * Получает статус всех сервисов для отладки
     */
    fun getServicesStatus(): String {
        return try {
            val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            val stepActive = prefs.getBoolean("step_service_active", false)
            val locationActive = prefs.getBoolean("location_tracking_active", false)
            val lastChange = prefs.getLong("last_service_state_change", 0L)

            "Step Service: $stepActive, Location Service: $locationActive, Last Change: ${if (lastChange > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(lastChange)) else "Never"}"
        } catch (e: Exception) {
            "Error getting services status: ${e.message}"
        }
    }
}