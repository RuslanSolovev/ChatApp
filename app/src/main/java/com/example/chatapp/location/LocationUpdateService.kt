package com.example.chatapp.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlin.math.abs

class LocationUpdateService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var handler: Handler
    private lateinit var notificationManager: NotificationManager

    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val TAG = "LocationUpdateService"
        const val NOTIFICATION_CHANNEL_ID = "location_tracker_channel"
        const val NOTIFICATION_CHANNEL_NAME = "Отслеживание маршрута"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "START_LOCATION_TRACKING"

        private const val LOCATION_UPDATE_INTERVAL = 30000L // 30 секунд
        private const val MIN_DISTANCE = 20f // 20 метров

        fun startService(context: Context) {
            Log.d(TAG, "Запуск сервиса локации")
            try {
                val intent = Intent(context, LocationUpdateService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка запуска сервиса", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate сервиса")

        try {
            handler = Handler(Looper.getMainLooper())
            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

            createNotificationChannel()
            createLocationCallback()

            Log.d(TAG, "Сервис успешно создан")
        } catch (e: Exception) {
            Log.e(TAG, "Критическая ошибка при создании сервиса", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        if (intent == null) {
            // Перезапуск сервиса системой
            startForegroundService()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                Log.d(TAG, "Запуск нового экземпляра сервиса")
                startForegroundService()
            }
            else -> {
                // По умолчанию запускаем сервис
                startForegroundService()
            }
        }

        return START_STICKY
    }

    private fun startForegroundService() {
        Log.d(TAG, "Запуск foreground сервиса")

        try {
            // Сразу показываем уведомление
            val notification = buildNotification("Сервис запущен")
            startForeground(NOTIFICATION_ID, notification)

            // Запускаем обновления локации
            serviceScope.launch {
                try {
                    setupLocationUpdates()
                    Log.d(TAG, "Foreground сервис успешно запущен")
                    updateNotification("Сервис активен")

                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка при запуске сервиса", e)
                    updateNotification("Ошибка: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Критическая ошибка запуска сервиса", e)
            stopSelf()
        }
    }

    private fun buildNotification(text: String): Notification {
        val packageName = packageName
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent ?: Intent(),
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
            .setOnlyAlertOnce(true)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates() {
        Log.d(TAG, "Настройка обновлений локации")

        // Проверяем разрешения
        if (!hasLocationPermissions()) {
            Log.e(TAG, "НЕТ РАЗРЕШЕНИЙ НА ЛОКАЦИЮ!")
            updateNotification("Нет разрешений на локацию")
            showPermissionNotification()
            return
        }

        // Проверяем включена ли локация
        if (!isLocationEnabled()) {
            Log.e(TAG, "Локация отключена в системе")
            updateNotification("Локация отключена")
            showLocationDisabledNotification()
            return
        }

        Log.d(TAG, "Разрешения есть, локация включена")

        try {
            // Удаляем старые обновления если есть
            removeLocationUpdates()

            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                LOCATION_UPDATE_INTERVAL
            )
                .setMinUpdateDistanceMeters(MIN_DISTANCE)
                .setWaitForAccurateLocation(true)
                .build()

            Log.d(TAG, "Запрос обновлений: интервал=${LOCATION_UPDATE_INTERVAL}мс")

            // Запускаем обновления локации
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            ).addOnSuccessListener {
                Log.d(TAG, "Обновления локации успешно запущены")
                updateNotification("Отслеживание активно")

            }.addOnFailureListener { e ->
                Log.e(TAG, "Ошибка запуска обновлений локации", e)
                updateNotification("Ошибка подключения")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Критическая ошибка настройки локации", e)
            updateNotification("Ошибка настройки")
        }
    }

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location != null) {
                    Log.d(TAG, "Получена локация: " +
                            "lat=${"%.6f".format(location.latitude)}, " +
                            "lng=${"%.6f".format(location.longitude)}")

                    if (isValidLocation(location)) {
                        Log.d(TAG, "Локация прошла валидацию")

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
                        Log.w(TAG, "Локация не прошла валидацию")
                    }
                } else {
                    Log.w(TAG, "Локация null в callback")
                    updateNotification("Локация не получена")
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.d(TAG, "Доступность локации: ${availability.isLocationAvailable}")

                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "Локация временно недоступна")
                    updateNotification("Поиск сигнала...")
                } else {
                    Log.d(TAG, "Локация снова доступна")
                    updateNotification("Сигнал восстановлен")
                }
            }
        }
    }

    private fun isValidLocation(location: Location): Boolean {
        if (location.latitude == 0.0 || location.longitude == 0.0) {
            return false
        }

        if (abs(location.latitude) > 90 || abs(location.longitude) > 180) {
            return false
        }

        return true
    }

    private suspend fun processNewLocation(location: Location) {
        try {
            saveLocationToFirebase(location)
            Log.d(TAG, "Локация обработана и сохранена")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки локации", e)
        }
    }

    private suspend fun saveLocationToFirebase(location: Location) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.e(TAG, "Пользователь не авторизован")
            return
        }

        try {
            // Создаем объект локации
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
            )

            // 1. Сохраняем в историю
            val historyKey = database.child("route_history").child(userId).push().key
            if (historyKey != null) {
                database.child("route_history")
                    .child(userId)
                    .child(historyKey)
                    .setValue(locationData)
                    .await()
                Log.d(TAG, "Локация сохранена в историю")
            }

            // 2. Обновляем последнюю локацию
            database.child("last_locations")
                .child(userId)
                .setValue(locationData)
                .await()

            Log.d(TAG, "Последняя локация обновлена в Firebase")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения локации в Firebase", e)
        }
    }

    private fun removeLocationUpdates() {
        try {
            if (this::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.d(TAG, "Location updates removed")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Location updates already removed or error", e)
        }
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

            hasFineLocation || hasCoarseLocation
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки разрешений", e)
            false
        }
    }

    private fun isLocationEnabled(): Boolean {
        return try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            gpsEnabled || networkEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки локации", e)
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
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
                Log.d(TAG, "Канал уведомлений создан")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка создания канала уведомлений", e)
            }
        }
    }

    private fun updateNotification(text: String) {
        try {
            val notification = buildNotification(text)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления уведомления", e)
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
            Log.e(TAG, "Ошибка показа уведомления о разрешениях", e)
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
            Log.e(TAG, "Ошибка показа уведомления о локации", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy сервиса")
        try {
            removeLocationUpdates()
            handler.removeCallbacksAndMessages(null)
            serviceScope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в onDestroy", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}