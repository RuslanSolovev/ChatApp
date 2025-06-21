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
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class StepCounterService : Service(), SensorEventListener {

    private val database = FirebaseDatabase.getInstance().reference

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var prefs: SharedPreferences
    private var lastSensorTimestamp = 0L
    private var initialSteps = 0f
    private var lastTotalSteps = 0f
    private var lastSyncedTime = 0L
    private var syncExecutor: ScheduledExecutorService? = null

    companion object {
        private const val NOTIFICATION_ID = 12345
        private const val CHANNEL_ID = "step_counter_channel"
        const val ACTION_STEPS_UPDATED = "com.example.chatapp.ACTION_STEPS_UPDATED"
        private const val SYNC_INTERVAL = 15L // минуты

        fun startService(context: Context) {
            val intent = Intent(context, StepCounterService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("StepCounter", "Service created")
        prefs = getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepCounterSensor == null) {
            Log.e("StepCounter", "Step counter sensor not available")
            stopSelf()
        } else {
            sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d("StepCounter", "Step counter sensor registered")
        }

        // Загрузка сохраненных значений
        initialSteps = prefs.getFloat("initial_step_count", 0f)
        lastTotalSteps = prefs.getFloat("last_total_steps", 0f)
        lastSyncedTime = prefs.getLong("last_sync_time", 0)

        // Периодическая синхронизация данных
        startPeriodicSync()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Счетчик шагов",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Подсчет ваших шагов"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Счетчик шагов")
            .setContentText("Идет подсчет ваших шагов...")
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
        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                val now = System.currentTimeMillis()
                // Защита от слишком частых обновлений (не чаще 1 раза в секунду)
                if (now - lastSensorTimestamp > 1000) {
                    processSteps(it.values[0])
                    lastSensorTimestamp = now
                }
            }
        }
    }

    private fun processSteps(totalSteps: Float) {
        val lastBoot = prefs.getLong("last_boot_time", 0)
        val bootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

        // Сброс при первом запуске или после перезагрузки
        if (initialSteps == 0f || bootTime > lastBoot) {
            Log.d("StepCounter", "Инициализация счетчика шагов: $totalSteps")
            initialSteps = totalSteps
            lastTotalSteps = totalSteps

            prefs.edit().apply {
                putFloat("initial_step_count", totalSteps)
                putFloat("last_total_steps", totalSteps)
                putLong("last_boot_time", bootTime)
                apply()
            }
            return
        }

        // Нормальное обновление шагов
        val diff = totalSteps - lastTotalSteps
        if (diff > 0) {
            Log.d("StepCounter", "Обнаружены новые шаги: $diff")
            addStepsToStats(diff.toInt())
            lastTotalSteps = totalSteps
            prefs.edit().putFloat("last_total_steps", totalSteps).apply()
        }
    }

    private fun addStepsToStats(steps: Int) {
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val todaySteps = prefs.getInt(todayKey, 0) + steps

        // Получаем текущее максимальное значение ДО редактирования
        val maxStepsKey = "max_steps_30days"
        val currentMax = prefs.getInt(maxStepsKey, 0)
        val newMax = if (todaySteps > currentMax) todaySteps else currentMax

        prefs.edit().apply {
            putInt(todayKey, todaySteps)
            putInt(maxStepsKey, newMax)
            apply()
        }

        syncWithFirebase(todayKey, todaySteps)
        notifyUiUpdate(todayKey, todaySteps)
    }

    private fun syncWithFirebase(date: String, steps: Int) {
        FirebaseAuth.getInstance().currentUser?.let { user ->
            // Сохраняем только число шагов вместо HashMap
            FirebaseDatabase.getInstance().reference
                .child("users")
                .child(user.uid)
                .child("stepsData")
                .child(date)
                .setValue(steps) // Просто число!
                .addOnSuccessListener {
                    Log.d("StepCounter", "Шаги синхронизированы: $steps за $date")
                    lastSyncedTime = System.currentTimeMillis()
                    prefs.edit().putLong("last_sync_time", lastSyncedTime).apply()
                    updateNotification(steps)
                }
                .addOnFailureListener { e ->
                    Log.e("StepCounter", "Ошибка синхронизации: ${e.message}")
                }
        }
    }

    private fun updateNotification(steps: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Счетчик шагов")
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

    private fun notifyUiUpdate(date: String, steps: Int) {
        val intent = Intent(ACTION_STEPS_UPDATED).apply {
            putExtra("date", date)
            putExtra("steps", steps)
        }
        sendBroadcast(intent)
    }

    private fun startPeriodicSync() {
        syncExecutor = Executors.newSingleThreadScheduledExecutor()
        syncExecutor?.scheduleWithFixedDelay({
            Log.d("StepCounter", "Периодическая проверка синхронизации")
            forceSyncAllData()
        }, SYNC_INTERVAL, SYNC_INTERVAL, TimeUnit.MINUTES)
    }

    private fun forceSyncAllData() {
        Log.d("StepCounter", "Принудительная синхронизация всех данных")
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val todaySteps = prefs.getInt(todayKey, 0)

        if (todaySteps > 0) {
            syncWithFirebase(todayKey, todaySteps)
        }

        // Синхронизация исторических данных
        syncHistoricalData()
    }

    private fun syncHistoricalData() {
        val allEntries = prefs.all
        FirebaseAuth.getInstance().currentUser?.let { user ->
            allEntries.forEach { entry ->
                if (entry.key is String && (entry.key as String).matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    val date = entry.key as String
                    val steps = entry.value as? Int ?: 0

                    FirebaseDatabase.getInstance().reference // Используем полный путь
                        .child("users")
                        .child(user.uid)
                        .child("stepsData")
                        .child(date)
                        .get()
                        .addOnSuccessListener { snapshot ->
                            if (!snapshot.exists()) {
                                // Используем database, которую объявили в классе
                                database.child("users")
                                    .child(user.uid)
                                    .child("stepsData")
                                    .child(date)
                                    .setValue(steps)
                            }
                        }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Не требуется
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        syncExecutor?.shutdown()
        Log.d("StepCounter", "Сервис остановлен")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Перезапускаем сервис при убийстве системы
        return START_STICKY
    }
}