package com.example.chatapp.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.chatapp.R
import com.example.chatapp.models.UserLocation
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicBoolean

class LocationUpdateService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()
    private val isRunning = AtomicBoolean(false)

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "location_service_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "START_FOREGROUND"
        const val ACTION_STOP = "STOP_FOREGROUND"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("LocationUpdateService", "Creating service...")
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundService()
                setupLocationUpdates()
            }
            ACTION_STOP -> stopSelf()
            else -> if (!isRunning.get()) startForegroundService()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Обновление местоположения")
            .setContentText("Приложение обновляет вашу геопозицию в фоне")
            .setSmallIcon(R.drawable.icons8_unit_50)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Location Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Channel for location update service" }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates() {
        if (!hasLocationPermissions()) {
            Log.w("LocationUpdateService", "Missing location permissions")
            stopSelf()
            return
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    updateUserLocation(location.latitude, location.longitude)
                }
            }
        }

        val userId = auth.currentUser?.uid ?: return
        database.child("location_settings").child(userId).get()
            .addOnSuccessListener { snapshot ->
                val settings = snapshot.getValue(com.example.chatapp.models.LocationSettings::class.java)
                if (settings?.enabled != true) {
                    stopSelf()
                    return@addOnSuccessListener
                }

                val updateInterval = (settings.updateInterval ?: 5) * 60 * 1000L
                val minUpdateInterval = maxOf(updateInterval / 2, 5 * 60 * 1000L)

                val locationRequest = LocationRequest.Builder(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    updateInterval
                ).apply {
                    setMinUpdateIntervalMillis(minUpdateInterval)
                    setMaxUpdateDelayMillis(updateInterval * 2)
                }.build()

                try {
                    fusedLocationClient.requestLocationUpdates(
                        locationRequest,
                        locationCallback,
                        Looper.getMainLooper()
                    )
                    isRunning.set(true)
                    Log.d("LocationUpdateService", "Location updates started")
                } catch (e: SecurityException) {
                    Log.e("LocationUpdateService", "Security exception", e)
                    stopSelf()
                }
            }
    }

    private fun hasLocationPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        } else {
            true
        }
    }

    private fun updateUserLocation(lat: Double, lng: Double) {
        val userId = auth.currentUser?.uid ?: return
        val location = UserLocation(lat, lng, System.currentTimeMillis())
        database.child("user_locations").child(userId).setValue(location)
            .addOnSuccessListener {
                Log.d("LocationUpdateService", "Location updated: $lat, $lng")
            }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
            isRunning.set(false)
            Log.d("LocationUpdateService", "Service destroyed")
        } catch (e: Exception) {
            Log.e("LocationUpdateService", "Error stopping updates", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, LocationUpdateService::class.java).apply {
            action = ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }
}