package com.example.chatapp.step

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.chatapp.R
import com.example.chatapp.activities.MainActivity
import com.example.chatapp.location.LocationUpdateService.Companion.ACTION_START
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
import java.util.concurrent.atomic.AtomicBoolean // <-- Добавляем AtomicBoolean

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
    private var lastMilestoneNotified = 0

    // Атомарный флаг для управления состоянием
    private val isRunning = AtomicBoolean(false) // <-- Добавляем флаг

    // Корутин скоуп для фоновых задач сервиса
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "StepCounterService"
        private const val NOTIFICATION_ID = 12345
        private const val MILESTONE_NOTIFICATION_ID = 12346
        private const val CHANNEL_ID = "step_counter_channel"
        private const val MILESTONE_CHANNEL_ID = "step_milestone_channel"
        const val ACTION_STEPS_UPDATED = "com.example.chatapp.ACTION_STEPS_UPDATED"
        private const val SYNC_INTERVAL_MINUTES = 5L
        private const val MIN_TIME_BETWEEN_STEPS_MS = 300L
        private const val BOOT_TIME_THRESHOLD_MS = 5000L
        private const val WAKE_LOCK_TIMEOUT = 10 * 60 * 1000L
        private const val MILESTONE_STEP = 1000

        fun startService(context: Context) {
            try {
                Log.d(TAG, "Starting step counter service...")

                val serviceIntent = Intent(context, StepCounterService::class.java).apply {
                    action = ACTION_START
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                Log.d(TAG, "Step counter service start command sent")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start step counter service", e)
                // Не вызываем saveServiceState(false) здесь, так как это может быть вызвано из ForegroundServiceLauncher
                // Лучше обработать это в onStartCommand или onCreate
            }
        }

        // Метод для сохранения состояния в StepCounterApp
        private fun saveServiceState(context: Context, isActive: Boolean) {
            try {
                (context.applicationContext as? StepCounterApp)?.saveServiceState("step", isActive)
                Log.d(TAG, "Service state saved via StepCounterApp: step = $isActive")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving service state via StepCounterApp", e)
                // Резервный способ сохранения
                val prefs = context.getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putBoolean("step_service_active", isActive)
                    putLong("last_service_state_change", System.currentTimeMillis())
                    apply()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Сервис создан")

        try {
            // ИНИЦИАЛИЗИРУЕМ WakeLock
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StepCounterService::WakeLock"
            )
            wakeLock.setReferenceCounted(false)

            // ЗАХВАТЫВАЕМ WakeLock С ТАЙМАУТОМ
            if (!wakeLock.isHeld) {
                wakeLock.acquire(WAKE_LOCK_TIMEOUT)
                Log.d(TAG, "WakeLock acquired")
            }

            sharedPreferences = getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createNotificationChannels()

            // ЗАПУСКАЕМ В FOREGROUND СРАЗУ - ЭТО ВАЖНО ДЛЯ ANDROID 12+
            startForeground(NOTIFICATION_ID, createInitialNotification())

            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

            if (stepCounterSensor == null) {
                Log.e(TAG, "Датчик шагов недоступен")
                showSensorUnavailableNotification()
                // Останавливаем сервис если датчик недоступен
                stopSelf()
                return // Важно выйти, чтобы не продолжать инициализацию
            } else {
                // РЕГИСТРИРУЕМ ДАТЧИК БЕЗ ТАЙМАУТА - ПОСТОЯННО
                val success = sensorManager.registerListener(
                    this,
                    stepCounterSensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )
                if (success) {
                    Log.d(TAG, "Датчик шагов зарегистрирован ПОСТОЯННО")
                } else {
                    Log.e(TAG, "Не удалось зарегистрировать датчик шагов")
                    showSensorUnavailableNotification()
                    stopSelf()
                    return // Важно выйти
                }
            }

            // ВОССТАНАВЛИВАЕМ СОСТОЯНИЕ
            initialStepsCount = sharedPreferences.getFloat("initial_step_count", 0f)
            lastTotalStepsCount = sharedPreferences.getFloat("last_total_steps", 0f)
            lastSyncTime = sharedPreferences.getLong("last_sync_time", 0L)
            lastMilestoneNotified = sharedPreferences.getInt("last_milestone", 0)

            // НЕ запускаем синхронизацию и не устанавливаем флаг здесь
            // startPeriodicDataSync() // <-- УБРАТЬ ИЗ onCreate

            Log.d(TAG, "Сервис частично инициализирован, ожидаем onStartCommand")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при создании сервиса", e)
            saveServiceState(this, false)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Сервис получает команду")

        // Устанавливаем флаг ДО выполнения других операций
        if (isRunning.compareAndSet(false, true)) {
            Log.d(TAG, "onStartCommand: Запускаем основную логику сервиса")
            // СОХРАНЯЕМ СОСТОЯНИЕ ЧЕРЕЗ StepCounterApp
            saveServiceState(this, true)

            startPeriodicDataSync()

            // ОБНОВЛЯЕМ УВЕДОМЛЕНИЕ чтобы показать что сервис активен
            updateNotificationWithSteps(getTodaySteps())

            // ЗАПУСКАЕМ СИНХРОНИЗАЦИЮ С ЗАДЕРЖКОЙ чтобы избежать ANR
            serviceScope.launch {
                delay(5000)
                forceFullDataSync()
            }
        } else {
            Log.d(TAG, "onStartCommand: Сервис уже запущен")
            // Если уже запущен, всё равно показываем уведомление, чтобы система не убила сервис
            startForeground(NOTIFICATION_ID, createInitialNotification())
            updateNotificationWithSteps(getTodaySteps())
        }

        return START_STICKY
    }

    private fun getTodaySteps(): Int {
        val todayDateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return sharedPreferences.getInt(todayDateKey, 0)
    }

    private fun showSensorUnavailableNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Счетчик шагов")
            .setContentText("Датчик шагов недоступен")
            .setSmallIcon(R.drawable.ic_walk)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // Метод для сохранения состояния внутри сервиса
    private fun saveServiceState(isRunning: Boolean) {
        try {
            (application as? StepCounterApp)?.saveServiceState("step", isRunning)
            Log.d(TAG, "Состояние сервиса сохранено: step = $isRunning")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения состояния через StepCounterApp", e)
            val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("step_service_active", isRunning)
                putLong("last_service_state_change", System.currentTimeMillis())
                apply()
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
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
                MILESTONE_CHANNEL_ID,
                "Достижения в ходьбе",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о достижении целей"
                setShowBadge(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(milestoneChannel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved: Step service removed, scheduling restart...")

        // ПЕРЕЗАПУСКАЕМ СЕРВИС ДАЖЕ НА ANDROID 12+ - МЫ FOREGROUND SERVICE
        scheduleRestart()

        super.onTaskRemoved(rootIntent)
    }

    private fun scheduleRestart() {
        try {
            val restartIntent = Intent(applicationContext, StepCounterService::class.java)
            val restartPendingIntent = PendingIntent.getService(
                applicationContext,
                2,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 5000,
                restartPendingIntent
            )
            Log.d(TAG, "Перезапуск запланирован через 5 секунд")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка планирования перезапуска", e)
        }
    }

    private fun createInitialNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Счетчик шагов")
            .setContentText("Считаем ваши шаги...")
            .setSmallIcon(R.drawable.ic_walk)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        // Проверяем флаг isRunning перед обработкой
        if (!isRunning.get()) {
            Log.d(TAG, "onSensorChanged: Сервис остановлен, пропускаем")
            return
        }

        event?.let { sensorEvent ->
            if (sensorEvent.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastSensorTimestamp > MIN_TIME_BETWEEN_STEPS_MS) {
                    serviceScope.launch {
                        processNewSteps(sensorEvent.values[0])
                    }
                    lastSensorTimestamp = currentTime
                }
            }
        }
    }

    private suspend fun processNewSteps(totalSteps: Float) {
        // Проверяем флаг isRunning в корутине
        if (!isRunning.get()) {
            Log.d(TAG, "processNewSteps: Сервис остановлен, пропускаем")
            return
        }

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
                    Log.d(TAG, "Сброс счетчика шагов. Всего: $totalSteps")
                    initialStepsCount = totalSteps
                    lastTotalStepsCount = totalSteps

                    sharedPreferences.edit().apply {
                        putFloat("initial_step_count", totalSteps)
                        putFloat("last_total_steps", totalSteps)
                        putLong("last_boot_time", systemBootTime)
                        apply()
                    }
                    return@withContext
                }

                val stepsDifference = totalSteps - lastTotalStepsCount
                if (stepsDifference > 0) {
                    Log.d(TAG, "Обнаружены новые шаги: $stepsDifference")
                    addStepsToStatistics(stepsDifference.toInt())
                    lastTotalStepsCount = totalSteps
                    sharedPreferences.edit().putFloat("last_total_steps", totalSteps).apply()

                    // ОБНОВЛЯЕМ УВЕДОМЛЕНИЕ СРАЗУ
                    updateNotificationWithSteps(getTodaySteps())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка в processNewSteps", e)
            }
        }
    }

    private fun checkAndResetForNewDay() {
        // Проверяем флаг isRunning перед выполнением
        if (!isRunning.get()) {
            Log.d(TAG, "checkAndResetForNewDay: Сервис остановлен, пропускаем")
            return
        }
        val calendar = Calendar.getInstance()
        val todayDateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)

        val lastProcessedDate = sharedPreferences.getString("last_processed_date", "")
        if (lastProcessedDate != todayDateKey) {
            lastMilestoneNotified = 0

            val currentMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
            val lastMonth = sharedPreferences.getString("last_month", "")

            if (currentMonth != lastMonth) {
                sharedPreferences.edit().remove("max_steps_30days").apply()
            }

            sharedPreferences.edit()
                .putString("last_processed_date", todayDateKey)
                .putString("last_month", currentMonth)
                .putInt("last_milestone", 0)
                .apply()

            Log.d(TAG, "Новый день: $todayDateKey, сброс счетчика рубежей")
        }
    }

    private fun addStepsToStatistics(newSteps: Int) {
        // Проверяем флаг isRunning перед выполнением
        if (!isRunning.get()) {
            Log.d(TAG, "addStepsToStatistics: Сервис остановлен, пропускаем")
            return
        }
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

        // Проверка достижения рубежа
        checkMilestoneAchievement(currentStepsToday)

        // СИНХРОНИЗИРУЕМ С FIREBASE
        serviceScope.launch {
            synchronizeWithFirebase(todayDateKey, currentStepsToday)
        }
    }

    private fun checkMilestoneAchievement(currentSteps: Int) {
        // Проверяем флаг isRunning перед выполнением
        if (!isRunning.get()) {
            Log.d(TAG, "checkMilestoneAchievement: Сервис остановлен, пропускаем")
            return
        }
        val currentMilestone = (currentSteps / MILESTONE_STEP) * MILESTONE_STEP

        if (currentMilestone > lastMilestoneNotified && currentMilestone >= MILESTONE_STEP) {
            showMilestoneNotification(currentMilestone)
            lastMilestoneNotified = currentMilestone
            sharedPreferences.edit().putInt("last_milestone", currentMilestone).apply()
            Log.d(TAG, "Достигнут рубеж: $currentMilestone шагов")
        }
    }

    private fun showMilestoneNotification(milestone: Int) {
        // Проверяем флаг isRunning перед выполнением
        if (!isRunning.get()) {
            Log.d(TAG, "showMilestoneNotification: Сервис остановлен, пропускаем")
            return
        }
        val messages = arrayOf(
            "«Путь в тысячу шагов начинается с первого» - и вы уже на $milestone! 🏛️",
            "$milestone шагов к мудрости пройдено. Продолжайте движение, философ! 📜",
            "С каждым шагом вы приближаетесь к гармонии. Уже $milestone на пути! ⚖️",
            "Как говорили стоики: «Преодолей себя!» Вы прошли $milestone шагов! 🏔️",
            "$milestone шагов - это $milestone моментов осознанности. Вы в потоке! 🧘‍♂️"
        )

        val randomMessage = messages.random()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_step_counter", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            MILESTONE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, MILESTONE_CHANNEL_ID)
            .setContentTitle("Философское достижение!")
            .setContentText(randomMessage)
            .setSmallIcon(R.drawable.ic_walk)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(randomMessage))
            .build()

        notificationManager.notify(MILESTONE_NOTIFICATION_ID, notification)
    }

    private fun updateNotificationWithSteps(steps: Int) {
        // Проверяем флаг isRunning перед выполнением
        if (!isRunning.get()) {
            Log.d(TAG, "updateNotificationWithSteps: Сервис остановлен, пропускаем")
            return
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Счетчик шагов")
            .setContentText("Сегодня: $steps шагов")
            .setSmallIcon(R.drawable.ic_walk)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private suspend fun synchronizeWithFirebase(date: String, steps: Int) {
        // Проверяем флаг isRunning в корутине
        if (!isRunning.get()) {
            Log.d(TAG, "synchronizeWithFirebase: Сервис остановлен, пропускаем")
            return
        }
        try {
            FirebaseAuth.getInstance().currentUser?.let { user ->
                firebaseDatabaseReference
                    .child("users")
                    .child(user.uid)
                    .child("stepsData")
                    .child(date)
                    .setValue(steps)
                    .await()
                Log.d(TAG, "Шаги синхронизированы: $steps за $date")
                lastSyncTime = System.currentTimeMillis()
                sharedPreferences.edit().putLong("last_sync_time", lastSyncTime).apply()
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Ошибка синхронизации: ${exception.message}")
        }
    }

    private fun startPeriodicDataSync() {
        // Проверяем флаг isRunning перед выполнением
        if (!isRunning.get()) {
            Log.d(TAG, "startPeriodicDataSync: Сервис остановлен, пропускаем")
            return
        }
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
        scheduledExecutor?.scheduleWithFixedDelay({
            Log.d(TAG, "Выполнение периодической синхронизации данных")
            if (isRunning.get()) { // Проверка внутри задачи планировщика
                serviceScope.launch {
                    forceFullDataSync()
                }
            } else {
                Log.d(TAG, "Планировщик: Сервис остановлен, прерываем задачу")
            }
        }, SYNC_INTERVAL_MINUTES, SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
    }

    private suspend fun forceFullDataSync() {
        // Проверяем флаг isRunning в корутине
        if (!isRunning.get()) {
            Log.d(TAG, "forceFullDataSync: Сервис остановлен, пропускаем")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Принудительная полная синхронизация данных")
                val todayDateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val todaySteps = sharedPreferences.getInt(todayDateKey, 0)

                if (todaySteps > 0) {
                    synchronizeWithFirebase(todayDateKey, todaySteps)
                } else {
                    Log.d(TAG, "forceFullDataSync: Сегодня шагов нет, пропускаем")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка в forceFullDataSync", e)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Остановка сервиса")

        // СОХРАНЯЕМ СОСТОЯНИЕ
        saveServiceState(false)
        isRunning.set(false) // Устанавливаем флаг в false

        try {
            // ОТПИСЫВАЕМСЯ ОТ ДАТЧИКА
            sensorManager.unregisterListener(this)
            scheduledExecutor?.shutdownNow()

            // ОСВОБОЖДАЕМ WakeLock БЕЗОПАСНО
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.d(TAG, "WakeLock released")
            }

            serviceScope.cancel()
            Log.d(TAG, "Ресурсы сервиса освобождены")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при остановке сервиса", e)
        } finally {
            super.onDestroy()
        }
    }
}