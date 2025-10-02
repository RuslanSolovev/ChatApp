package com.example.chatapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

class StepCounterService : Service(), SensorEventListener {
    private val firebaseDatabaseReference = FirebaseDatabase.getInstance().reference
    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var wakeLock: PowerManager.WakeLock
    private var lastSensorTimestamp = 0L
    private var initialStepsCount = 0f
    private var lastTotalStepsCount = 0f
    private var lastSyncTime = 0L
    private var scheduledExecutor: ScheduledExecutorService? = null

    // Корутин скоуп для фоновых задач сервиса
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val NOTIFICATION_ID = 12345
        private const val CHANNEL_ID = "step_counter_channel"
        const val ACTION_STEPS_UPDATED = "com.example.chatapp.ACTION_STEPS_UPDATED"
        private const val SYNC_INTERVAL_MINUTES = 5L
        private const val MIN_TIME_BETWEEN_STEPS_MS = 300L
        private const val BOOT_TIME_THRESHOLD_MS = 5000L
        private const val WAKE_LOCK_TIMEOUT = 10 * 60 * 1000L // 10 minutes

        fun startService(context: Context) {
            val serviceIntent = Intent(context, StepCounterService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("StepCounterService", "Service created")

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "StepCounterService::lock"
        ).apply {
            setReferenceCounted(false)
            // Уменьшаем таймаут или убираем acquire, если не критично
            // acquire(WAKE_LOCK_TIMEOUT)
        }

        sharedPreferences = getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createInitialNotification())

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepCounterSensor == null) {
            Log.e("StepCounterService", "Step counter sensor not available")
            stopSelf()
        } else {
            sensorManager.registerListener(
                this,
                stepCounterSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            Log.d("StepCounterService", "Step counter sensor registered")
        }

        initialStepsCount = sharedPreferences.getFloat("initial_step_count", 0f)
        lastTotalStepsCount = sharedPreferences.getFloat("last_total_steps", 0f)
        lastSyncTime = sharedPreferences.getLong("last_sync_time", 0L)

        startPeriodicDataSync()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Step Counter",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracks your steps in background"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createInitialNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Step Counter")
            .setContentText("Counting your steps...")
            .setSmallIcon(R.drawable.ic_walk)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let { sensorEvent ->
            if (sensorEvent.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastSensorTimestamp > MIN_TIME_BETWEEN_STEPS_MS) {
                    // Запускаем обработку в фоновой корутине
                    serviceScope.launch {
                        processNewSteps(sensorEvent.values[0])
                    }
                    lastSensorTimestamp = currentTime
                }
            }
        }
    }

    // Сделана suspend для использования с корутинами
    private suspend fun processNewSteps(totalSteps: Float) {
        withContext(Dispatchers.IO) {
            try {
                checkAndResetForNewDay()

                val now = System.currentTimeMillis()
                val systemBootTime = now - android.os.SystemClock.elapsedRealtime()
                val lastBootTime = sharedPreferences.getLong("last_boot_time", 0L)

                if (initialStepsCount == 0f ||
                    abs(systemBootTime - lastBootTime) > BOOT_TIME_THRESHOLD_MS ||
                    totalSteps < lastTotalStepsCount
                ) {

                    Log.d("StepCounterService", "Resetting step counter. Total: $totalSteps, Last: $lastTotalStepsCount")
                    initialStepsCount = totalSteps
                    lastTotalStepsCount = totalSteps

                    sharedPreferences.edit().apply {
                        putFloat("initial_step_count", totalSteps)
                        putFloat("last_total_steps", totalSteps)
                        putLong("last_boot_time", systemBootTime)
                        apply()
                    }
                    return@withContext // Выходим из suspend функции
                }

                val stepsDifference = totalSteps - lastTotalStepsCount
                if (stepsDifference > 0) {
                    Log.d("StepCounterService", "New steps detected: $stepsDifference")
                    addStepsToStatistics(stepsDifference.toInt())
                    lastTotalStepsCount = totalSteps
                    sharedPreferences.edit().putFloat("last_total_steps", totalSteps).apply()

                    // Немедленная синхронизация при обнаружении новых шагов
                    forceFullDataSync()
                }
            } catch (e: Exception) {
                Log.e("StepCounterService", "Ошибка в processNewSteps", e)
            }
        }
    }


    private fun checkAndResetForNewDay() {
        val calendar = Calendar.getInstance()
        val todayDateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)

        val lastProcessedDate = sharedPreferences.getString("last_processed_date", "")
        if (lastProcessedDate != todayDateKey) {
            val currentMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
            val lastMonth = sharedPreferences.getString("last_month", "")

            if (currentMonth != lastMonth) {
                sharedPreferences.edit().remove("max_steps_30days").apply()
            }

            sharedPreferences.edit()
                .putString("last_processed_date", todayDateKey)
                .putString("last_month", currentMonth)
                .apply()
        }
    }

    private fun addStepsToStatistics(newSteps: Int) {
        val todayDateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val currentStepsToday = sharedPreferences.getInt(todayDateKey, 0) + newSteps

        val maxStepsKey = "max_steps_30days"
        val currentMaxSteps = sharedPreferences.getInt(maxStepsKey, 0)
        val newMaxSteps = max(currentStepsToday, currentMaxSteps)

        sharedPreferences.edit().apply {
            putInt(todayDateKey, currentStepsToday)
            putInt(maxStepsKey, newMaxSteps)
            apply()
        }

        // Синхронизация и обновление UI также в фоне
        serviceScope.launch {
            synchronizeWithFirebase(todayDateKey, currentStepsToday)
            // notifyUIUpdate(todayDateKey, currentStepsToday) // Переносим в Main внутри функции
        }
    }

    private suspend fun synchronizeWithFirebase(date: String, steps: Int) {
        try {
            FirebaseAuth.getInstance().currentUser?.let { user ->
                firebaseDatabaseReference
                    .child("users")
                    .child(user.uid)
                    .child("stepsData")
                    .child(date)
                    .setValue(steps)
                    .await() // Используем await для корутин
                Log.d("StepCounterService", "Steps synchronized: $steps for $date")
                lastSyncTime = System.currentTimeMillis()
                sharedPreferences.edit().putLong("last_sync_time", lastSyncTime).apply()

                // Обновление уведомления и отправка broadcast в Main потоке
                withContext(Dispatchers.Main) {
                    updateNotificationWithSteps(steps)
                    notifyUIUpdate(date, steps)
                }

            }
        } catch (exception: Exception) {
            Log.e("StepCounterService", "Sync error: ${exception.message}", exception)
            // Повторная попытка через 30 секунд
            delay(30000)
            synchronizeWithFirebase(date, steps) // Рекурсивный вызов внутри корутины
        }
    }


    private fun updateNotificationWithSteps(steps: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Step Counter")
            .setContentText("Сегодня: $steps шагов")
            .setSmallIcon(R.drawable.ic_walk)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun notifyUIUpdate(date: String, steps: Int) {
        try {
            // Отправляем broadcast как раньше
            val updateIntent = Intent(ACTION_STEPS_UPDATED).apply {
                putExtra("date", date)
                putExtra("steps", steps)
            }
            sendBroadcast(updateIntent)

            // Дополнительно можно обновлять ViewModel через Application
            (application as? StepCounterApp)?.updateStepsInViewModel(steps)
        } catch (e: Exception) {
            Log.e("StepCounterService", "Ошибка при уведомлении UI", e)
        }
    }

    private fun startPeriodicDataSync() {
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
        scheduledExecutor?.scheduleWithFixedDelay({
            Log.d("StepCounterService", "Performing periodic data sync")
            // Запускаем синхронизацию в корутине
            serviceScope.launch {
                forceFullDataSync()
            }
        }, SYNC_INTERVAL_MINUTES, SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
    }

    private suspend fun forceFullDataSync() {
        withContext(Dispatchers.IO) {
            try {
                Log.d("StepCounterService", "Forcing full data synchronization")
                val todayDateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val todaySteps = sharedPreferences.getInt(todayDateKey, 0)

                if (todaySteps > 0) {
                    synchronizeWithFirebase(todayDateKey, todaySteps)
                }

                synchronizeHistoricalData()
            } catch (e: Exception) {
                Log.e("StepCounterService", "Ошибка в forceFullDataSync", e)
            }
        }
    }

    private suspend fun synchronizeHistoricalData() {
        withContext(Dispatchers.IO) {
            try {
                val allEntries = sharedPreferences.all
                FirebaseAuth.getInstance().currentUser?.let { user ->
                    val batchUpdates = hashMapOf<String, Any>()

                    allEntries.forEach { entry ->
                        if (entry.key is String && (entry.key as String).matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                            val date = entry.key as String
                            val steps = entry.value as? Int ?: 0
                            batchUpdates["users/${user.uid}/stepsData/$date"] = steps
                        }
                    }

                    if (batchUpdates.isNotEmpty()) {
                        firebaseDatabaseReference.updateChildren(batchUpdates)
                            .await() // Используем await для корутин
                        Log.d("StepCounterService", "Historical data synchronized")
                    }
                }
            } catch (exception: Exception) {
                Log.e("StepCounterService", "Historical sync error: ${exception.message}", exception)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        scheduledExecutor?.shutdown()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        // Отменяем все фоновые задачи сервиса
        serviceScope.cancel()
        Log.d("StepCounterService", "Service destroyed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_REDELIVER_INTENT
    }
}