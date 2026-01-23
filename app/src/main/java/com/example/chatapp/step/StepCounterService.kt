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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StepCounterService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "StepCounterService"
        private const val NOTIFICATION_ID = 12345
        private const val MILESTONE_NOTIFICATION_ID = 12346
        private const val CHANNEL_ID = "step_counter_channel"
        private const val MILESTONE_CHANNEL_ID = "step_milestone_channel"
        const val ACTION_STEPS_UPDATED = "com.example.chatapp.ACTION_STEPS_UPDATED"
        private const val SYNC_INTERVAL_MINUTES = 5L
        private const val MIN_TIME_BETWEEN_STEPS_MS = 300L
        private const val BOOT_TIME_THRESHOLD_MS = 60_000L // 60 секунд
        private const val WAKE_LOCK_TIMEOUT = 10 * 60 * 1000L
        private const val MILESTONE_STEP = 1000
        const val ACTION_START = "com.example.chatapp.step.StepCounterService.START"

        fun startService(context: Context) {
            try {
                Log.d(TAG, "Запуск сервиса шагомера...")

                val serviceIntent = Intent(context, StepCounterService::class.java).apply {
                    action = ACTION_START
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                Log.d(TAG, "Команда запуска сервиса отправлена")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка запуска сервиса шагомера", e)
            }
        }
    }

    private val firebaseDatabase = FirebaseDatabase.getInstance()
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
    private val isRunning = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var stepDataManager: StepDataManager
    private var stepsToday = 0
    private var lastProcessedDate = ""
    private var bootTimestamp = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Создание сервиса шагомера")

        try {
            // Инициализация StepDataManager
            stepDataManager = StepDataManager.getInstance(this)

            // Проверяем, была ли недавняя перезагрузка
            checkIfBootRecently()

            // Инициализация WakeLock
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StepCounterService::WakeLock"
            )
            wakeLock.setReferenceCounted(false)

            // Захватываем WakeLock
            if (!wakeLock.isHeld) {
                wakeLock.acquire(WAKE_LOCK_TIMEOUT)
                Log.d(TAG, "WakeLock захвачен")
            }

            // Инициализация компонентов
            sharedPreferences = getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createNotificationChannels()

            // Запуск в фоне с уведомлением
            startForeground(NOTIFICATION_ID, createInitialNotification())

            // Инициализация датчика шагов
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            initializeStepSensor()

            // Восстановление состояния
            restoreServiceState()

            Log.d(TAG, "Сервис успешно создан")

        } catch (e: Exception) {
            Log.e(TAG, "Критическая ошибка при создании сервиса", e)
            stopSelf()
        }
    }

    private fun checkIfBootRecently() {
        val lastBootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val timeSinceBoot = System.currentTimeMillis() - lastBootTime
        bootTimestamp = lastBootTime

        Log.d(TAG, "Последняя перезагрузка: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastBootTime))}")
        Log.d(TAG, "Прошло с перезагрузки: ${timeSinceBoot / 1000} секунд")

        if (timeSinceBoot < BOOT_TIME_THRESHOLD_MS) {
            Log.w(TAG, "Обнаружена недавняя перезагрузка, сбрасываем начальные значения датчика")
        }
    }

    private fun initializeStepSensor() {
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepCounterSensor == null) {
            Log.w(TAG, "Датчик TYPE_STEP_COUNTER недоступен, пробуем другие датчики")

            // Пробуем разные типы датчиков
            val availableSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            for (sensor in availableSensors) {
                Log.d(TAG, "Доступный датчик: ${sensor.name} (тип: ${sensor.type})")

                // Ищем шагомер или детектор шагов
                if (sensor.name.contains("step", ignoreCase = true) ||
                    sensor.name.contains("pedometer", ignoreCase = true)) {
                    stepCounterSensor = sensor
                    Log.d(TAG, "Найден альтернативный датчик шагов: ${sensor.name}")
                    break
                }
            }

            if (stepCounterSensor == null) {
                stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            }
        }

        if (stepCounterSensor != null) {
            val success = sensorManager.registerListener(
                this,
                stepCounterSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )

            if (success) {
                Log.d(TAG, "Датчик шагов успешно зарегистрирован: ${stepCounterSensor?.name}")
                Log.d(TAG, "Максимальный диапазон: ${stepCounterSensor?.maximumRange}")
                Log.d(TAG, "Разрешение: ${stepCounterSensor?.resolution}")
                Log.d(TAG, "Мощность: ${stepCounterSensor?.power}")
            } else {
                Log.e(TAG, "Не удалось зарегистрировать датчик шагов")
                showSensorUnavailableNotification()
            }
        } else {
            Log.e(TAG, "На устройстве нет датчиков шагов")
            showSensorUnavailableNotification()
        }
    }

    private fun restoreServiceState() {
        val todayDate = getTodayDate()

        // Восстанавливаем шаги через StepDataManager
        stepsToday = stepDataManager.getSteps(todayDate)

        Log.d(TAG, "Восстановлено шагов за сегодня: $stepsToday")

        // Восстанавливаем состояние датчика из SharedPreferences
        initialStepsCount = sharedPreferences.getFloat("initial_steps_$todayDate", 0f)
        lastTotalStepsCount = sharedPreferences.getFloat("last_total_$todayDate", 0f)
        lastMilestoneNotified = sharedPreferences.getInt("last_milestone_$todayDate", 0)
        lastProcessedDate = sharedPreferences.getString("last_processed_date", "") ?: ""

        Log.d(TAG, "Состояние датчика: initial=$initialStepsCount, lastTotal=$lastTotalStepsCount")


        // Запускаем периодическую синхронизацию
        startPeriodicDataSync()
    }













    private suspend fun processStepCounterEvent(totalSteps: Float) {
        withContext(Dispatchers.IO) {
            try {
                checkForNewDay()

                Log.d(TAG, "Обработка счетчика шагов: totalSteps=$totalSteps, initial=$initialStepsCount, lastTotal=$lastTotalStepsCount")

                // Проверяем сброс датчика
                val timeSinceBoot = System.currentTimeMillis() - bootTimestamp
                val isRecentBoot = timeSinceBoot < BOOT_TIME_THRESHOLD_MS

                if (isRecentBoot || totalSteps < lastTotalStepsCount || totalSteps < initialStepsCount) {
                    Log.d(TAG, "Сброс датчика: total=$totalSteps, initial=$initialStepsCount, lastTotal=$lastTotalStepsCount")
                    initialStepsCount = totalSteps
                    lastTotalStepsCount = totalSteps
                    saveStepState()
                    return@withContext
                }

                // Если это первый запуск сегодня
                if (initialStepsCount == 0f) {
                    initialStepsCount = totalSteps
                    lastTotalStepsCount = totalSteps
                    saveStepState()
                    Log.d(TAG, "Инициализирован счетчик шагов: $totalSteps")
                    return@withContext
                }

                // Рассчитываем новые шаги
                val newSteps = (totalSteps - lastTotalStepsCount).toInt()

                if (newSteps > 0) {
                    Log.d(TAG, "Обнаружены новые шаги: $newSteps")

                    // Используем StepDataManager для сохранения
                    addStepsToStatistics(newSteps)
                    lastTotalStepsCount = totalSteps
                    saveStepState()

                    // Обновляем уведомление
                    updateNotificationWithSteps(stepsToday)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обработки данных датчика", e)
            }
        }
    }

    private fun addStepsToStatistics(newSteps: Int) {
        val todayDate = getTodayDate()

        // Обновляем шаги за сегодня
        stepsToday += newSteps

        Log.d(TAG, "Добавляем шаги: $newSteps, всего: $stepsToday")

        // Сохраняем через StepDataManager с проверкой
        val success = stepDataManager.saveStepsWithRetry(todayDate, stepsToday)

        if (success) {
            Log.d(TAG, "Шаги успешно сохранены в StepDataManager")
        } else {
            Log.e(TAG, "Не удалось сохранить шаги в StepDataManager")
            // Пробуем сохранить в старый SharedPreferences как fallback

        }

        // Проверяем достижение целей
        checkMilestoneAchievement(stepsToday)

        // Отправляем broadcast для обновления UI
        sendStepsUpdateBroadcast()

        // Синхронизируем с Firebase
        serviceScope.launch {
            synchronizeWithFirebase(todayDate, stepsToday)
        }
    }

    private fun checkForNewDay() {
        val todayDate = getTodayDate()

        if (lastProcessedDate != todayDate) {
            Log.d(TAG, "Обнаружен новый день: $todayDate (было: $lastProcessedDate)")

            // 🔴 УДАЛЕНО: не сохраняем stepsToday за вчерашний день
            // Это приводило к искажению статистики

            // Получаем сохранённые шаги за сегодня из надёжного источника
            stepsToday = stepDataManager.getSteps(todayDate)
            Log.d(TAG, "Восстановлено шагов за сегодня из StepDataManager: $stepsToday")

            // Сбрасываем счётчики датчика для нового дня
            initialStepsCount = 0f
            lastTotalStepsCount = 0f
            lastMilestoneNotified = 0

            // Сохраняем состояние нового дня
            sharedPreferences.edit().apply {
                putString("last_processed_date", todayDate)
                putFloat("initial_steps_$todayDate", 0f)
                putFloat("last_total_$todayDate", 0f)
                putInt("last_milestone_$todayDate", 0)
                apply()
            }

            lastProcessedDate = todayDate

            // Сообщаем о новом дне
            sendStepsUpdateBroadcast()
        }
    }





    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Основной канал для сервиса
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Счетчик шагов",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Отслеживает ваши шаги в фоновом режиме"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // Канал для достижений
            val milestoneChannel = NotificationChannel(
                MILESTONE_CHANNEL_ID,
                "Достижения",
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

    private fun createInitialNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Счетчик шагов")
            .setContentText("Отслеживание активности...")
            .setSmallIcon(R.drawable.ic_walk)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun showSensorUnavailableNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Счетчик шагов")
            .setContentText("Датчик шагов недоступен на этом устройстве")
            .setSmallIcon(R.drawable.ic_walk)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning.get()) return

        event?.let { sensorEvent ->
            val sensorType = sensorEvent.sensor.type
            val isStepCounter = sensorType == Sensor.TYPE_STEP_COUNTER
            val isStepDetector = sensorType == Sensor.TYPE_STEP_DETECTOR

            if (isStepCounter || isStepDetector) {
                val currentTime = System.currentTimeMillis()

                if (isStepCounter) {
                    // TYPE_STEP_COUNTER: накопительный счетчик
                    if (currentTime - lastSensorTimestamp > MIN_TIME_BETWEEN_STEPS_MS) {
                        serviceScope.launch {
                            processStepCounterEvent(sensorEvent.values[0])
                        }
                        lastSensorTimestamp = currentTime
                    }
                } else if (isStepDetector) {
                    // TYPE_STEP_DETECTOR: детектор отдельных шагов
                    if (sensorEvent.values[0] == 1.0f) {
                        if (currentTime - lastSensorTimestamp > MIN_TIME_BETWEEN_STEPS_MS) {
                            serviceScope.launch {
                                processSingleStep()
                            }
                            lastSensorTimestamp = currentTime
                        }
                    }
                }

                Log.v(TAG, "Событие датчика: тип=$sensorType, значение=${sensorEvent.values[0]}")
            }
        }
    }





    private suspend fun processSingleStep() {
        withContext(Dispatchers.IO) {
            try {
                checkForNewDay()

                // TYPE_STEP_DETECTOR: каждый вызов = 1 шаг
                Log.d(TAG, "Обнаружен один шаг (детектор)")
                addStepsToStatistics(1)

                // Обновляем уведомление
                updateNotificationWithSteps(stepsToday)

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обработки детектора шагов", e)
            }
        }
    }



    private fun getTodayDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }



    private fun checkMilestoneAchievement(currentSteps: Int) {
        val currentMilestone = (currentSteps / MILESTONE_STEP) * MILESTONE_STEP

        if (currentMilestone > lastMilestoneNotified && currentMilestone >= MILESTONE_STEP) {
            showMilestoneNotification(currentMilestone)
            lastMilestoneNotified = currentMilestone

            // Сохраняем последний достигнутый рубеж
            val todayDate = getTodayDate()
            sharedPreferences.edit()
                .putInt("last_milestone_$todayDate", currentMilestone)
                .apply()

            Log.d(TAG, "Достигнут рубеж: $currentMilestone шагов")
        }
    }

    private fun showMilestoneNotification(milestone: Int) {
        val messages = listOf(
            "Отличный результат! Вы прошли $milestone шагов сегодня! 🎯",
            "$milestone шагов - это впечатляет! Так держать! 💪",
            "Поздравляем! Вы достигли $milestone шагов! 🏆",
            "Ура! Вы прошли $milestone шагов сегодня! 🌟",
            "Ваша активность на высоте! Уже $milestone шагов! 🚀"
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
            .setContentTitle("Достижение!")
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

    private fun sendStepsUpdateBroadcast() {
        val intent = Intent(ACTION_STEPS_UPDATED)
        sendBroadcast(intent)
        Log.d(TAG, "Broadcast отправлен: $ACTION_STEPS_UPDATED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Получена команда запуска")

        if (isRunning.compareAndSet(false, true)) {
            Log.d(TAG, "Сервис переходит в активное состояние")

            // 🔹 Сразу обновляем уведомление с актуальными шагами
            updateNotificationWithSteps(stepsToday)

            // 🔹 Проверяем, не наступил ли новый день
            checkForNewDay()

            // 🔹 Немедленно отправляем текущие шаги в UI (даже если новых шагов нет)
            sendStepsUpdateBroadcast()

            // 🔹 Принудительно синхронизируем с Firebase
            serviceScope.launch {
                delay(1000) // небольшая задержка для стабилизации
                val todayDate = getTodayDate()
                val currentSteps = stepDataManager.getSteps(todayDate)
                synchronizeWithFirebase(todayDate, currentSteps)
                forceFullDataSync() // также синхронизируем историю
            }
        }

        return START_STICKY
    }

    private fun saveStepState() {
        val todayDate = getTodayDate()
        sharedPreferences.edit().apply {
            putFloat("initial_steps_$todayDate", initialStepsCount)
            putFloat("last_total_$todayDate", lastTotalStepsCount)
            apply()
        }
    }

    private suspend fun synchronizeWithFirebase(date: String, steps: Int) {
        try {
            FirebaseAuth.getInstance().currentUser?.let { user ->
                firebaseDatabase.reference
                    .child("users")
                    .child(user.uid)
                    .child("stepsData")
                    .child(date)
                    .setValue(steps)
                    .await()

                lastSyncTime = System.currentTimeMillis()
                sharedPreferences.edit().putLong("last_sync_time", lastSyncTime).apply()

                Log.d(TAG, "Шаги синхронизированы с Firebase: $steps за $date")
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Ошибка синхронизации с Firebase: ${exception.message}")
        }
    }

    private fun startPeriodicDataSync() {
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
        scheduledExecutor?.scheduleWithFixedDelay({
            if (isRunning.get()) {
                serviceScope.launch {
                    forceFullDataSync()
                }
            }
        }, SYNC_INTERVAL_MINUTES, SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
    }

    private suspend fun forceFullDataSync() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Принудительная синхронизация всех данных")
                val todayDate = getTodayDate()
                val todaySteps = stepDataManager.getSteps(todayDate)

                if (todaySteps > 0) {
                    synchronizeWithFirebase(todayDate, todaySteps)
                }

                // Синхронизация исторических данных
                synchronizeHistoricalData()

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка принудительной синхронизации", e)
            }
        }
    }

    private suspend fun synchronizeHistoricalData() {
        FirebaseAuth.getInstance().currentUser?.let { user ->
            // Получаем все даты и шаги через StepDataManager
            val allDates = getLast30Days()
            val batchUpdates = hashMapOf<String, Any>()

            allDates.forEach { date ->
                val steps = stepDataManager.getSteps(date)
                if (steps > 0) {
                    batchUpdates["users/${user.uid}/stepsData/$date"] = steps
                }
            }

            if (batchUpdates.isNotEmpty()) {
                firebaseDatabase.reference.updateChildren(batchUpdates).await()
                Log.d(TAG, "Исторические данные синхронизированы: ${batchUpdates.size} записей")
            }
        }
    }

    private fun getLast30Days(): List<String> {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dates = mutableListOf<String>()

        repeat(30) {
            dates.add(dateFormat.format(calendar.time))
            calendar.add(Calendar.DATE, -1)
        }

        return dates
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Точность датчика изменилась: $accuracy")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Остановка сервиса шагомера")

        isRunning.set(false)

        try {
            // Отписываемся от датчика
            sensorManager.unregisterListener(this)

            // Останавливаем планировщик
            scheduledExecutor?.shutdownNow()

            // Освобождаем WakeLock
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.d(TAG, "WakeLock освобожден")
            }

            // Отменяем корутины
            serviceScope.cancel()

            // Сохраняем последние данные
            val todayDate = getTodayDate()
            stepDataManager.saveSteps(todayDate, stepsToday)

            Log.d(TAG, "Ресурсы сервиса освобождены")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при остановке сервиса", e)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}