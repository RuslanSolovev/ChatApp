package com.example.chatapp.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.chatapp.R
import com.example.chatapp.models.UserLocation
import com.example.chatapp.step.StepCounterApp
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.yandex.mapkit.geometry.Point
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.*

class LocationUpdateService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var handler: Handler

    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()

    // Атомарные флаги для управления состоянием
    private val isRunning = AtomicBoolean(false)
    private val isServiceStarting = AtomicBoolean(false)
    private val isLocationUpdatesActive = AtomicBoolean(false)

    // Данные локации
    private var lastLocation: Location? = null
    private var lastKnownAccuracy: Float = 50f
    private var retryCount = 0

    // Для отслеживания поворотов
    private var currentColorIndex = 0
    private val routePoints = CopyOnWriteArrayList<Point>()
    private val previousBearings = CopyOnWriteArrayList<Double>()

    // Корутин скоуп для фоновых задач сервиса
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val TAG = "LocationUpdateService"
        const val NOTIFICATION_CHANNEL_ID = "location_service_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.example.chatapp.action.START_LOCATION_SERVICE"
        const val ACTION_STOP = "com.example.chatapp.action.STOP_LOCATION_SERVICE"

        // ОПТИМИЗИРОВАННЫЕ настройки локации для ПОСТОЯННОГО отслеживания
        private const val LOCATION_UPDATE_INTERVAL = 30000L // 30 секунд
        private const val FASTEST_UPDATE_INTERVAL = 15000L // 15 секунд
        private const val MAX_UPDATE_DELAY = 45000L // 45 секунд
        private const val MIN_ACCURACY = 150f
        private const val MIN_DISTANCE = 30f
        private const val MAX_SPEED = 80.0

        // ОПТИМИЗИРОВАННЫЕ настройки повторных попыток
        private const val MAX_RETRY_COUNT = 2
        private const val RETRY_DELAY_BASE = 10000L

        // Настройки поворотов
        private const val TURN_ANGLE_THRESHOLD = 110.0
        private const val MAX_ROUTE_POINTS = 30

        private val ROUTE_COLORS = listOf(
            Color.BLUE,
            Color.RED,
            Color.GREEN,
            Color.MAGENTA,
            Color.CYAN
        )

        // Флаг для отслеживания запущенного сервиса
        @Volatile
        private var isServiceRunningGlobally = false

        @Synchronized
        fun setServiceRunning(running: Boolean) {
            isServiceRunningGlobally = running
        }

        @Synchronized
        fun isServiceRunning(): Boolean {
            return isServiceRunningGlobally
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Быстрая инициализация сервиса...")

        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // ИНИЦИАЛИЗИРУЕМ КОЛБЭК СРАЗУ - ЭТО ВАЖНО!
        createLocationCallbackFast()

        Log.d(TAG, "onCreate: Сервис инициализирован")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Вызван с action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                if (isServiceStarting.getAndSet(true)) {
                    Log.w(TAG, "onStartCommand: Сервис уже запускается, пропускаем")
                    return START_STICKY
                }

                if (isServiceRunning()) {
                    Log.w(TAG, "onStartCommand: Сервис уже запущен глобально, пропускаем")
                    isServiceStarting.set(false)
                    return START_STICKY
                }

                if (!isRunning.get()) {
                    Log.d(TAG, "onStartCommand: Быстрый запуск сервиса в foreground")
                    startForegroundServiceFast()
                } else {
                    Log.d(TAG, "onStartCommand: Сервис уже запущен локально")
                    isServiceStarting.set(false)
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "onStartCommand: Получена команда STOP")
                serviceScope.launch {
                    stopLocationTrackingFast()
                }
                stopSelf()
            }
            else -> {
                Log.d(TAG, "onStartCommand: Перезапуск системой, быстрая проверка статуса")
                // ПРИ ПЕРЕЗАПУСКЕ СИСТЕМОЙ ПЫТАЕМСЯ ЗАПУСТИТЬСЯ
                if (!isRunning.get() && !isServiceStarting.get()) {
                    Log.d(TAG, "onStartCommand: Перезапуск сервиса системой")
                    startForegroundServiceFast()
                }
            }
        }

        return START_STICKY
    }

    /**
     * ОПТИМИЗИРОВАННЫЙ запуск сервиса
     */
    private fun startForegroundServiceFast() {
        Log.d(TAG, "startForegroundServiceFast: Начало быстрого запуска")
        try {
            // УСТАНАВЛИВАЕМ ФЛАГ ПЕРВЫМ ДЕЛОМ!
            isRunning.set(true)

            // БЫСТРОЕ сохранение состояния
            saveServiceState(true)
            setServiceRunning(true)

            val notification = buildFastNotification()
            startForeground(NOTIFICATION_ID, notification)

            // ЗАПУСКАЕМ ВСЕ ПАРАЛЛЕЛЬНО
            serviceScope.launch {
                // Параллельный запуск обновлений локации и обновления статуса
                val jobs = listOf(
                    async { setupLocationUpdatesFast() },
                    async { updateTrackingStatus(true) }
                )
                jobs.awaitAll()

                // СБРАСЫВАЕМ ФЛАГ ЗАПУСКА ПОСЛЕ УСПЕШНОЙ ИНИЦИАЛИЗАЦИИ
                isServiceStarting.set(false)
            }

            Log.d(TAG, "startForegroundServiceFast: Сервис успешно запущен")

        } catch (e: Exception) {
            Log.e(TAG, "startForegroundServiceFast: Ошибка запуска сервиса", e)
            // ПРИ ОШИБКЕ СБРАСЫВАЕМ ФЛАГИ
            isRunning.set(false)
            isServiceStarting.set(false)
            saveServiceState(false)
            setServiceRunning(false)
            stopSelf()
        }
    }

    /**
     * ОПТИМИЗИРОВАННАЯ настройка получения обновлений локации
     * УБИРАЕМ ПРОВЕРКУ isRunning - она уже выполнена в вызывающем коде
     */
    @SuppressLint("MissingPermission")
    private fun setupLocationUpdatesFast() {
        Log.d(TAG, "setupLocationUpdatesFast: БЫСТРАЯ настройка")

        // УБИРАЕМ ПРОВЕРКУ isRunning - МЫ УЖЕ ЗДЕСЬ ЗНАЧИТ СЕРВИС ЗАПУЩЕН

        // ПАРАЛЛЕЛЬНЫЕ проверки вместо последовательных
        if (!hasForegroundLocationPermissions()) {
            Log.w(TAG, "setupLocationUpdatesFast: Нет разрешений. Останавливаем сервис.")
            handlePermissionErrorFast()
            return
        }

        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.w(TAG, "setupLocationUpdatesFast: Пользователь не авторизован. Останавливаем сервис.")
            handleAuthErrorFast()
            return
        }

        Log.d(TAG, "setupLocationUpdatesFast: Проверки пройдены - запускаем локацию")

        // ОПТИМИЗИРОВАННАЯ инициализация
        initializeLocationUpdatesFast(userId)
    }

    /**
     * БЫСТРАЯ инициализация обновлений локации
     */
    @SuppressLint("MissingPermission")
    private fun initializeLocationUpdatesFast(userId: String) {
        try {
            // 1. Удаляем старый callback БЫСТРО
            removePreviousLocationCallbackFast()

            // 2. Создаем ПОСТОЯННЫЙ location request БЕЗ ТАЙМАУТОВ
            val locationRequest = createContinuousLocationRequest()

            Log.d(TAG, "initializeLocationUpdatesFast: Запрашиваем ПОСТОЯННЫЕ обновления...")

            // 3. ЗАПУСКАЕМ ПОСТОЯННОЕ ОБНОВЛЕНИЕ
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            ).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "initializeLocationUpdatesFast: ПОСТОЯННЫЕ обновления запущены")
                    isLocationUpdatesActive.set(true)
                    retryCount = 0

                    // ЗАПУСКАЕМ В ФОНЕ чтобы не блокировать
                    serviceScope.launch {
                        requestSingleLocationUpdateFast()
                    }
                } else {
                    Log.e(TAG, "initializeLocationUpdatesFast: Ошибка запуска", task.exception)
                    handleLocationUpdatesErrorFast()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "initializeLocationUpdatesFast: Критическая ошибка", e)
            handleCriticalErrorFast()
        }
    }

    /**
     * ПОСТОЯННЫЙ location request для непрерывного отслеживания
     */
    private fun createContinuousLocationRequest(): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, LOCATION_UPDATE_INTERVAL)
            .setIntervalMillis(LOCATION_UPDATE_INTERVAL)
            .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL)
            .setMaxUpdateDelayMillis(MAX_UPDATE_DELAY)
            .setWaitForAccurateLocation(false)
            .setMinUpdateDistanceMeters(MIN_DISTANCE)
            .build()
    }

    /**
     * БЫСТРЫЙ запрос единичной локации
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestSingleLocationUpdateFast() {
        withContext(Dispatchers.IO) {
            try {
                if (!isRunning.get() || !hasForegroundLocationPermissions()) return@withContext

                val immediateRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                    .setMaxUpdates(1)
                    .setWaitForAccurateLocation(false)
                    .build()

                val location = withTimeoutOrNull(5000) {
                    suspendCoroutine<Location?> { continuation ->
                        val tempCallback = object : LocationCallback() {
                            override fun onLocationResult(locationResult: LocationResult) {
                                continuation.resume(locationResult.lastLocation)
                                fusedLocationClient.removeLocationUpdates(this)
                            }
                        }

                        fusedLocationClient.requestLocationUpdates(
                            immediateRequest,
                            tempCallback,
                            Looper.getMainLooper()
                        )

                        handler.postDelayed({
                            if (continuation.context.isActive) {
                                continuation.resume(null)
                                fusedLocationClient.removeLocationUpdates(tempCallback)
                            }
                        }, 5000)
                    }
                }

                location?.let {
                    processLocationFast(it)
                    Log.d(TAG, "requestSingleLocationUpdateFast: Получена быстрая локация")
                }

            } catch (e: Exception) {
                Log.e(TAG, "requestSingleLocationUpdateFast: Ошибка", e)
            }
        }
    }

    /**
     * Создание callback для обработки обновлений локации
     */
    private fun createLocationCallbackFast() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (!isRunning.get()) {
                    Log.d(TAG, "onLocationResult: Сервис остановлен, игнорируем локации")
                    return
                }

                val location = locationResult.lastLocation
                if (location != null) {
                    Log.d(TAG, "onLocationResult: Получена новая локация: ${location.latitude}, ${location.longitude}")

                    lastKnownAccuracy = location.accuracy
                    if (retryCount > 0) {
                        Log.d(TAG, "onLocationResult: Получена локация, сбрасываем retryCount")
                        retryCount = 0
                    }

                    // Обрабатываем локацию в фоне
                    serviceScope.launch {
                        processLocationFast(location)
                    }
                } else {
                    Log.w(TAG, "onLocationResult: locationResult.lastLocation is null")
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.d(TAG, "onLocationAvailability: Доступность - ${availability.isLocationAvailable}")

                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "onLocationAvailability: Сервисы геолокации временно недоступны")
                } else {
                    Log.d(TAG, "onLocationAvailability: Сервисы геолокации доступны")
                    retryCount = 0
                }
            }
        }
    }

    /**
     * ОПТИМИЗИРОВАННАЯ обработка полученной локации
     */
    private suspend fun processLocationFast(location: Location) {
        try {
            if (!isRunning.get()) {
                Log.d(TAG, "processLocationFast: Сервис остановлен, пропускаем обработку")
                return
            }

            // УПРОЩЕННАЯ проверка валидности локации
            if (isLocationValidFast(location)) {
                val newPoint = Point(location.latitude, location.longitude)

                // Быстрая проверка поворота
                checkAndUpdateTurnColorFast(newPoint)

                // Обновляем данные в Firebase
                updateUserLocationInFirebaseFast(
                    location.latitude,
                    location.longitude,
                    ROUTE_COLORS[currentColorIndex],
                    location.accuracy
                )

                // Быстрое обновление состояния
                withContext(Dispatchers.Main) {
                    lastLocation = location
                    routePoints.add(newPoint)

                    if (routePoints.size > MAX_ROUTE_POINTS) {
                        routePoints.removeAt(0)
                    }
                }

                Log.d(TAG, "processLocationFast: Локация успешно обработана")
            } else {
                Log.d(TAG, "processLocationFast: Локация не прошла валидацию")
            }
        } catch (e: Exception) {
            Log.e(TAG, "processLocationFast: Ошибка обработки локации", e)
        }
    }

    /**
     * УПРОЩЕННАЯ проверка валидности локации
     */
    private fun isLocationValidFast(newLocation: Location): Boolean {
        if (newLocation.accuracy > MIN_ACCURACY * 2) {
            Log.d(TAG, "isLocationValidFast: FALSE - Низкая точность (${newLocation.accuracy}м)")
            return false
        }

        lastLocation?.let { oldLocation ->
            val distance = calculateDistanceFast(
                oldLocation.latitude,
                oldLocation.longitude,
                newLocation.latitude,
                newLocation.longitude
            )
            val timeDiffSec = (newLocation.time - oldLocation.time) / 1000.0

            if (distance < MIN_DISTANCE && timeDiffSec < 15) {
                Log.d(TAG, "isLocationValidFast: FALSE - Малое расстояние ($distance м)")
                return false
            }

            if (timeDiffSec > 0) {
                val speed = distance / timeDiffSec
                if (speed > MAX_SPEED) {
                    Log.d(TAG, "isLocationValidFast: FALSE - Высокая скорость (${"%.2f".format(speed)} м/с)")
                    return false
                }
            }

            return true
        }

        return true
    }

    /**
     * БЫСТРАЯ проверка и обновление цвета при повороте
     */
    private fun checkAndUpdateTurnColorFast(newPoint: Point) {
        synchronized(routePoints) {
            if (routePoints.size >= 2) {
                try {
                    val prevPoint1 = routePoints[routePoints.size - 2]
                    val prevPoint2 = routePoints.last()

                    val angle = calculateTurnAngleFast(prevPoint1, prevPoint2, newPoint)

                    synchronized(previousBearings) {
                        previousBearings.add(angle)

                        if (previousBearings.size > 5) {
                            previousBearings.removeAt(0)
                        }

                        val avgAngle = previousBearings.average()

                        if (abs(avgAngle) >= TURN_ANGLE_THRESHOLD) {
                            currentColorIndex = (currentColorIndex + 1) % ROUTE_COLORS.size
                            Log.d(TAG, "checkAndUpdateTurnColorFast: Резкий поворот ${avgAngle.toInt()}°")
                            previousBearings.clear()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "checkAndUpdateTurnColorFast: Ошибка расчета поворота", e)
                }
            }
        }
    }

    /**
     * ОПТИМИЗИРОВАННАЯ обработка ошибок
     */
    private fun handleLocationUpdatesErrorFast() {
        if (!isRunning.get()) {
            Log.d(TAG, "handleLocationUpdatesErrorFast: Сервис уже остановлен")
            return
        }

        if (retryCount < MAX_RETRY_COUNT) {
            retryCount++
            val delay = RETRY_DELAY_BASE * retryCount

            Log.w(TAG, "handleLocationUpdatesErrorFast: Повтор $retryCount/$MAX_RETRY_COUNT через ${delay}мс")

            handler.postDelayed({
                if (isRunning.get()) {
                    setupLocationUpdatesFast()
                }
            }, delay)
        } else {
            Log.e(TAG, "handleLocationUpdatesErrorFast: Превышено кол-во попыток. Останавливаем.")
            isServiceStarting.set(false)

            serviceScope.launch {
                stopLocationTrackingFast()
            }
        }
    }

    /**
     * БЫСТРАЯ обработка критических ошибок
     */
    private fun handleCriticalErrorFast() {
        Log.e(TAG, "handleCriticalErrorFast: Критическая ошибка, быстрая остановка")
        isServiceStarting.set(false)

        serviceScope.launch {
            stopLocationTrackingFast()
        }
    }

    /**
     * ОПТИМИЗИРОВАННАЯ остановка трекинга локации
     */
    private suspend fun stopLocationTrackingFast() {
        Log.d(TAG, "stopLocationTrackingFast: Быстрая остановка")

        // СБРАСЫВАЕМ ФЛАГИ ПЕРВЫМ ДЕЛОМ!
        isRunning.set(false)
        isServiceStarting.set(false)

        saveServiceState(false)
        setServiceRunning(false)
        removePreviousLocationCallbackFast()

        // Последовательная очистка (быстрее и надежнее)
        try {
            updateTrackingStatus(false)
            clearCurrentLocationFast()
        } catch (e: Exception) {
            Log.e(TAG, "stopLocationTrackingFast: Ошибка при очистке", e)
        }

        isLocationUpdatesActive.set(false)
        lastLocation = null
        routePoints.clear()
        previousBearings.clear()

        Log.d(TAG, "stopLocationTrackingFast: Трекинг полностью остановлен")
    }

    /**
     * Сохраняет состояние сервиса
     */
    private fun saveServiceState(isRunning: Boolean) {
        try {
            (application as? StepCounterApp)?.saveServiceState("location", isRunning)
            Log.d(TAG, "Состояние сервиса сохранено: location = $isRunning")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения состояния", e)
            val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("location_tracking_active", isRunning)
                putLong("last_service_state_change", System.currentTimeMillis())
                apply()
            }
        }
    }

    /**
     * ОПТИМИЗИРОВАННОЕ удаление предыдущего callback
     */
    private fun removePreviousLocationCallbackFast() {
        if (::locationCallback.isInitialized) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.d(TAG, "removePreviousLocationCallbackFast: Callback удален")
            } catch (e: Exception) {
                Log.d(TAG, "removePreviousLocationCallbackFast: Callback уже удален")
            }
        }
    }

    private fun handlePermissionErrorFast() {
        Log.e(TAG, "handlePermissionErrorFast: Нет разрешений на локацию")
        serviceScope.launch {
            stopLocationTrackingFast()
        }
        stopSelf()
    }

    private fun handleAuthErrorFast() {
        Log.e(TAG, "handleAuthErrorFast: Пользователь не авторизован")
        serviceScope.launch {
            stopLocationTrackingFast()
        }
        stopSelf()
    }

    private fun checkTrackingStatusAndStartFast() {
        val userId = auth.currentUser?.uid ?: return

        serviceScope.launch {
            try {
                val snapshot = database.child("tracking_status").child(userId).get().await()
                val isTracking = snapshot.getValue(Boolean::class.java) ?: false

                if (isTracking && !isRunning.get() && !isServiceStarting.get() && !isServiceRunning()) {
                    Log.d(TAG, "checkTrackingStatusAndStartFast: Восстанавливаем трекинг")
                    withContext(Dispatchers.Main) {
                        startForegroundServiceFast()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkTrackingStatusAndStartFast: Ошибка проверки статуса", e)
            }
        }
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
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(false)
                enableLights(false)
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Log.d(TAG, "createNotificationChannel: Канал уведомлений создан")
        }
    }

    private fun buildFastNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Отслеживание маршрута")
            .setContentText("Сервис отслеживания активности работает")
            .setSmallIcon(R.drawable.ic_location)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun hasForegroundLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun updateUserLocationInFirebaseFast(lat: Double, lng: Double, color: Int, accuracy: Float) {
        if (!isRunning.get()) return

        val userId = auth.currentUser?.uid ?: return
        val timestamp = System.currentTimeMillis()

        val location = UserLocation(
            lat = lat,
            lng = lng,
            timestamp = timestamp,
            color = color,
            accuracy = accuracy
        )

        try {
            database.child("user_locations").child(userId).setValue(location).await()
            Log.d(TAG, "updateUserLocationInFirebaseFast: Локация обновлена в Firebase")
        } catch (e: Exception) {
            Log.e(TAG, "updateUserLocationInFirebaseFast: Ошибка обновления Firebase", e)
        }
    }

    private suspend fun updateTrackingStatus(isTracking: Boolean) {
        val userId = auth.currentUser?.uid ?: return

        try {
            database.child("tracking_status").child(userId).setValue(isTracking).await()
            Log.d(TAG, "updateTrackingStatus: Статус трекинга обновлен: $isTracking")
        } catch (e: Exception) {
            Log.e(TAG, "updateTrackingStatus: Ошибка обновления статуса", e)
        }
    }

    private suspend fun clearCurrentLocationFast() {
        val userId = auth.currentUser?.uid ?: return

        try {
            database.child("user_locations").child(userId).removeValue().await()
            Log.d(TAG, "clearCurrentLocationFast: Текущая локация очищена")
        } catch (e: Exception) {
            Log.e(TAG, "clearCurrentLocationFast: Ошибка очистки локации", e)
        }
    }

    private fun calculateTurnAngleFast(p1: Point, p2: Point, p3: Point): Double {
        val bearing1 = calculateBearingFast(p1, p2)
        val bearing2 = calculateBearingFast(p2, p3)

        var angle = bearing2 - bearing1
        if (angle > 180) angle -= 360
        if (angle < -180) angle += 360

        return angle
    }

    private fun calculateBearingFast(from: Point, to: Point): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lat2 = Math.toRadians(to.latitude)
        val lon2 = Math.toRadians(to.longitude)

        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)

        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun calculateDistanceFast(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0

        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2) + cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Остановка сервиса")

        try {
            serviceScope.launch {
                stopLocationTrackingFast()
            }
            handler.removeCallbacksAndMessages(null)
            serviceScope.cancel()
            Log.d(TAG, "onDestroy: Ресурсы освобождены")
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy: Ошибка при остановке", e)
        } finally {
            super.onDestroy()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}