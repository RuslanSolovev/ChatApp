package com.example.chatapp.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val routePoints = mutableListOf<Point>()
    private val previousBearings = mutableListOf<Double>()

    // Корутин скоуп для фоновых задач сервиса
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val TAG = "LocationUpdateService"
        const val NOTIFICATION_CHANNEL_ID = "location_service_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.example.chatapp.action.START_LOCATION_SERVICE"
        const val ACTION_STOP = "com.example.chatapp.action.STOP_LOCATION_SERVICE"

        // Настройки локации
        private const val LOCATION_UPDATE_INTERVAL = 10000L // 10 секунд для стабильности
        private const val FASTEST_UPDATE_INTERVAL = 5000L // 5 секунд
        private const val MAX_UPDATE_DELAY = 15000L // 15 секунд
        private const val MIN_ACCURACY = 100f // Увеличил допустимую погрешность
        private const val MIN_DISTANCE = 20f // Увеличил минимальное расстояние
        private const val MAX_SPEED = 50.0 // Увеличил максимальную скорость

        // Настройки повторных попыток
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_BASE = 10000L // 10 секунд

        // Настройки поворотов
        private const val TURN_ANGLE_THRESHOLD = 110.0
        private const val MAX_ROUTE_POINTS = 50

        private val ROUTE_COLORS = listOf(
            Color.BLUE,
            Color.RED,
            Color.GREEN,
            Color.MAGENTA,
            Color.CYAN
        )

        // Флаг для отслеживания запущенного сервиса
        private var isServiceRunningGlobally = false
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Инициализация сервиса...")

        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        Log.d(TAG, "onCreate: Сервис инициализирован")
    }

    // В классе LocationUpdateService обновите onStartCommand:

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Вызван с action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                if (isServiceStarting.getAndSet(true)) {
                    Log.w(TAG, "onStartCommand: Сервис уже запускается, пропускаем дублирующий запрос")
                    return START_STICKY
                }

                if (isServiceRunningGlobally) {
                    Log.w(TAG, "onStartCommand: Сервис уже запущен глобально, пропускаем")
                    isServiceStarting.set(false)
                    return START_STICKY
                }

                if (!isRunning.get()) {
                    Log.d(TAG, "onStartCommand: Запускаем сервис в foreground")
                    // ЗАПУСКАЕМ СРАЗУ В ТЕКУЩЕМ ПОТОКЕ, БЕЗ КОРУТИНЫ
                    startForegroundServiceImmediately()
                } else {
                    Log.d(TAG, "onStartCommand: Сервис уже запущен локально")
                    isServiceStarting.set(false)
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "onStartCommand: Получена команда STOP")
                serviceScope.launch {
                    stopLocationTracking()
                }
                stopSelf()
            }
            else -> {
                Log.d(TAG, "onStartCommand: Перезапуск системой, проверяем статус трекинга")
                checkTrackingStatusAndStart()
            }
        }

        return START_STICKY
    }

    /**
     * Немедленный запуск сервиса без корутин
     */
    private fun startForegroundServiceImmediately() {
        Log.d(TAG, "startForegroundServiceImmediately: Начало")
        try {
            // СОХРАНЯЕМ СОСТОЯНИЕ ПЕРЕД ЗАПУСКОМ
            saveServiceState(true)

            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
            isRunning.set(true)
            isServiceRunningGlobally = true

            // Запускаем обновления локации в фоне через корутину
            serviceScope.launch {
                setupLocationUpdates()
                updateTrackingStatus(true)
            }

            Log.d(TAG, "startForegroundServiceImmediately: Сервис успешно запущен")
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundServiceImmediately: Ошибка запуска сервиса", e)
            saveServiceState(false) // Сбрасываем при ошибке
            isServiceStarting.set(false)
            isServiceRunningGlobally = false
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved: Сервис удален из списка задач, перезапускаем...")

        // Создаем Intent для перезапуска
        val restartIntent = Intent(applicationContext, LocationUpdateService::class.java).apply {
            action = ACTION_START
        }

        // Используем PendingIntent для надежного перезапуска
        val restartServiceIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        // Перезапускаем через 5 секунд
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 5000,
            restartServiceIntent
        )

        super.onTaskRemoved(rootIntent)
    }

    /**
     * Обработка низкой памяти
     */
    override fun onTrimMemory(level: Int) {
        when (level) {
            TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "onTrimMemory: TRIM_MEMORY_COMPLETE - система требует освободить память")
                // Можно временно остановить некоторые операции, но не сервис полностью
            }
            else -> {
                Log.d(TAG, "onTrimMemory: level=$level")
            }
        }
        super.onTrimMemory(level)
    }

    /**
     * Сохраняет состояние сервиса для автоматического перезапуска после перезагрузки
     */
    private fun saveServiceState(isRunning: Boolean) {
        try {
            // Пытаемся сохранить через Application класс
            (application as? StepCounterApp)?.saveServiceState("location", isRunning)
            Log.d(TAG, "Состояние сервиса сохранено через Application: location = $isRunning")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения состояния через Application, используем SharedPreferences", e)
            // Резервное сохранение в SharedPreferences
            val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("location_tracking_active", isRunning)
                putLong("last_service_state_change", System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "Состояние сервиса сохранено в SharedPreferences: location_tracking_active=$isRunning")
        }
    }

    /**
     * Безопасный запуск foreground сервиса
     */
    private suspend fun startForegroundServiceSafely() {
        Log.d(TAG, "startForegroundServiceSafely: Начало")
        try {
            // СОХРАНЯЕМ СОСТОЯНИЕ ПЕРЕД ЗАПУСКОМ - ВАЖНО ДЛЯ ПЕРЕЗАПУСКА
            saveServiceState(true)

            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
            isRunning.set(true)
            isServiceRunningGlobally = true

            // Запускаем обновления локации
            setupLocationUpdates()

            // Обновляем статус в Firebase
            updateTrackingStatus(true)

            Log.d(TAG, "startForegroundServiceSafely: Сервис успешно запущен")
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundServiceSafely: Ошибка запуска сервиса", e)
            saveServiceState(false) // Сбрасываем при ошибке
            isServiceStarting.set(false)
            isServiceRunningGlobally = false
            stopSelf()
        }
    }

    /**
     * Остановка трекинга локации
     */
    private suspend fun stopLocationTracking() {
        Log.d(TAG, "stopLocationTracking: Начало остановки")

        // СОХРАНЯЕМ СОСТОЯНИЕ ПЕРЕД ОСТАНОВКОЙ - ВАЖНО ДЛЯ ПЕРЕЗАПУСКА
        saveServiceState(false)

        // Останавливаем получение локаций
        removePreviousLocationCallback()

        // Обновляем статус в Firebase
        updateTrackingStatus(false)

        // Очищаем текущую локацию
        clearCurrentLocation()

        // Сбрасываем состояние
        isRunning.set(false)
        isServiceStarting.set(false)
        isLocationUpdatesActive.set(false)
        isServiceRunningGlobally = false
        lastLocation = null
        routePoints.clear()
        previousBearings.clear()

        Log.d(TAG, "stopLocationTracking: Трекинг полностью остановлен")
    }

    /**
     * Настройка получения обновлений локации
     */
    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates() {
        Log.d(TAG, "setupLocationUpdates: Начало настройки")

        // Проверка разрешений
        if (!hasForegroundLocationPermissions()) {
            Log.w(TAG, "setupLocationUpdates: Нет необходимых разрешений. Останавливаем сервис.")
            handlePermissionError()
            return
        }

        // Проверка авторизации пользователя
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.w(TAG, "setupLocationUpdates: Пользователь не авторизован. Останавливаем сервис.")
            handleAuthError()
            return
        }

        Log.d(TAG, "setupLocationUpdates: Разрешения и авторизация проверены")

        // Удаляем предыдущий callback если был
        removePreviousLocationCallback()

        // Создаем новый callback
        createLocationCallback()

        // Настраиваем запрос локации
        val locationRequest = createLocationRequest()

        try {
            Log.d(TAG, "setupLocationUpdates: Запрашиваем обновления локации")
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            ).addOnSuccessListener {
                Log.d(TAG, "setupLocationUpdates: Обновления локации успешно запущены")
                isLocationUpdatesActive.set(true)
                isServiceStarting.set(false)
                retryCount = 0

                // Запрашиваем текущую локацию для немедленного обновления
                requestSingleLocationUpdate()

            }.addOnFailureListener { e ->
                Log.e(TAG, "setupLocationUpdates: Ошибка запуска обновлений локации", e)
                handleLocationUpdatesError()
            }
        } catch (e: Exception) {
            Log.e(TAG, "setupLocationUpdates: Критическая ошибка при запросе локации", e)
            handleCriticalError()
        }
    }

    /**
     * Создание callback для обработки обновлений локации
     */
    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (!isRunning.get()) {
                    Log.d(TAG, "onLocationResult: Сервис остановлен, игнорируем локации")
                    return
                }

                val location = locationResult.lastLocation
                if (location != null) {
                    Log.d(TAG, "onLocationResult: Получена новая локация: ${location.latitude}, ${location.longitude}, точность: ${location.accuracy}м")

                    lastKnownAccuracy = location.accuracy
                    if (retryCount > 0) {
                        Log.d(TAG, "onLocationResult: Получена локация, сбрасываем retryCount")
                        retryCount = 0
                    }

                    // Обрабатываем локацию в фоне
                    serviceScope.launch {
                        processLocation(location)
                    }
                } else {
                    Log.w(TAG, "onLocationResult: locationResult.lastLocation is null")
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.d(TAG, "onLocationAvailability: Доступность изменена - доступно: ${availability.isLocationAvailable}")

                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "onLocationAvailability: Сервисы геолокации временно недоступны")
                    // Не увеличиваем retryCount, т.к. это системная проблема
                } else {
                    Log.d(TAG, "onLocationAvailability: Сервисы геолокации доступны")
                    retryCount = 0 // Сбрасываем при восстановлении доступности
                }
            }
        }
    }

    /**
     * Обработка полученной локации
     */
    private suspend fun processLocation(location: Location) {
        try {
            if (!isRunning.get()) {
                Log.d(TAG, "processLocation: Сервис остановлен, пропускаем обработку")
                return
            }

            // Проверяем валидность локации
            if (isLocationValid(location)) {
                val newPoint = Point(location.latitude, location.longitude)

                // Проверяем поворот и обновляем цвет если нужно
                checkAndUpdateTurnColor(newPoint)

                // Обновляем данные в Firebase
                updateUserLocationInFirebase(
                    location.latitude,
                    location.longitude,
                    ROUTE_COLORS[currentColorIndex],
                    location.accuracy
                )

                // Обновляем состояние
                withContext(Dispatchers.Main) {
                    lastLocation = location
                    routePoints.add(newPoint)

                    // Ограничиваем размер буфера точек
                    if (routePoints.size > MAX_ROUTE_POINTS) {
                        routePoints.removeAt(0)
                    }
                }

                Log.d(TAG, "processLocation: Локация успешно обработана")
            } else {
                Log.d(TAG, "processLocation: Локация не прошла валидацию")
            }
        } catch (e: Exception) {
            Log.e(TAG, "processLocation: Ошибка обработки локации", e)
        }
    }

    /**
     * Проверка валидности локации
     */
    private fun isLocationValid(newLocation: Location): Boolean {
        // Более мягкая проверка для нестабильных условий
        if (newLocation.accuracy > MIN_ACCURACY * 2) {
            Log.d(TAG, "isLocationValid: FALSE - Низкая точность (${newLocation.accuracy}м)")
            return false
        }

        lastLocation?.let { oldLocation ->
            val distance = calculateDistance(
                oldLocation.latitude,
                oldLocation.longitude,
                newLocation.latitude,
                newLocation.longitude
            )
            val timeDiffSec = (newLocation.time - oldLocation.time) / 1000.0

            // Проверка на слишком маленькое расстояние за короткое время
            if (distance < MIN_DISTANCE && timeDiffSec < 15) {
                Log.d(TAG, "isLocationValid: FALSE - Малое расстояние ($distance м) за короткое время ($timeDiffSec сек)")
                return false
            }

            // Проверка скорости
            if (timeDiffSec > 0) {
                val speed = distance / timeDiffSec
                if (speed > MAX_SPEED) {
                    Log.d(TAG, "isLocationValid: FALSE - Высокая скорость (${"%.2f".format(speed)} м/с)")
                    return false
                }
            }

            return true
        }

        // Первая локация всегда валидна
        return true
    }

    /**
     * Обработка ошибок получения локации
     */
    private fun handleLocationUpdatesError() {
        if (isRunning.get() && retryCount < MAX_RETRY_COUNT) {
            retryCount++
            val delay = RETRY_DELAY_BASE * retryCount

            Log.w(TAG, "handleLocationUpdatesError: Повторная попытка $retryCount/$MAX_RETRY_COUNT через ${delay}мс")

            handler.postDelayed({
                if (isRunning.get()) {
                    setupLocationUpdates()
                }
            }, delay)
        } else {
            Log.e(TAG, "handleLocationUpdatesError: Превышено максимальное количество попыток. Останавливаем сервис.")
            isServiceStarting.set(false)
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Остановка сервиса")

        try {
            // Запускаем остановку в корутине
            serviceScope.launch {
                stopLocationTracking()
            }
            handler.removeCallbacksAndMessages(null)
            serviceScope.cancel()
            Log.d(TAG, "onDestroy: Ресурсы освобождены")
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy: Ошибка при остановке", e)
        } finally {
            super.onDestroy()
            Log.d(TAG, "onDestroy: Сервис уничтожен")
        }
    }

    private fun createLocationRequest(): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, LOCATION_UPDATE_INTERVAL)
            .setIntervalMillis(LOCATION_UPDATE_INTERVAL)
            .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL)
            .setMaxUpdateDelayMillis(MAX_UPDATE_DELAY)
            .setWaitForAccurateLocation(false) // Упростил для стабильности
            .setMinUpdateDistanceMeters(MIN_DISTANCE)
            .build()
    }

    private fun checkAndUpdateTurnColor(newPoint: Point) {
        if (routePoints.size >= 2) {
            val prevPoint1 = routePoints[routePoints.size - 2]
            val prevPoint2 = routePoints.last()

            val angle = calculateTurnAngle(prevPoint1, prevPoint2, newPoint)
            previousBearings.add(angle)

            if (previousBearings.size > 5) {
                previousBearings.removeAt(0)
            }

            val avgAngle = previousBearings.average()

            if (abs(avgAngle) >= TURN_ANGLE_THRESHOLD) {
                currentColorIndex = (currentColorIndex + 1) % ROUTE_COLORS.size
                Log.d(TAG, "checkAndUpdateTurnColor: Резкий поворот ${avgAngle.toInt()}° - новый цвет: ${ROUTE_COLORS[currentColorIndex]}")
                previousBearings.clear()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleLocationUpdate() {
        if (!hasForegroundLocationPermissions()) return

        val immediateLocationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0)
            .setMaxUpdates(1)
            .setWaitForAccurateLocation(false)
            .build()

        fusedLocationClient.requestLocationUpdates(
            immediateLocationRequest,
            object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        serviceScope.launch {
                            processLocation(location)
                        }
                    }
                    // Удаляем временный callback
                    fusedLocationClient.removeLocationUpdates(this)
                }
            },
            Looper.getMainLooper()
        )
    }

    private fun removePreviousLocationCallback() {
        if (::locationCallback.isInitialized) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.d(TAG, "removePreviousLocationCallback: Предыдущий callback удален")
            } catch (e: Exception) {
                Log.e(TAG, "removePreviousLocationCallback: Ошибка удаления callback", e)
            }
        }
    }

    private fun handlePermissionError() {
        Log.e(TAG, "handlePermissionError: Нет разрешений на локацию")
        saveServiceState(false) // Сохраняем состояние при ошибке
        isServiceStarting.set(false)
        isServiceRunningGlobally = false
        stopSelf()
    }

    private fun handleAuthError() {
        Log.e(TAG, "handleAuthError: Пользователь не авторизован")
        saveServiceState(false) // Сохраняем состояние при ошибке
        isServiceStarting.set(false)
        isServiceRunningGlobally = false
        stopSelf()
    }

    private fun handleCriticalError() {
        Log.e(TAG, "handleCriticalError: Критическая ошибка, останавливаем сервис")
        saveServiceState(false) // Сохраняем состояние при ошибке
        isServiceStarting.set(false)
        isServiceRunningGlobally = false
        stopSelf()
    }

    private fun checkTrackingStatusAndStart() {
        val userId = auth.currentUser?.uid ?: return

        serviceScope.launch {
            try {
                val snapshot = database.child("tracking_status").child(userId).get().await()
                val isTracking = snapshot.getValue(Boolean::class.java) ?: false

                if (isTracking && !isRunning.get() && !isServiceStarting.get() && !isServiceRunningGlobally) {
                    Log.d(TAG, "checkTrackingStatusAndStart: Восстанавливаем трекинг после перезапуска")
                    withContext(Dispatchers.Main) {
                        startForegroundServiceSafely()
                    }
                } else {
                    Log.d(TAG, "checkTrackingStatusAndStart: Трекинг не активен или сервис уже работает")
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkTrackingStatusAndStart: Ошибка проверки статуса", e)
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
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                enableVibration(false)
                enableLights(false)
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Log.d(TAG, "createNotificationChannel: Канал уведомлений создан")
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Отслеживание маршрута")
            .setContentText("Сервис отслеживания активности работает")
            .setSmallIcon(R.drawable.ic_location)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    private fun hasForegroundLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun updateUserLocationInFirebase(lat: Double, lng: Double, color: Int, accuracy: Float) {
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
            // Обновляем текущую локацию
            database.child("user_locations").child(userId).setValue(location).await()

            // Сохраняем в историю
            database.child("user_location_history")
                .child(userId)
                .child(timestamp.toString())
                .setValue(location)
                .await()

            Log.d(TAG, "updateUserLocationInFirebase: Локация обновлена в Firebase")
        } catch (e: Exception) {
            Log.e(TAG, "updateUserLocationInFirebase: Ошибка обновления Firebase", e)
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

    private suspend fun clearCurrentLocation() {
        val userId = auth.currentUser?.uid ?: return

        try {
            database.child("user_locations").child(userId).removeValue().await()
            Log.d(TAG, "clearCurrentLocation: Текущая локация очищена")
        } catch (e: Exception) {
            Log.e(TAG, "clearCurrentLocation: Ошибка очистки локации", e)
        }
    }

    private fun calculateTurnAngle(p1: Point, p2: Point, p3: Point): Double {
        val bearing1 = calculateBearing(p1, p2)
        val bearing2 = calculateBearing(p2, p3)

        var angle = bearing2 - bearing1
        if (angle > 180) angle -= 360
        if (angle < -180) angle += 360

        return angle
    }

    private fun calculateBearing(from: Point, to: Point): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lat2 = Math.toRadians(to.latitude)
        val lon2 = Math.toRadians(to.longitude)

        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)

        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0

        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2) + cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}