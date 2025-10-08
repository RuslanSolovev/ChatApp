package com.example.chatapp.step

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.chatapp.R
import com.example.chatapp.activities.MainActivity
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
    private var lastMilestoneNotified = 0 // Последний отпразднованный рубеж

    // Корутин скоуп для фоновых задач сервиса
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val NOTIFICATION_ID = 12345
        private const val MILESTONE_NOTIFICATION_ID = 12346 // Фиксированный ID для уведомлений о достижениях
        private const val CHANNEL_ID = "step_counter_channel"
        private const val MILESTONE_CHANNEL_ID = "step_milestone_channel"
        const val ACTION_STEPS_UPDATED = "com.example.chatapp.ACTION_STEPS_UPDATED"
        private const val SYNC_INTERVAL_MINUTES = 5L
        private const val MIN_TIME_BETWEEN_STEPS_MS = 300L
        private const val BOOT_TIME_THRESHOLD_MS = 5000L
        private const val WAKE_LOCK_TIMEOUT = 10 * 60 * 1000L // 10 minutes
        private const val MILESTONE_STEP = 1000 // Уведомление каждые 1000 шагов

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
        Log.d("StepCounterService", "Сервис создан")

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "StepCounterService::lock"
        ).apply {
            setReferenceCounted(false)
        }

        sharedPreferences = getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, createInitialNotification())

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepCounterSensor == null) {
            Log.e("StepCounterService", "Датчик шагов недоступен")
            stopSelf()
        } else {
            sensorManager.registerListener(
                this,
                stepCounterSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            Log.d("StepCounterService", "Датчик шагов зарегистрирован")
        }

        initialStepsCount = sharedPreferences.getFloat("initial_step_count", 0f)
        lastTotalStepsCount = sharedPreferences.getFloat("last_total_steps", 0f)
        lastSyncTime = sharedPreferences.getLong("last_sync_time", 0L)
        lastMilestoneNotified = sharedPreferences.getInt("last_milestone", 0)

        startPeriodicDataSync()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Основной канал для сервиса
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Счетчик шагов",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Отслеживает ваши шаги в фоне"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            // Канал для мотивационных уведомлений
            val milestoneChannel = NotificationChannel(
                MILESTONE_CHANNEL_ID,
                "Достижения в ходьбе",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о достижении целей"
                setShowBadge(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // Устанавливаем легкую вибрацию для уведомлений о достижениях
                vibrationPattern = longArrayOf(0, 200, 100, 200)
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(milestoneChannel)
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
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onSensorChanged(event: SensorEvent?) {
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
                    Log.d("StepCounterService", "Сброс счетчика шагов. Всего: $totalSteps, Последние: $lastTotalStepsCount")
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
                    Log.d("StepCounterService", "Обнаружены новые шаги: $stepsDifference")
                    addStepsToStatistics(stepsDifference.toInt())
                    lastTotalStepsCount = totalSteps
                    sharedPreferences.edit().putFloat("last_total_steps", totalSteps).apply()

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
            // Сброс счетчика рубежей при новом дне
            sharedPreferences.edit().putInt("last_milestone", 0).apply()
            lastMilestoneNotified = 0

            val currentMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
            val lastMonth = sharedPreferences.getString("last_month", "")

            if (currentMonth != lastMonth) {
                sharedPreferences.edit().remove("max_steps_30days").apply()
            }

            sharedPreferences.edit()
                .putString("last_processed_date", todayDateKey)
                .putString("last_month", currentMonth)
                .apply()

            Log.d("StepCounterService", "Новый день: $todayDateKey, сброс счетчика рубежей")
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

        // Проверка достижения рубежа
        checkMilestoneAchievement(currentStepsToday)

        serviceScope.launch {
            synchronizeWithFirebase(todayDateKey, currentStepsToday)
        }
    }

    private fun checkMilestoneAchievement(currentSteps: Int) {
        val currentMilestone = (currentSteps / MILESTONE_STEP) * MILESTONE_STEP

        // Проверяем, достигли ли мы нового рубежа (1000, 2000, 3000 и т.д.)
        if (currentMilestone > lastMilestoneNotified && currentMilestone >= MILESTONE_STEP) {
            showMilestoneNotification(currentMilestone)
            lastMilestoneNotified = currentMilestone
            sharedPreferences.edit().putInt("last_milestone", currentMilestone).apply()
            Log.d("StepCounterService", "Достигнут рубеж: $currentMilestone шагов")
        }
    }

    private fun showMilestoneNotification(milestone: Int) {
        // ФИЛОСОФСКИЕ МОТИВАЦИОННЫЕ СООБЩЕНИЯ
        val messages = arrayOf(
            "«Путь в тысячу шагов начинается с первого» - и вы уже на $milestone! 🏛️",
            "$milestone шагов к мудрости пройдено. Продолжайте движение, философ! 📜",
            "С каждым шагом вы приближаетесь к гармонии. Уже $milestone на пути! ⚖️",
            "Как говорили стоики: «Преодолей себя!» Вы прошли $milestone шагов! 🏔️",
            "$milestone шагов - это $milestone моментов осознанности. Вы в потоке! 🧘‍♂️",
            "По Сократу: «Познай себя через движение». Вы на пути - $milestone шагов! 🔍",
            "Ваши $milestone шагов - это диалог души с телом. Продолжайте беседу! 💭",
            "Аристотель бы одобрил: $milestone шагов к совершенной форме! 🏛️",
            "«Бытие определяется движением» - и ваше бытие уже $milestone шагов! 🌌",
            "Платон улыбнулся бы: $milestone шагов к миру идей через мир тела! ✨",
            "Стоики гордились бы вашей дисциплиной: $milestone шагов! 💪",
            "По Конфуцию: «Дорога в тысячу ли начинается с первого шага» - у вас $milestone! 🛣️",
            "Ваши $milestone шагов - это медитация в движении. Продолжайте! 🧠",
            "Ницше сказал бы: «Стань тем, кто ты есть!» Через $milestone шагов! 🔥",
            "$milestone шагов к атараксии - душевному спокойствию через движение! 🌊",
            "По Гераклиту: «Всё течёт, всё меняется» - и вы прошли $milestone шагов! 🌊",
            "Сенека бы отметил: $milestone шагов к добродетели через заботу о себе! 🎭",
            "Ваши $milestone шагов - это воплощение «Познай самого себя»! 🏺",
            "По Лао-цзы: «Путь в тысячу ли начинается под ногами» - у вас $milestone! 🐉",
            "$milestone шагов к катарсису через физическое очищение! 🌬️",
            "Эпикур улыбнулся: $milestone шагов к умеренному удовольствию! 🍇",
            "По Декарту: «Я двигаюсь, значит, существую» - $milestone раз! 🤔",
            "Ваши $milestone шагов - это практика настоящего момента! ⏳",
            "Марк Аврелий бы записал: «Сегодня я сделал $milestone шагов к совершенству»! 📖",
            "Пифагор бы оценил: $milestone - прекрасное число на пути к гармонии! 🔢"
        )

        val randomMessage = messages.random()

        // Создаем интент для открытия приложения при нажатии на уведомление
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

        // Создаем билдер уведомления БЕЗ категории (убрали проблемный код)
        val notification = NotificationCompat.Builder(this, MILESTONE_CHANNEL_ID)
            .setContentTitle("Философское достижение!")
            .setContentText(randomMessage)
            .setSmallIcon(R.drawable.ic_walk)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(NotificationCompat.BigTextStyle().bigText(randomMessage))
            .setOnlyAlertOnce(true)
            .build()

        // Используем фиксированный ID, чтобы обновлять уведомление, а не создавать новое
        notificationManager.notify(MILESTONE_NOTIFICATION_ID, notification)
        Log.d("StepCounterService", "Показано философское уведомление: $milestone шагов")
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
                    .await()
                Log.d("StepCounterService", "Шаги синхронизированы: $steps за $date")
                lastSyncTime = System.currentTimeMillis()
                sharedPreferences.edit().putLong("last_sync_time", lastSyncTime).apply()

                withContext(Dispatchers.Main) {
                    updateNotificationWithSteps(steps)
                    notifyUIUpdate(date, steps)
                }
            }
        } catch (exception: Exception) {
            Log.e("StepCounterService", "Ошибка синхронизации: ${exception.message}", exception)
            delay(30000)
            synchronizeWithFirebase(date, steps)
        }
    }

    private fun updateNotificationWithSteps(steps: Int) {
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

    private fun notifyUIUpdate(date: String, steps: Int) {
        try {
            val updateIntent = Intent(ACTION_STEPS_UPDATED).apply {
                putExtra("date", date)
                putExtra("steps", steps)
            }
            sendBroadcast(updateIntent)

            (application as? StepCounterApp)?.updateStepsInViewModel(steps)
        } catch (e: Exception) {
            Log.e("StepCounterService", "Ошибка при уведомлении UI", e)
        }
    }

    private fun startPeriodicDataSync() {
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
        scheduledExecutor?.scheduleWithFixedDelay({
            Log.d("StepCounterService", "Выполнение периодической синхронизации данных")
            serviceScope.launch {
                forceFullDataSync()
            }
        }, SYNC_INTERVAL_MINUTES, SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
    }

    private suspend fun forceFullDataSync() {
        withContext(Dispatchers.IO) {
            try {
                Log.d("StepCounterService", "Принудительная полная синхронизация данных")
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
                        firebaseDatabaseReference.updateChildren(batchUpdates).await()
                        Log.d("StepCounterService", "Исторические данные синхронизированы")
                    }
                }
            } catch (exception: Exception) {
                Log.e("StepCounterService", "Ошибка исторической синхронизации: ${exception.message}", exception)
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
        serviceScope.cancel()
        Log.d("StepCounterService", "Сервис уничтожен")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_REDELIVER_INTENT
    }
}