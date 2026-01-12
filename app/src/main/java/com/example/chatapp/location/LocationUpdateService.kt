package com.example.chatapp.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.chatapp.R
import com.example.chatapp.models.UserLocation
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

class LocationUpdateService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var handler: Handler
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var notificationManager: NotificationManager

    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()

    private val isRunning = AtomicBoolean(false)
    private val isLocationUpdatesActive = AtomicBoolean(false)

    private var lastLocation: Location? = null
    private var lastValidLocation: Location? = null
    private var currentColorIndex = 0
    private val routeColors = listOf(Color.BLUE, Color.RED, Color.GREEN, Color.MAGENTA, Color.CYAN)
    private var locationUpdatesAttempts = 0

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val TAG = "LocationUpdateService"
        const val NOTIFICATION_CHANNEL_ID = "location_tracker_channel"
        const val NOTIFICATION_CHANNEL_NAME = "Отслеживание маршрута"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "START_LOCATION_TRACKING"
        const val ACTION_STOP = "STOP_LOCATION_TRACKING"
        const val ACTION_PAUSE = "PAUSE_LOCATION_TRACKING"
        const val ACTION_RESUME = "RESUME_LOCATION_TRACKING"

        // Оптимизированные настройки для баланса точности и батареи
        private const val LOCATION_UPDATE_INTERVAL = 30000L // 30 секунд
        private const val FASTEST_UPDATE_INTERVAL = 15000L   // 15 секунд
        private const val MIN_DISTANCE = 20f               // 20 метров
        private const val MAX_ACCURACY = 100f              // Максимальная погрешность 100м
        private const val MIN_ACCURACY = 5f                // Минимальная точность 5м (для GPS)
        private const val MAX_SPEED = 55.56f               // 200 км/ч в м/с

        // Настройки для разных состояний
        private const val BACKGROUND_UPDATE_INTERVAL = 60000L // 60 секунд в фоне
        private const val HIGH_ACCURACY_TIMEOUT = 30000L      // 30 сек высокой точности

        // Максимальное количество попыток перезапуска
        private const val MAX_RETRY_ATTEMPTS = 3

        fun startService(context: Context) {
            Log.d(TAG, "🚀 Запрос запуска сервиса локации")
            try {
                val intent = Intent(context, LocationUpdateService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "✅ Команда запуска отправлена")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка запуска сервиса", e)
            }
        }

        fun stopService(context: Context) {
            Log.d(TAG, "🛑 Запрос остановки сервиса локации")
            try {
                val intent = Intent(context, LocationUpdateService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
                Log.d(TAG, "✅ Команда остановки отправлена")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка остановки сервиса", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "📍 onCreate сервиса")

        try {
            // Инициализация компонентов
            handler = Handler(Looper.getMainLooper())
            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

            // Создание канала уведомлений
            createNotificationChannel()

            // Инициализация WakeLock
            initWakeLock()

            // Создание callback'а
            createLocationCallback()

            Log.d(TAG, "✅ Сервис успешно создан")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Критическая ошибка при создании сервиса", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🎯 onStartCommand: ${intent?.action}, flags: $flags, startId: $startId")

        if (intent == null) {
            // Перезапуск сервиса системой
            Log.w(TAG, "⚠️ Сервис перезапущен системой")
            if (!isRunning.get()) {
                startForegroundService()
            }
            return START_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                if (!isRunning.get()) {
                    Log.d(TAG, "🔄 Запуск нового экземпляра сервиса")
                    startForegroundService()
                } else {
                    Log.d(TAG, "✅ Сервис уже запущен, обновляем настройки")
                    serviceScope.launch {
                        restartLocationUpdates()
                    }
                }
            }

            ACTION_STOP -> {
                Log.d(TAG, "🛑 Команда остановки сервиса")
                stopLocationTracking()
                stopSelf()
            }

            ACTION_PAUSE -> {
                Log.d(TAG, "⏸️ Команда паузы трекинга")
                pauseLocationTracking()
            }

            ACTION_RESUME -> {
                Log.d(TAG, "▶️ Команда возобновления трекинга")
                serviceScope.launch {
                    setupLocationUpdates()
                }
            }

            else -> {
                Log.w(TAG, "⚠️ Неизвестная команда: ${intent.action}")
                // По умолчанию запускаем сервис
                if (!isRunning.get()) {
                    startForegroundService()
                }
            }
        }

        return START_STICKY
    }

    private fun initWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LocationTracker::WakeLock"
            ).apply {
                setReferenceCounted(false)
            }
            Log.d(TAG, "🔋 WakeLock инициализирован")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка инициализации WakeLock", e)
            // Не критично, продолжаем без WakeLock
        }
    }

    private fun acquireWakeLock() {
        try {
            if (::wakeLock.isInitialized && !wakeLock.isHeld) {
                wakeLock.acquire(10 * 60 * 1000L) // 10 минут
                Log.d(TAG, "🔋 WakeLock получен")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка получения WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (::wakeLock.isInitialized && wakeLock.isHeld) {
                wakeLock.release()
                Log.d(TAG, "🔋 WakeLock освобожден")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка освобождения WakeLock", e)
        }
    }

    private fun startForegroundService() {
        Log.d(TAG, "🚀 Запуск foreground сервиса")

        if (isRunning.get()) {
            Log.w(TAG, "⚠️ Сервис уже запущен")
            return
        }

        try {
            isRunning.set(true)
            locationUpdatesAttempts = 0

            // Сразу показываем уведомление
            val notification = buildNotification("Инициализация...")
            startForeground(NOTIFICATION_ID, notification)

            // Запускаем все задачи
            serviceScope.launch {
                try {
                    // 1. Обновляем статус трекинга
                    updateTrackingStatus(true)

                    // 2. Запрашиваем единичную локацию для быстрого старта
                    requestSingleLocation()

                    // 3. Настраиваем постоянные обновления
                    setupLocationUpdates()

                    Log.d(TAG, "✅ Foreground сервис успешно запущен")
                    updateNotification("Поиск локации...")

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Ошибка при запуске сервиса", e)
                    updateNotification("Ошибка запуска: ${e.message}")
                    // Пытаемся восстановиться через 10 секунд
                    handler.postDelayed({
                        if (isRunning.get()) {
                            serviceScope.launch {
                                setupLocationUpdates()
                            }
                        }
                    }, 10000)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Критическая ошибка запуска сервиса", e)
            stopLocationTracking()
            stopSelf()
        }
    }

    private fun buildNotification(text: String): Notification {
        // Intent для открытия приложения
        val packageName = packageName
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent для остановки сервиса
        val stopIntent = Intent(this, LocationUpdateService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("📍 Отслеживание маршрута")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_location)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_stop,
                "Остановить",
                stopPendingIntent
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true) // Не вибрировать при каждом обновлении
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates() {
        Log.d(TAG, "🎯 Настройка обновлений локации, попытка #${locationUpdatesAttempts + 1}")

        if (!isRunning.get()) {
            Log.w(TAG, "⚠️ Сервис не запущен, пропускаем настройку")
            return
        }

        // Проверяем разрешения
        if (!hasLocationPermissions()) {
            Log.e(TAG, "❌ НЕТ РАЗРЕШЕНИЙ НА ЛОКАЦИЮ!")
            updateNotification("Нет разрешений на локацию")
            showPermissionNotification()

            // Пытаемся снова через 30 секунд
            scheduleRetry()
            return
        }

        // Проверяем включена ли локация
        if (!isLocationEnabled()) {
            Log.e(TAG, "❌ Локация отключена в системе")
            updateNotification("Локация отключена")
            showLocationDisabledNotification()

            // Пытаемся снова через 30 секунд
            scheduleRetry()
            return
        }

        Log.d(TAG, "✅ Разрешения есть, локация включена")

        try {
            // Удаляем старые обновления если есть
            removeLocationUpdates()

            // Определяем приоритет в зависимости от состояния
            val priority = if (isAppInForeground()) {
                Priority.PRIORITY_HIGH_ACCURACY
            } else {
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }

            // Определяем интервал в зависимости от состояния
            val interval = if (isAppInForeground()) {
                LOCATION_UPDATE_INTERVAL
            } else {
                BACKGROUND_UPDATE_INTERVAL
            }

            val locationRequest = LocationRequest.Builder(priority, interval)
                .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL)
                .setMinUpdateDistanceMeters(MIN_DISTANCE)
                .setWaitForAccurateLocation(true)
                .setMaxUpdateDelayMillis(10000) // Максимальная задержка 10 секунд
                .setDurationMillis(HIGH_ACCURACY_TIMEOUT) // Таймаут высокой точности
                .setMaxUpdates(Int.MAX_VALUE) // Без ограничения количества обновлений
                .build()

            Log.d(TAG, "📍 Запрос обновлений: приоритет=$priority, интервал=${interval}мс")

            // Запускаем обновления локации
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            ).addOnSuccessListener {
                Log.d(TAG, "✅ Обновления локации успешно запущены")
                isLocationUpdatesActive.set(true)
                locationUpdatesAttempts = 0 // Сбрасываем счетчик попыток
                updateNotification("Отслеживание активно")

                // При успешном запуске получаем WakeLock
                acquireWakeLock()

            }.addOnFailureListener { e ->
                Log.e(TAG, "❌ Ошибка запуска обновлений локации", e)
                updateNotification("Ошибка подключения")
                handleLocationError(e)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Критическая ошибка настройки локации", e)
            updateNotification("Ошибка настройки")
            handleLocationError(e)
        }
    }

    private fun isAppInForeground(): Boolean {
        // Простая проверка - считаем что сервис в фоне
        // В реальном приложении нужно использовать ActivityManager
        return false
    }

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (!isRunning.get()) {
                    Log.w(TAG, "⚠️ Сервис не запущен, игнорируем локацию")
                    return
                }

                val location = locationResult.lastLocation
                if (location != null) {
                    Log.d(TAG, "📍 Получена локация: " +
                            "lat=${"%.6f".format(location.latitude)}, " +
                            "lng=${"%.6f".format(location.longitude)}, " +
                            "accuracy=${location.accuracy}m, " +
                            "provider=${location.provider}")

                    if (isValidLocation(location)) {
                        Log.d(TAG, "✅ Локация прошла валидацию")

                        // Обновляем уведомление с координатами
                        updateNotification(
                            "Ш: ${"%.4f".format(location.latitude)}, " +
                                    "Д: ${"%.4f".format(location.longitude)}"
                        )

                        // Обрабатываем локацию
                        serviceScope.launch {
                            processNewLocation(location)
                        }

                    } else {
                        Log.w(TAG, "⚠️ Локация не прошла валидацию")

                        // Если есть последняя валидная локация, используем её
                        lastValidLocation?.let { validLoc ->
                            Log.d(TAG, "🔄 Используем последнюю валидную локацию")
                            serviceScope.launch {
                                processNewLocation(validLoc)
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "⚠️ Локация null в callback")
                    updateNotification("Локация не получена")
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.d(TAG, "📡 Доступность локации: ${availability.isLocationAvailable}")

                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "⚠️ Локация временно недоступна")
                    updateNotification("Поиск сигнала...")

                    // Пытаемся восстановить соединение через 5 секунд
                    handler.postDelayed({
                        if (isRunning.get() && isLocationUpdatesActive.get()) {
                            Log.d(TAG, "🔄 Попытка восстановления локации...")
                            serviceScope.launch {
                                requestSingleLocation() // Быстрый запрос
                            }
                        }
                    }, 5000)

                } else {
                    Log.d(TAG, "✅ Локация снова доступна")
                    updateNotification("Сигнал восстановлен")
                }
            }
        }
    }



    private fun isValidLocation(location: Location): Boolean {
        val currentTime = System.currentTimeMillis()

        // 1. Проверка на нулевые координаты
        if (location.latitude == 0.0 || location.longitude == 0.0) {
            Log.w(TAG, "⚠️ Нулевые координаты")
            return false
        }

        // 2. Проверка на валидные диапазоны
        if (abs(location.latitude) > 90 || abs(location.longitude) > 180) {
            Log.w(TAG, "⚠️ Координаты вне диапазона")
            return false
        }

        // 3. Проверка времени (не из будущего и не старше 2 минут)
        if (location.time > currentTime + 60000 || // Не из будущего (> 1 мин)
            location.time < currentTime - 120000) { // Не старше 2 минут
            Log.w(TAG, "⚠️ Неактуальное время: ${currentTime - location.time}мс разницы")
            return false
        }

        // 4. Проверка точности
        if (!location.hasAccuracy()) {
            Log.w(TAG, "⚠️ Нет данных о точности")
            return false
        }

        val accuracy = location.accuracy
        if (accuracy > MAX_ACCURACY) {
            Log.w(TAG, "⚠️ Низкая точность: ${accuracy}м")
            return false
        }

        // Для GPS проверяем минимальную точность
        if (location.provider == LocationManager.GPS_PROVIDER && accuracy < MIN_ACCURACY) {
            Log.w(TAG, "⚠️ Слишком высокая точность для GPS: ${accuracy}м (возможно шум)")
            return false
        }

        // 5. Проверка скорости
        if (location.hasSpeed()) {
            val speedKmh = location.speed * 3.6f
            if (speedKmh > 200) { // 200 км/ч - разумный максимум
                Log.w(TAG, "⚠️ Нереальная скорость: ${"%.1f".format(speedKmh)} км/ч")
                return false
            }
        }

        // 6. Проверка на "прыжки" (Sudden jumps)
        lastLocation?.let { previous ->
            val distance = calculateHaversineDistance(
                previous.latitude, previous.longitude,
                location.latitude, location.longitude
            )
            val timeDiff = (location.time - previous.time) / 1000.0 // секунды

            if (timeDiff > 0) {
                val speedMs = distance / timeDiff
                val speedKmh = speedMs * 3.6

                // Максимальная скорость между точками - 250 км/ч (69.44 м/с)
                if (speedKmh > 250) {
                    Log.w(TAG, "⚠️ Слишком быстрое перемещение: ${"%.1f".format(speedKmh)} км/ч")
                    return false
                }

                // Для пешеходов/велосипедов - дополнительные проверки
                if (distance > 1000 && speedKmh < 5) {
                    // Переместился на 1+ км со скоростью пешехода - возможно GPS дрейф
                    Log.w(TAG, "⚠️ Возможный GPS дрейф: ${"%.0f".format(distance)}м за ${"%.0f".format(timeDiff)}с")
                    return false
                }
            }
        }

        Log.d(TAG, "✅ Локация валидна: accuracy=${"%.1f".format(accuracy)}м")
        lastValidLocation = location
        return true
    }

    private suspend fun processNewLocation(location: Location) {
        try {
            // Сохраняем последнюю локацию
            lastLocation = location

            // Сохраняем в Firebase
            saveLocationToFirebase(location)

            Log.d(TAG, "✅ Локация обработана и сохранена")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка обработки локации", e)
        }
    }

    private suspend fun saveLocationToFirebase(location: Location) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.e(TAG, "❌ Пользователь не авторизован")
            return
        }

        try {
            // Создаем объект локации (БЕЗ лишних полей для совместимости с MapFragment)
            val userLocation = UserLocation(
                lat = location.latitude,
                lng = location.longitude,
                timestamp = System.currentTimeMillis()
            )

            // Конвертируем в Map для Firebase
            val locationData = hashMapOf<String, Any>(
                "lat" to userLocation.lat,
                "lng" to userLocation.lng,
                "timestamp" to userLocation.timestamp
                // НЕ добавляем accuracy и color - это ломает MapFragment!
            )

            // 1. Сохраняем в историю (если нужно)
            val historyKey = database.child("route_history").child(userId).push().key
            if (historyKey != null) {
                database.child("route_history")
                    .child(userId)
                    .child(historyKey)
                    .setValue(locationData)
                    .await()
                Log.d(TAG, "✅ Локация сохранена в историю")
            }

            // 2. Обновляем последнюю локацию (самое важное для MapFragment)
            database.child("last_locations")
                .child(userId)
                .setValue(locationData)
                .await()

            Log.d(TAG, "✅ Последняя локация обновлена в Firebase")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка сохранения локации в Firebase", e)
            // Не бросаем исключение дальше, продолжаем работу
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleLocation() {
        if (!hasLocationPermissions()) {
            Log.e(TAG, "❌ Нет разрешений для единичного запроса")
            return
        }

        Log.d(TAG, "🎯 Запрос единичной локации...")

        try {
            val location = withTimeoutOrNull(10000) { // Таймаут 10 секунд
                suspendCoroutine<Location?> { continuation ->
                    val tempCallback = object : LocationCallback() {
                        override fun onLocationResult(locationResult: LocationResult) {
                            val loc = locationResult.lastLocation
                            Log.d(TAG, "📍 Единичная локация получена: ${loc?.latitude}, ${loc?.longitude}")
                            continuation.resume(loc)
                            fusedLocationClient.removeLocationUpdates(this)
                        }
                    }

                    val immediateRequest = LocationRequest.Builder(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        5000
                    ).setMaxUpdates(1).build()

                    fusedLocationClient.requestLocationUpdates(
                        immediateRequest,
                        tempCallback,
                        Looper.getMainLooper()
                    ).addOnSuccessListener {
                        Log.d(TAG, "✅ Запрос единичной локации отправлен")
                    }.addOnFailureListener { e ->
                        Log.e(TAG, "❌ Ошибка запроса единичной локации", e)
                        continuation.resume(null)
                        fusedLocationClient.removeLocationUpdates(tempCallback)
                    }

                    // Таймаут на случай если локация не придет
                    handler.postDelayed({
                        if (continuation.context.isActive) {
                            Log.w(TAG, "⚠️ Таймаут единичной локации")
                            continuation.resume(null)
                            fusedLocationClient.removeLocationUpdates(tempCallback)
                        }
                    }, 10000)
                }
            }

            location?.let {
                if (isValidLocation(it)) {
                    processNewLocation(it)
                }
            } ?: run {
                Log.w(TAG, "⚠️ Единичная локация не получена")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка запроса единичной локации", e)
        }
    }

    private fun restartLocationUpdates() {
        Log.d(TAG, "🔄 Перезапуск обновлений локации")
        serviceScope.launch {
            removeLocationUpdates()
            delay(1000) // Небольшая пауза
            setupLocationUpdates()
        }
    }

    private fun pauseLocationTracking() {
        Log.d(TAG, "⏸️ Пауза трекинга")
        removeLocationUpdates()
        updateNotification("Трекинг на паузе")
        isLocationUpdatesActive.set(false)
    }

    private fun stopLocationTracking() {
        Log.d(TAG, "🛑 Полная остановка трекинга")

        isRunning.set(false)
        isLocationUpdatesActive.set(false)

        // 1. Удаляем обновления локации
        removeLocationUpdates()

        // 2. Обновляем статус в Firebase
        serviceScope.launch {
            updateTrackingStatus(false)
        }

        // 3. Освобождаем WakeLock
        releaseWakeLock()

        // 4. Останавливаем корутины
        serviceScope.coroutineContext.cancelChildren()

        // 5. Снимаем с foreground (если нужно)
        stopForeground(true)

        Log.d(TAG, "✅ Трекинг полностью остановлен")
    }

    private fun removeLocationUpdates() {
        try {
            if (this::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.d(TAG, "✅ Location updates removed")
            }
        } catch (e: Exception) {
            Log.d(TAG, "⚠️ Location updates already removed or error", e)
        }
    }

    private suspend fun updateTrackingStatus(isTracking: Boolean) {
        val userId = auth.currentUser?.uid ?: return
        try {
            database.child("tracking_status").child(userId).setValue(isTracking).await()
            Log.d(TAG, "✅ Статус трекинга обновлен: $isTracking")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка обновления статуса трекинга", e)
        }
    }

    private fun scheduleRetry() {
        locationUpdatesAttempts++

        if (locationUpdatesAttempts >= MAX_RETRY_ATTEMPTS) {
            Log.e(TAG, "❌ Достигнут максимум попыток ($MAX_RETRY_ATTEMPTS)")
            updateNotification("Ошибка: проверьте настройки")
            return
        }

        val delay = locationUpdatesAttempts * 30000L // 30, 60, 90 секунд
        Log.d(TAG, "🔄 Повтор через ${delay/1000} секунд (попытка $locationUpdatesAttempts)")

        handler.postDelayed({
            if (isRunning.get()) {
                serviceScope.launch {
                    setupLocationUpdates()
                }
            }
        }, delay)
    }

    private fun handleLocationError(error: Exception?) {
        Log.w(TAG, "🔄 Обработка ошибки локации: ${error?.message}")
        updateNotification("Ошибка подключения")

        // Планируем повторную попытку
        scheduleRetry()
    }

    private fun hasLocationPermissions(): Boolean {
        return try {
            val hasFineLocation = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val hasCoarseLocation = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            Log.d(TAG, "🔐 Проверка разрешений: FINE=$hasFineLocation, COARSE=$hasCoarseLocation")

            hasFineLocation || hasCoarseLocation
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка проверки разрешений", e)
            false
        }
    }

    private fun isLocationEnabled(): Boolean {
        return try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            Log.d(TAG, "📡 Проверка провайдеров: GPS=$gpsEnabled, NETWORK=$networkEnabled")

            gpsEnabled || networkEnabled
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка проверки локации", e)
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // ❌ УБРАТЬ ЭТУ СТРОКУ:
                // notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_ID)

                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Канал для сервиса отслеживания перемещений"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(false)
                    enableLights(false)
                }

                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "✅ Канал уведомлений создан")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка создания канала уведомлений", e)
            }
        }
    }

    private fun updateNotification(text: String) {
        try {
            val notification = buildNotification(text)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка обновления уведомления", e)
        }
    }

    private fun showPermissionNotification() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                2,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("📍 Требуется разрешение")
                .setContentText("Дайте разрешение на доступ к локации")
                .setSmallIcon(R.drawable.ic_location)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(2, notification)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка показа уведомления о разрешениях", e)
        }
    }

    private fun showLocationDisabledNotification() {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            val pendingIntent = PendingIntent.getActivity(
                this,
                3,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("📍 Включите геолокацию")
                .setContentText("Для работы трекера включите GPS")
                .setSmallIcon(R.drawable.ic_location)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(3, notification)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка показа уведомления о локации", e)
        }
    }

    private fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // метры
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadius * c
    }

    override fun onDestroy() {
        Log.d(TAG, "🗑️ onDestroy сервиса")
        try {
            stopLocationTracking()
            handler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка в onDestroy", e)
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "📱 Задача удалена из списка недавних")
        // Сервис будет перезапущен системой благодаря START_STICKY
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}