package com.example.chatapp.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.chatapp.R
import com.example.chatapp.models.UserLocation
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
    private val isRunning = AtomicBoolean(false)
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

        // Увеличена точность - уменьшены интервалы и расстояния
        private const val MIN_UPDATE_INTERVAL = 5000L // 5 секунд вместо 15
        private const val MAX_UPDATE_DELAY = 10000L // 10 секунд вместо 30
        private const val MIN_ACCURACY = 50f // Более мягкие требования к точности
        private const val MAX_SPEED = 25.0 // 90 км/ч
        private const val MIN_DISTANCE = 10f // 10 метров вместо 50 для большей точности
        private const val MAX_RETRY_COUNT_ON_START_FAILURE = 8
        private const val RETRY_DELAY_ON_START_FAILURE = 10_000L

        private const val TURN_ANGLE_THRESHOLD = 110.0
        private val ROUTE_COLORS = listOf(
            Color.BLUE,
            Color.RED,
            Color.GREEN,
            Color.MAGENTA,
            Color.CYAN
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Сервис создается...")
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        Log.d(TAG, "onCreate: Завершено")
    }

    // Замените onStartCommand():
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Вызван с action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning.get()) {
                    Log.d(TAG, "onStartCommand: Запускаем сервис в foreground")
                    startForegroundService()
                    setupLocationUpdates()
                    updateTrackingStatus(true) // ДОБАВИТЬ
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "onStartCommand: Получена команда STOP")
                stopLocationTracking()
                stopSelf()
            }
            else -> {
                // Если сервис перезапущен системой, проверяем статус трекинга
                checkTrackingStatusAndStart()
            }
        }

        return START_STICKY
    }

    // В методе stopLocationTracking() убедитесь, что статус обновляется:
    private fun stopLocationTracking() {
        Log.d(TAG, "stopLocationTracking: Начало остановки трекинга")

        // Останавливаем получение локаций
        if (::locationCallback.isInitialized) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.d(TAG, "stopLocationTracking: Обновления локации остановлены")
            } catch (e: Exception) {
                Log.e(TAG, "stopLocationTracking: Ошибка остановки обновлений локации", e)
            }
        }

        // Обновляем статус в Firebase
        updateTrackingStatus(false)

        // Очищаем текущую локацию в базе
        clearCurrentLocation()

        // Сбрасываем состояние
        isRunning.set(false)
        lastLocation = null
        routePoints.clear()
        previousBearings.clear()

        Log.d(TAG, "stopLocationTracking: Трекинг полностью остановлен")
    }

    // ДОБАВЛЕНО: очистка текущей локации в базе
    private fun clearCurrentLocation() {
        val userId = auth.currentUser?.uid ?: return
        serviceScope.launch {
            try {
                database.child("user_locations").child(userId).removeValue().await()
                Log.d(TAG, "Текущая локация очищена из user_locations")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка очистки текущей локации", e)
            }
        }
    }

    private fun updateTrackingStatus(isTracking: Boolean) {
        val userId = auth.currentUser?.uid ?: return
        serviceScope.launch {
            try {
                database.child("tracking_status").child(userId).setValue(isTracking).await()
                Log.d(TAG, "Статус трекинга обновлен: $isTracking")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обновления статуса трекинга", e)
            }
        }
    }

    private fun startForegroundService() {
        Log.d(TAG, "startForegroundService: Начало")
        try {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
            isRunning.set(true)
            Log.d(TAG, "startForegroundService: Сервис запущен в foreground")
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService: Ошибка запуска foreground", e)
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        Log.d(TAG, "buildNotification: Создание уведомления")
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Отслеживание маршрута")
            .setContentText("Сервис отслеживания активности работает в фоне")
            .setSmallIcon(R.drawable.ic_location)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setSilent(true) // Уведомление без звука
            .build()
    }

    private fun createNotificationChannel() {
        Log.d(TAG, "createNotificationChannel: Начало")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Отслеживание маршрута",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Канал для сервиса отслеживания перемещений"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            Log.d(TAG, "createNotificationChannel: Канал создан, регистрация")
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Log.d(TAG, "createNotificationChannel: Завершено")
        } else {
            Log.d(TAG, "createNotificationChannel: Пропущено (API < 26)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates() {
        Log.d(TAG, "setupLocationUpdates: Начало")

        if (!hasForegroundLocationPermissions()) {
            Log.w(TAG, "setupLocationUpdates: Нет необходимых разрешений (foreground). Останавливаем сервис.")
            stopSelf()
            return
        }

        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.w(TAG, "setupLocationUpdates: Пользователь не авторизован. Останавливаем сервис.")
            stopSelf()
            return
        }

        Log.d(TAG, "setupLocationUpdates: Разрешения и пользователь проверены")

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // ИСПРАВЛЕНО: проверяем, что сервис еще работает
                if (!isRunning.get()) {
                    Log.d(TAG, "onLocationResult: Сервис остановлен, игнорируем локации")
                    return
                }

                locationResult.lastLocation?.let { location ->
                    lastKnownAccuracy = location.accuracy
                    if (retryCount > 0) {
                        Log.d(TAG, "onLocationResult: Получена локация, сбрасываем retryCount.")
                        retryCount = 0
                    }

                    // УСОВЕРШЕНСТВОВАНО: более мягкая проверка точности
                    if (location.accuracy <= MIN_ACCURACY || location.accuracy <= lastKnownAccuracy * 1.5f) {
                        // Запускаем обработку в фоновой корутине
                        serviceScope.launch {
                            processLocationInBackground(location)
                        }
                    } else {
                        Log.d(TAG, "onLocationResult: Низкая точность (${location.accuracy}м), игнорируем")
                    }
                } ?: run {
                    Log.w(TAG, "onLocationResult: locationResult.lastLocation is null")
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.d(TAG, "onLocationAvailability: Доступность сервисов геолокации изменена. Доступно: ${availability.isLocationAvailable}")
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "onLocationAvailability: Сервисы геолокации временно недоступны")
                    retryCount = 0 // Сброс при недоступности
                } else {
                    Log.d(TAG, "onLocationAvailability: Сервисы геолокации снова доступны.")
                    retryCount = 0
                    Log.d(TAG, "onLocationAvailability: Счетчик повторных попыток сброшен.")
                }
            }
        }

        // УСОВЕРШЕНСТВОВАНО: более точный запрос локации
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, MIN_UPDATE_INTERVAL)
            .setIntervalMillis(MIN_UPDATE_INTERVAL)
            .setMinUpdateIntervalMillis(3000L) // Минимум 3 секунды
            .setMaxUpdateDelayMillis(MAX_UPDATE_DELAY)
            .setWaitForAccurateLocation(true) // Ждем точную локацию
            .setMinUpdateDistanceMeters(MIN_DISTANCE)
            .build()

        try {
            Log.d(TAG, "setupLocationUpdates: Запрашиваем обновления локации у FusedLocationProviderClient")
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            ).addOnSuccessListener {
                Log.d(TAG, "setupLocationUpdates: Обновления локации успешно запущены")
                isRunning.set(true)
                retryCount = 0

                // ДОБАВЛЕНО: немедленно запрашиваем текущую локацию
                requestSingleLocationUpdate()
            }.addOnFailureListener { e ->
                Log.e(TAG, "setupLocationUpdates: Ошибка запуска обновлений локации", e)
                if (isRunning.get() && retryCount < MAX_RETRY_COUNT_ON_START_FAILURE) {
                    retryCount++
                    val longerDelay = RETRY_DELAY_ON_START_FAILURE * (retryCount + 1)
                    Log.d(TAG, "setupLocationUpdates: Планируем повторную попытку ($retryCount/$MAX_RETRY_COUNT_ON_START_FAILURE) через ${longerDelay}мс...")
                    handler.postDelayed({
                        if (isRunning.get()) {
                            Log.d(TAG, "setupLocationUpdates: Повторная попытка запуска обновлений...")
                            setupLocationUpdates()
                        }
                    }, longerDelay)
                } else if (retryCount >= MAX_RETRY_COUNT_ON_START_FAILURE){
                    Log.e(TAG, "setupLocationUpdates: Превышено максимальное количество попыток запуска ($MAX_RETRY_COUNT_ON_START_FAILURE). Останавливаем сервис.")
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "setupLocationUpdates: Критическая ошибка при запуске сервиса", e)
            stopSelf()
        }
    }

    // ДОБАВЛЕНО: запрос единичного обновления для немедленного старта
    @SuppressLint("MissingPermission")
    private fun requestSingleLocationUpdate() {
        if (!hasForegroundLocationPermissions()) return

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0)
            .setMaxUpdates(1)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        serviceScope.launch {
                            processLocationInBackground(location)
                        }
                    }
                    // Удаляем callback после получения локации
                    fusedLocationClient.removeLocationUpdates(this)
                }
            },
            Looper.getMainLooper()
        )
    }

    // Фоновая обработка локации
    private suspend fun processLocationInBackground(location: Location) {
        try {
            // ИСПРАВЛЕНО: проверяем, что сервис еще работает
            if (!isRunning.get()) {
                Log.d(TAG, "processLocationInBackground: Сервис остановлен, игнорируем локацию")
                return
            }

            // Вся тяжелая логика теперь в IO потоке
            if (isLocationValid(location)) {
                val newPoint = Point(location.latitude, location.longitude)

                // Проверка угла поворота в главном потоке
                withContext(Dispatchers.Main) {
                    checkTurnAngle(newPoint)
                }

                // Обновление Firebase также в фоне
                updateUserLocation(
                    location.latitude,
                    location.longitude,
                    ROUTE_COLORS[currentColorIndex]
                )

                // Обновление состояния сервиса в main
                withContext(Dispatchers.Main) {
                    lastLocation = location
                    routePoints.add(newPoint)
                    if (routePoints.size > 100) { // Увеличил буфер точек
                        routePoints.removeAt(0)
                    }
                }

                Log.d(TAG, "Локация обработана: ${location.latitude}, ${location.longitude}, точность: ${location.accuracy}м")
            } else {
                Log.d(TAG, "processLocationInBackground: Локация не валидна (isLocationValid=false)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки локации в фоне", e)
        }
    }




    private fun checkTurnAngle(newPoint: Point) {
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
                Log.d(TAG, "checkTurnAngle: Резкий поворот: ${avgAngle.toInt()}° - новый цвет: ${ROUTE_COLORS[currentColorIndex]}")
                previousBearings.clear()
            }
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

        val bearing = (Math.toDegrees(atan2(y, x)) + 360) % 360
        return bearing
    }


    // Добавьте метод проверки статуса:
    private fun checkTrackingStatusAndStart() {
        val userId = auth.currentUser?.uid ?: return

        serviceScope.launch {
            try {
                val snapshot = database.child("tracking_status").child(userId).get().await()
                val isTracking = snapshot.getValue(Boolean::class.java) ?: false

                if (isTracking && !isRunning.get()) {
                    Log.d(TAG, "checkTrackingStatusAndStart: Трекинг активен, запускаем сервис")
                    withContext(Dispatchers.Main) {
                        startForegroundService()
                        setupLocationUpdates()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка проверки статуса трекинга", e)
            }
        }
    }

    // УСОВЕРШЕНСТВОВАНО: улучшена валидация локации
    private fun isLocationValid(newLocation: Location): Boolean {
        lastLocation?.let { oldLocation ->
            val distance = calculateDistance(
                oldLocation.latitude,
                oldLocation.longitude,
                newLocation.latitude,
                newLocation.longitude
            )
            val timeDiffSec = (newLocation.time - oldLocation.time) / 1000.0

            // Более мягкие условия для городской среды
            if (distance < MIN_DISTANCE && timeDiffSec < 10) {
                Log.d(TAG, "isLocationValid: FALSE - Слишком маленькое расстояние ($distance м) и время ($timeDiffSec сек)")
                return false
            }

            if (timeDiffSec > 0) {
                val speed = distance / timeDiffSec
                if (speed > MAX_SPEED) {
                    Log.d(TAG, "isLocationValid: FALSE - Слишком высокая скорость (${String.format("%.2f", speed)} м/с)")
                    return false
                }
            }

            // Проверка на скачки координат
            if (newLocation.accuracy > 100f && distance > 1000) {
                Log.d(TAG, "isLocationValid: FALSE - Возможный скачок координат (точность: ${newLocation.accuracy}м, расстояние: ${distance}м)")
                return false
            }

            return true
        }
        return true // Первая локация всегда валидна
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0

        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2) + cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        val distance = earthRadius * c
        return distance
    }

    private fun hasForegroundLocationPermissions(): Boolean {
        Log.d(TAG, "hasForegroundLocationPermissions: Проверка разрешений")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private suspend fun updateUserLocation(lat: Double, lng: Double, color: Int) {
        // ИСПРАВЛЕНО: проверяем, что сервис еще работает
        if (!isRunning.get()) {
            Log.d(TAG, "updateUserLocation: Сервис остановлен, не обновляем базу")
            return
        }

        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.w(TAG, "updateUserLocation: Пользователь не авторизован")
            return
        }
        val timestamp = System.currentTimeMillis()

        val location = UserLocation(
            lat = lat,
            lng = lng,
            timestamp = timestamp,
            color = color
        )

        try {
            // Обновляем текущую локацию для реального времени
            database.child("user_locations").child(userId).setValue(location).await()
            Log.d(TAG, "updateUserLocation: Текущая локация обновлена в user_locations")
        } catch (e: Exception) {
            Log.w(TAG, "updateUserLocation: Ошибка обновления текущей локации в user_locations", e)
        }

        try {
            // Сохраняем в историю
            database.child("user_location_history")
                .child(userId)
                .child(timestamp.toString())
                .setValue(location)
                .await()
            Log.d(TAG, "updateUserLocation: Локация сохранена в историю user_location_history")
        } catch (e: Exception) {
            Log.w(TAG, "updateUserLocation: Ошибка сохранения локации в историю user_location_history", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind: Вызван (не используется для started service)")
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Начало остановки сервиса")
        try {
            // Используем метод остановки трекинга
            stopLocationTracking()

            handler.removeCallbacksAndMessages(null)
            Log.d(TAG, "onDestroy: Callbacks и сообщения handler удалены")
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy: Ошибка при остановке", e)
        } finally {
            // Отменяем все фоновые задачи сервиса
            serviceScope.cancel()
            super.onDestroy()
            Log.d(TAG, "onDestroy: Сервис остановлен")
        }
    }
}

// ДОБАВЛЕНО: хелпер для показа тостов из сервиса
object ToastHelper {
    private var handler = Handler(Looper.getMainLooper())

    fun showToast(context: Context, message: String) {
        handler.post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}