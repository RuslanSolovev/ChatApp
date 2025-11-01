package com.example.chatapp.step

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.work.*
import com.example.chatapp.ForegroundServiceLauncher
import com.example.chatapp.LocationWorker
import com.example.chatapp.StepCounterWorker
import com.onesignal.OneSignal
import com.yandex.mapkit.MapKitFactory
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.TimeUnit

// ⚠️ ОБНОВЛЕННЫЙ ПРАВИЛЬНЫЙ APP ID
const val ONESIGNAL_APP_ID = "0083de8f-7ca0-4824-ac88-9c037278237e"

class StepCounterApp : Application() {

    companion object {
        private const val TAG = "StepCounterApp"
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
            val criticalJobs = listOf(
                async { initializeMapKit() },
                async { setupPeriodicStepSync() },
                async { initializeOneSignalLite() }
            )
            criticalJobs.awaitAll()

            delay(2000)
            safeRestartServices()

            Log.d(TAG, "Application initialization COMPLETED - все сервисы запущены")
        }
    }

    private fun safeRestartServices() {
        appScope.launch {
            try {
                val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
                val wasStepActive = prefs.getBoolean("step_service_active", false)
                val wasLocationActive = prefs.getBoolean("location_tracking_active", false)

                Log.d(TAG, "=== SAFE SERVICE RESTART ===")
                Log.d(TAG, "Step was active: $wasStepActive, Location was active: $wasLocationActive")

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

    fun restartServicesAfterCrash() {
        Log.d(TAG, "restartServicesAfterCrash: Перезапуск сервисов после краша")

        appScope.launch {
            try {
                val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
                val wasStepActive = prefs.getBoolean("step_service_active", false)
                val wasLocationActive = prefs.getBoolean("location_tracking_active", false)

                if (wasStepActive) {
                    ForegroundServiceLauncher.startStepCounterService(this@StepCounterApp)
                }
                if (wasLocationActive) {
                    ForegroundServiceLauncher.startLocationService(this@StepCounterApp)
                }

                Log.d(TAG, "Перезапуск сервисов после краша завершен")
            } catch (e: Exception) {
                Log.e(TAG, "Критическая ошибка при перезапуске сервисов", e)
                scheduleEmergencyWorkersAfterCrash()
            }
        }
    }

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

    private fun initializeOneSignalLite() {
        try {
            Log.d(TAG, "Initializing OneSignal LITE...")
            Log.d(TAG, "OneSignal App ID: ${ONESIGNAL_APP_ID.take(8)}...")

            // Правильная инициализация OneSignal
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
                // Сохраняем временно в SharedPreferences для последующего сохранения
                val prefs = getSharedPreferences("oneSignal_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("pending_oneSignal_id", playerId).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving OneSignal ID to Firebase", e)
        }
    }

    // Метод для сохранения отложенного OneSignal ID после логина пользователя
    fun savePendingOneSignalId(userId: String) {
        try {
            val prefs = getSharedPreferences("oneSignal_prefs", Context.MODE_PRIVATE)
            val pendingOneSignalId = prefs.getString("pending_oneSignal_id", null)

            if (!pendingOneSignalId.isNullOrBlank()) {
                com.google.firebase.database.FirebaseDatabase.getInstance().reference
                    .child("users").child(userId).child("oneSignalId")
                    .setValue(pendingOneSignalId)
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ Pending OneSignal ID saved to Firebase for user: $userId")
                        prefs.edit().remove("pending_oneSignal_id").apply()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ Failed to save pending OneSignal ID", e)
                    }
            }

            // Также сохраняем текущий OneSignal ID если есть
            OneSignal.getDeviceState()?.userId?.let { currentPlayerId ->
                if (currentPlayerId.isNotBlank()) {
                    com.google.firebase.database.FirebaseDatabase.getInstance().reference
                        .child("users").child(userId).child("oneSignalId")
                        .setValue(currentPlayerId)
                        .addOnSuccessListener {
                            Log.d(TAG, "✅ Current OneSignal ID saved to Firebase: ${currentPlayerId.take(8)}...")
                        }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving pending OneSignal ID", e)
        }
    }

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

    fun startServicesFromActivity() {
        Log.d(TAG, "Starting services from activity...")
        ForegroundServiceLauncher.startPendingServices(this)

        appScope.launch {
            if (!getServiceState("step")) {
                ForegroundServiceLauncher.startStepCounterService(this@StepCounterApp)
            }
            if (!getServiceState("location")) {
                ForegroundServiceLauncher.startLocationService(this@StepCounterApp)
            }
        }
    }

    fun hasPendingServices(): Boolean {
        val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("step_service_pending", false) ||
                prefs.getBoolean("location_service_pending", false)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

            // Канал для философских цитат
            val philosophyChannel = NotificationChannel(
                "292588fb-8a77-4b57-8566-b8bb9552ff68",
                "Философские цитаты",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Ежечасные мудрые мысли"
                setShowBadge(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(stepServiceChannel)
            notificationManager.createNotificationChannel(milestoneChannel)
            notificationManager.createNotificationChannel(locationServiceChannel)
            notificationManager.createNotificationChannel(philosophyChannel)

            Log.d(TAG, "All notification channels created (including philosophy)")
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }
}