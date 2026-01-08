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
import kotlin.math.sqrt

class LocationUpdateService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var handler: Handler
    private lateinit var wakeLock: PowerManager.WakeLock

    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()

    private val isRunning = AtomicBoolean(false)
    private val isLocationUpdatesActive = AtomicBoolean(false)

    private var lastLocation: Location? = null
    private var currentColorIndex = 0
    private val routeColors = listOf(Color.BLUE, Color.RED, Color.GREEN, Color.MAGENTA, Color.CYAN)

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val TAG = "LocationUpdateService"
        const val NOTIFICATION_CHANNEL_ID = "location_tracker_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "START_LOCATION_TRACKING"
        const val ACTION_STOP = "STOP_LOCATION_TRACKING"

        // Более консервативные настройки для фильтрации GPS "прыжков"
        private const val LOCATION_UPDATE_INTERVAL = 15000L // 15 секунд
        private const val FASTEST_UPDATE_INTERVAL = 10000L   // 10 секунд
        private const val MIN_DISTANCE = 15f               // 15 метров
        private const val MAX_ACCURACY = 50f               // Максимальная погрешность в метрах
        private const val MAX_SPEED = 27.78f               // 100 км/ч в м/с

        fun startService(context: Context) {
            Log.d(TAG, "🚀 Запуск сервиса локации")
            val intent = Intent(context, LocationUpdateService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            Log.d(TAG, "🛑 Остановка сервиса локации")
            val intent = Intent(context, LocationUpdateService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "📍 Создание сервиса")
        checkBatteryOptimization()
        acquireWakeLock()
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createLocationCallback()
        Log.d(TAG, "✅ Сервис создан")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🎯 Команда: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning.get()) {
                    Log.d(TAG, "🔄 Запуск нового сервиса")
                    startForegroundService()
                } else {
                    Log.d(TAG, "✅ Сервис уже запущен, обновляем настройки")
                    // Перезапускаем обновления локации
                    serviceScope.launch {
                        setupLocationUpdates()
                    }
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "🛑 Команда остановки сервиса")
                stopLocationTracking()
                stopSelf()
            }
            else -> {
                Log.w(TAG, "⚠️ Неизвестная команда: ${intent?.action}")
            }
        }

        return START_STICKY
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoringOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName)

            Log.d(TAG, "🔋 Battery optimization ignored: $isIgnoringOptimizations")

            if (!isIgnoringOptimizations) {
                Log.w(TAG, "⚠️ BATTERY OPTIMIZATION IS ENABLED - LOCATION MAY BE INTERRUPTED!")
                showBatteryOptimizationNotification()
            } else {
                Log.d(TAG, "✅ Battery optimization is disabled - good!")
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "LocationTracker::WakeLock"
            )
            wakeLock.setReferenceCounted(true)
            wakeLock.acquire(10 * 60 * 1000L)
            Log.d(TAG, "🔋 WakeLock получен")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка получения WakeLock", e)
        }
    }

    private fun startForegroundService() {
        Log.d(TAG, "🚀 Запуск foreground сервиса")
        try {
            isRunning.set(true)

            // Сразу обновляем статус трекинга
            serviceScope.launch {
                updateTrackingStatus(true)
            }

            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)

            // Запускаем все задачи
            serviceScope.launch {
                Log.d(TAG, "🎯 Начинаем настройку сервиса...")
                setupLocationUpdates()
                requestSingleLocation() // Немедленный запрос локации
            }

            Log.d(TAG, "✅ Foreground сервис запущен")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка запуска сервиса", e)
            stopLocationTracking()
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("📍 Отслеживание маршрута")
            .setContentText("Активно - поиск локации...")
            .setSmallIcon(R.drawable.ic_location)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates() {
        Log.d(TAG, "🎯 Настройка обновлений локации")

        if (!hasLocationPermissions()) {
            Log.e(TAG, "❌ НЕТ РАЗРЕШЕНИЙ НА ЛОКАЦИЮ!")
            showPermissionNotification()
            updateNotification("Нет разрешений на локацию")
            return
        }

        if (!isLocationEnabled()) {
            Log.e(TAG, "❌ Локация отключена в системе")
            showLocationDisabledNotification()
            updateNotification("Локация отключена")
            return
        }

        Log.d(TAG, "✅ Разрешения есть, локация включена")

        try {
            // Удаляем старый callback
            removeLocationUpdates()

            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                LOCATION_UPDATE_INTERVAL
            )
                .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL)
                .setMinUpdateDistanceMeters(MIN_DISTANCE)
                .setWaitForAccurateLocation(true)
                .setMaxUpdateDelayMillis(5000) // Максимальная задержка
                .build()

            Log.d(TAG, "📍 Запрос обновлений локации: интервал $LOCATION_UPDATE_INTERVAL мс")

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            ).addOnSuccessListener {
                Log.d(TAG, "✅ Обновления локации ЗАПУЩЕНЫ")
                isLocationUpdatesActive.set(true)
                updateNotification("Отслеживание активно")
            }.addOnFailureListener { e ->
                Log.e(TAG, "❌ Ошибка запуска обновлений локации", e)
                updateNotification("Ошибка локации")
                handleLocationError()
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Критическая ошибка настройки локации", e)
            updateNotification("Ошибка настройки")
            handleLocationError()
        }
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
                    Log.d(TAG, "📍 Получена новая локация: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}")

                    if (isValidLocation(location)) {
                        Log.d(TAG, "✅ Локация прошла валидацию")
                        updateNotification("Локация: ${"%.6f".format(location.latitude)}, ${"%.6f".format(location.longitude)}")
                        serviceScope.launch {
                            processNewLocation(location)
                        }
                    } else {
                        Log.w(TAG, "⚠️ Локация не прошла валидацию")
                        updateNotification("Невалидная локация")
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
                    updateNotification("Локация недоступна")

                    // Пытаемся восстановить соединение
                    handler.postDelayed({
                        if (isRunning.get() && !availability.isLocationAvailable) {
                            Log.d(TAG, "🔄 Попытка восстановления локации...")
                            serviceScope.launch {
                                setupLocationUpdates()
                            }
                        }
                    }, 2000)
                } else {
                    Log.d(TAG, "✅ Локация снова доступна")
                    updateNotification("Поиск локации...")
                }
            }
        }
    }

    private fun isValidLocation(location: Location): Boolean {
        val currentTime = System.currentTimeMillis()

        // Базовые проверки
        if (location.latitude == 0.0 || location.longitude == 0.0 ||
            abs(location.latitude) > 90 || abs(location.longitude) > 180 ||
            location.time > currentTime + 60000 || // Не из будущего
            location.time < currentTime - 300000) { // Не старше 5 минут
            Log.w(TAG, "⚠️ Локация невалидна: базовые проверки")
            return false
        }

        // Проверка точности
        if (location.accuracy > MAX_ACCURACY) {
            Log.w(TAG, "⚠️ Локация невалидна: низкая точность ${location.accuracy}")
            return false
        }

        // Проверка скорости
        if (location.hasSpeed() && location.speed > MAX_SPEED) {
            Log.w(TAG, "⚠️ Локация невалидна: превышение скорости ${location.speed * 3.6} км/ч")
            return false
        }

        // Проверка расстояния от предыдущей точки
        lastLocation?.let { previous ->
            val distance = calculateDistance(
                previous.latitude, previous.longitude,
                location.latitude, location.longitude
            )
            val timeDiff = (location.time - previous.time) / 1000.0 // секунды

            if (timeDiff > 0) {
                val speed = distance / timeDiff // м/с

                // Максимальная скорость 100 км/ч (27.78 м/с)
                if (speed > MAX_SPEED) {
                    Log.w(TAG, "⚠️ Локация невалидна: скорость между точками ${String.format("%.1f", speed * 3.6)} км/ч")
                    return false
                }

                // Максимальное расстояние за интервал (100 км/ч * 15 сек = 417 метров)
                val maxPossibleDistance = MAX_SPEED * timeDiff
                if (distance > maxPossibleDistance) {
                    Log.w(TAG, "⚠️ Локация невалидна: расстояние $distance м превышает возможное $maxPossibleDistance м")
                    return false
                }
            }
        }

        Log.d(TAG, "✅ Локация прошла валидацию: accuracy=${location.accuracy}")
        return true
    }

    private fun isRealisticMovement(newLocation: Location): Boolean {
        lastLocation?.let { previous ->
            val distance = calculateDistance(
                previous.latitude, previous.longitude,
                newLocation.latitude, newLocation.longitude
            )
            val timeDiff = (newLocation.time - previous.time) / 1000.0

            if (timeDiff <= 0) return true

            val speed = distance / timeDiff

            // Для велосипеда и пеших прогулок - более строгие ограничения
            val maxSpeed = when {
                distance < 50 -> 10.0  // 36 км/ч для коротких дистанций
                distance < 200 -> 15.0 // 54 км/ч для средних дистанций
                else -> MAX_SPEED.toDouble() // Конвертируем Float в Double
            }

            if (speed > maxSpeed) {
                Log.w(TAG, "🚫 Нереалистичное движение: ${String.format("%.1f", speed * 3.6)} км/ч")
                return false
            }
        }

        return true
    }

    private suspend fun processNewLocation(location: Location) {
        try {
            // Дополнительная проверка на реалистичность
            if (!isRealisticMovement(location)) {
                Log.w(TAG, "📍 Локация отфильтрована как нереалистичная")
                return
            }

            // Обновляем цвет при значительном перемещении (увеличиваем порог)
            lastLocation?.let { oldLocation ->
                val distance = calculateDistance(
                    oldLocation.latitude, oldLocation.longitude,
                    location.latitude, location.longitude
                )
                if (distance > 200) { // Более 200 метров - меняем цвет (было 100)
                    currentColorIndex = (currentColorIndex + 1) % routeColors.size
                    Log.d(TAG, "🎨 Смена цвета маршрута: ${routeColors[currentColorIndex]}")
                }
            }

            // Сохраняем в Firebase
            saveLocationToFirebase(location)

            lastLocation = location
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

        // Используем явное объявление типа Map с указанием типов
        val locationData = hashMapOf<String, Any>(
            "lat" to location.latitude,
            "lng" to location.longitude,
            "timestamp" to System.currentTimeMillis(),
            "accuracy" to location.accuracy,
            "color" to routeColors[currentColorIndex]
        )

        try {
            // Сохраняем в историю маршрута (никогда не очищается автоматически)
            val historyKey = database.child("route_history").child(userId).push().key
            if (historyKey != null) {
                database.child("route_history")
                    .child(userId)
                    .child(historyKey)
                    .setValue(locationData)
                    .await()
                Log.d(TAG, "✅ Локация сохранена в историю с ключом $historyKey")
            }

            // Также сохраняем для текущей сессии (можно очищать)
            val sessionKey = database.child("user_locations").child(userId).push().key
            if (sessionKey != null) {
                database.child("user_locations")
                    .child(userId)
                    .child(sessionKey)
                    .setValue(locationData)
                    .await()
                Log.d(TAG, "✅ Локация сохранена в сессию с ключом $sessionKey")
            }

            // Обновляем последнюю локацию для быстрого доступа
            database.child("last_locations")
                .child(userId)
                .setValue(locationData)
                .await()
            Log.d(TAG, "✅ Последняя локация обновлена")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка сохранения локации в Firebase", e)
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
            val location = withTimeoutOrNull(15000) {
                suspendCoroutine<Location?> { continuation ->
                    Log.d(TAG, "⏳ Ожидание единичной локации...")

                    val tempCallback = object : LocationCallback() {
                        override fun onLocationResult(locationResult: LocationResult) {
                            val loc = locationResult.lastLocation
                            Log.d(TAG, "📍 Единичная локация получена в callback: ${loc?.latitude}, ${loc?.longitude}")
                            continuation.resume(loc)
                            fusedLocationClient.removeLocationUpdates(this)
                        }

                        override fun onLocationAvailability(availability: LocationAvailability) {
                            Log.d(TAG, "📡 Доступность единичной локации: ${availability.isLocationAvailable}")
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
                    }

                    // Таймаут
                    handler.postDelayed({
                        try {
                            if (continuation.context.isActive) {
                                Log.w(TAG, "⚠️ Таймаут единичной локации")
                                continuation.resume(null)
                                fusedLocationClient.removeLocationUpdates(tempCallback)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Ошибка в таймауте", e)
                        }
                    }, 15000)
                }
            }

            location?.let {
                Log.d(TAG, "📍 Единичная локация получена: ${it.latitude}, ${it.longitude}")
                processNewLocation(it)
            } ?: run {
                Log.w(TAG, "⚠️ Единичная локация не получена (таймаут или ошибка)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка запроса единичной локации", e)
        }
    }

    private fun stopLocationTracking() {
        Log.d(TAG, "🛑 Остановка трекинга")

        isRunning.set(false)
        isLocationUpdatesActive.set(false)

        removeLocationUpdates()

        serviceScope.launch {
            updateTrackingStatus(false)
        }

        releaseWakeLock()
        serviceScope.cancel("Service stopped")

        Log.d(TAG, "✅ Трекинг остановлен")
    }

    private fun removeLocationUpdates() {
        try {
            // Исправленная проверка инициализации lateinit переменной
            if (this::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.d(TAG, "✅ Location updates removed")
            } else {
                Log.d(TAG, "⚠️ Location callback not initialized yet")
            }
        } catch (e: Exception) {
            Log.d(TAG, "⚠️ Location updates already removed", e)
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

    private fun releaseWakeLock() {
        try {
            // Проверка инициализации wakeLock
            if (this::wakeLock.isInitialized && wakeLock.isHeld) {
                wakeLock.release()
                Log.d(TAG, "🔋 WakeLock освобожден")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка освобождения WakeLock", e)
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        Log.d(TAG, "🔐 Проверка разрешений: FINE=$hasFineLocation, COARSE=$hasCoarseLocation")

        return hasFineLocation || hasCoarseLocation
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        Log.d(TAG, "📡 Проверка провайдеров локации: GPS=$gpsEnabled, NETWORK=$networkEnabled")

        return gpsEnabled || networkEnabled
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Отслеживание маршрута",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Канал для сервиса отслеживания перемещений"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Log.d(TAG, "✅ Канал уведомлений создан")
        }
    }

    private fun updateNotification(text: String) {
        try {
            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("📍 Отслеживание маршрута")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_location)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка обновления уведомления", e)
        }
    }

    private fun showBatteryOptimizationNotification() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("🔋 Оптимизация батареи")
            .setContentText("Отключите оптимизацию для стабильной работы трекера")
            .setSmallIcon(R.drawable.ic_location)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(4, notification)
    }

    private fun showPermissionNotification() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("📍 Требуется разрешение")
            .setContentText("Дайте разрешение на доступ к локации")
            .setSmallIcon(R.drawable.ic_location)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2, notification)
    }

    private fun showLocationDisabledNotification() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("📍 Включите геолокацию")
            .setContentText("Для работы трекера включите GPS")
            .setSmallIcon(R.drawable.ic_location)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(3, notification)
    }

    private fun handleLocationError() {
        Log.w(TAG, "🔄 Повторная попытка через 10 секунд")
        handler.postDelayed({
            if (isRunning.get()) {
                Log.d(TAG, "🔄 Перезапуск обновлений локации...")
                serviceScope.launch {
                    setupLocationUpdates()
                }
            }
        }, 10000)
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    override fun onDestroy() {
        Log.d(TAG, "🗑️ Уничтожение сервиса")
        stopLocationTracking()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}