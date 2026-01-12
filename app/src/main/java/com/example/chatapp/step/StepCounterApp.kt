package com.example.chatapp.step

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.*
import com.example.chatapp.ForegroundServiceLauncher
import com.example.chatapp.LocationWorker
import com.example.chatapp.StepCounterWorker
import com.onesignal.OneSignal
import com.yandex.mapkit.MapKitFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

const val ONESIGNAL_APP_ID = "0083de8f-7ca0-4824-ac88-9c037278237e"

@HiltAndroidApp
class StepCounterApp : Application() {

    companion object {
        private const val TAG = "StepCounterApp"
        const val STEP_SERVICE_WORK_NAME = "step_counter_periodic_work"
        const val STEP_SYNC_WORK_NAME = "step_sync_work"
    }

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        createNotificationChannels()
        initializeAppAsync()
        Log.d(TAG, "Application initialization started")
    }

    private fun initializeAppAsync() {
        appScope.launch {
            try {
                // Запускаем критические инициализации параллельно
                val criticalJobs = listOf(
                    async { initializeMapKit() },
                    async { initializeOneSignalLite() }
                )
                criticalJobs.awaitAll()

                // Пауза для стабильности системы
                delay(3000)

                // Запускаем сервисы
                restartServicesAfterBoot()

                // Настраиваем периодическую проверку сервиса
                setupStepServiceMonitorWork()

                Log.d(TAG, "Application initialization COMPLETED")

            } catch (e: Exception) {
                Log.e(TAG, "Error during app initialization", e)
                // Пробуем запустить сервисы даже при ошибках
                safeRestartServices()
            }
        }
    }

    /**
     * Запуск сервисов после перезагрузки устройства
     */
    private fun restartServicesAfterBoot() {
        appScope.launch {
            try {
                Log.d(TAG, "=== RESTARTING SERVICES AFTER BOOT ===")

                // Всегда запускаем шагомер после перезагрузки
                startStepCounterService()

                // Локацию запускаем только если она была активна
                val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
                val wasLocationActive = prefs.getBoolean("location_tracking_active", false)

                if (wasLocationActive) {
                    startLocationService()
                }

                Log.d(TAG, "=== SERVICES RESTARTED ===")

            } catch (e: Exception) {
                Log.e(TAG, "Error restarting services after boot", e)
                // Планируем аварийный перезапуск через WorkManager
                scheduleEmergencyRestart()
            }
        }
    }

    /**
     * Безопасный запуск сервисов
     */
    private fun safeRestartServices() {
        appScope.launch {
            try {
                Log.d(TAG, "=== SAFE SERVICE RESTART ===")

                startStepCounterService()

                val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
                val wasLocationActive = prefs.getBoolean("location_tracking_active", false)

                if (wasLocationActive) {
                    startLocationService()
                }

                Log.d(TAG, "=== SAFE RESTART COMPLETED ===")
            } catch (e: Exception) {
                Log.e(TAG, "Safe restart error", e)
            }
        }
    }

    /**
     * Прямой запуск сервиса шагомера
     */
    private fun startStepCounterService() {
        try {
            Log.d(TAG, "Starting step counter service from Application")
            StepCounterService.startService(this)
            saveServiceState("step", true)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting step counter service", e)
            saveServiceState("step", false)
        }
    }

    /**
     * Прямой запуск сервиса локации
     */
    private fun startLocationService() {
        try {
            Log.d(TAG, "Starting location service from Application")
            // Используйте ваш класс для запуска локации
            val locationServiceClass = Class.forName("com.example.chatapp.location.LocationServiceManager")
            val method = locationServiceClass.getMethod("startLocationService", Context::class.java)
            method.invoke(null, this)
            saveServiceState("location", true)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location service", e)
            saveServiceState("location", false)
        }
    }






    /**
     * Перезапуск сервисов после краша приложения
     */
    fun restartServicesAfterCrash() {
        appScope.launch {
            try {
                Log.d(TAG, "=== RESTARTING SERVICES AFTER CRASH ===")

                // Сохраняем флаг краша
                val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("was_crash", true).apply()

                // Ждем немного для стабильности
                delay(2000)

                // Запускаем шагомер
                startStepCounterService()

                // Проверяем, была ли активна локация
                val wasLocationActive = prefs.getBoolean("location_tracking_active", false)

                if (wasLocationActive) {
                    startLocationService()
                }

                // Очищаем флаг краша после успешного перезапуска
                prefs.edit().putBoolean("was_crash", false).apply()

                Log.d(TAG, "=== SERVICES RESTARTED AFTER CRASH ===")

            } catch (e: Exception) {
                Log.e(TAG, "Error restarting services after crash", e)
                scheduleEmergencyRestart()
            }
        }
    }







    /**
     * Настраивает периодическую проверку сервиса шагомера
     */
    private fun setupStepServiceMonitorWork() {
        try {
            Log.d(TAG, "Setting up StepServiceMonitorWorker")

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false) // Работаем даже при низком заряде
                .build()

            val monitorWork = PeriodicWorkRequestBuilder<StepServiceMonitorWorker>(
                15, TimeUnit.MINUTES, // Проверяем каждые 15 минут
                5, TimeUnit.MINUTES // Гибкое окно
            )
                .setConstraints(constraints)
                .setInitialDelay(5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                STEP_SERVICE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                monitorWork
            )

            Log.d(TAG, "StepServiceMonitorWorker scheduled")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up StepServiceMonitorWorker", e)
        }
    }

    /**
     * Аварийный перезапуск через WorkManager
     */
    private fun scheduleEmergencyRestart() {
        try {
            Log.w(TAG, "Scheduling emergency restart")

            val stepWorkRequest = OneTimeWorkRequestBuilder<StepCounterWorker>()
                .setInitialDelay(2, TimeUnit.MINUTES)
                .addTag("emergency_step_restart")
                .build()

            WorkManager.getInstance(this).enqueue(stepWorkRequest)
            Log.d(TAG, "Emergency restart scheduled")

        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling emergency restart", e)
        }
    }

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

    private fun initializeOneSignalLite() {
        try {
            Log.d(TAG, "Initializing OneSignal LITE...")
            Log.d(TAG, "OneSignal App ID: ${ONESIGNAL_APP_ID.take(8)}...")

            OneSignal.initWithContext(this)
            OneSignal.setAppId(ONESIGNAL_APP_ID)
            OneSignal.setLocationShared(false)
            OneSignal.disablePush(false)

            // Обработчик уведомлений в foreground
            OneSignal.setNotificationWillShowInForegroundHandler { notificationReceivedEvent ->
                Log.d(TAG, "📱 Notification received in foreground")
                notificationReceivedEvent.complete(notificationReceivedEvent.notification)
            }

            // Обработчик открытия уведомлений
            OneSignal.setNotificationOpenedHandler { result ->
                Log.d(TAG, "👆 Notification opened: ${result.notification.title}")
            }

            // Получение playerId после инициализации
            OneSignal.getDeviceState()?.let { deviceState ->
                deviceState.userId?.let { playerId ->
                    if (playerId.isNotBlank()) {
                        Log.d(TAG, "✅ OneSignal Player ID obtained: ${playerId.take(8)}...")
                        saveOneSignalIdToFirebase(playerId)
                    } else {
                        Log.d(TAG, "⏳ OneSignal Player ID is empty, waiting for registration...")
                    }
                }
            }

            Log.d(TAG, "✅ OneSignal LITE initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ OneSignal LITE init error", e)
        }
    }

    private fun saveOneSignalIdToFirebase(playerId: String) {
        try {
            val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            if (currentUserId != null) {
                com.google.firebase.database.FirebaseDatabase.getInstance().reference
                    .child("users").child(currentUserId).child("oneSignalId")
                    .setValue(playerId)
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ OneSignal ID saved to Firebase: ${playerId.take(8)}...")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ Failed to save OneSignal ID to Firebase", e)
                    }
            } else {
                Log.w(TAG, "⚠️ No authenticated user, OneSignal ID will be saved later when user logs in")
                val prefs = getSharedPreferences("oneSignal_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("pending_oneSignal_id", playerId).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving OneSignal ID to Firebase", e)
        }
    }

    /**
     * Сохраняет состояние сервиса
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
     * Получает состояние сервиса
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

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Основной канал для сервиса шагомера
            val stepServiceChannel = NotificationChannel(
                "step_counter_channel",
                "Счетчик шагов",
                NotificationManager.IMPORTANCE_HIGH // ВАЖНО: HIGH для Huawei/Honor
            ).apply {
                description = "Отслеживает ваши шаги в фоне"
                setShowBadge(true)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // Канал для достижений
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

            // Канал для перезапуска сервиса
            val restartChannel = NotificationChannel(
                "service_restart_channel",
                "Перезапуск сервисов",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о перезапуске сервисов"
                setShowBadge(false)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(stepServiceChannel)
            notificationManager.createNotificationChannel(milestoneChannel)
            notificationManager.createNotificationChannel(restartChannel)

            Log.d(TAG, "Notification channels created")
        }
    }

    /**
     * Проверяет, запущен ли сервис шагомера
     */
    fun isStepServiceRunning(): Boolean {
        return try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            manager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == StepCounterService::class.java.name }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking service status", e)
            false
        }
    }

    /**
     * Принудительный перезапуск шагомера
     */
    fun forceRestartStepCounter() {
        appScope.launch {
            try {
                Log.w(TAG, "Force restarting step counter service")

                // Останавливаем сервис если запущен
                if (isStepServiceRunning()) {
                    // Можно отправить broadcast для остановки или просто запустить новый
                    Log.d(TAG, "Service is running, will be replaced")
                }

                // Запускаем заново
                startStepCounterService()

                // Показываем уведомление о перезапуске
                showRestartNotification()

            } catch (e: Exception) {
                Log.e(TAG, "Error in force restart", e)
            }
        }
    }

    /**
     * Показывает уведомление о перезапуске
     */
    private fun showRestartNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notification = Notification.Builder(this, "service_restart_channel")
                .setContentTitle("Шагомер перезапущен")
                .setContentText("Сервис шагомера был перезапущен для стабильной работы")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
        Log.d(TAG, "Application terminated")
    }
}