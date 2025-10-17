package com.example.chatapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*

class LocationWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()

    companion object {
        private const val TAG = "LocationWorker"
    }

    override fun doWork(): Result {
        return try {
            Log.d(TAG, "LocationWorker started for Android 12+")
            requestSingleLocationUpdate()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "LocationWorker failed: ${e.message}")
            // Для Android 12+ используем более агрессивный retry
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Result.retry()
            } else {
                Result.success() // На старых версиях не перезапускаем
            }
        }
    }

    private fun requestSingleLocationUpdate() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)

        if (!hasLocationPermission()) {
            Log.w(TAG, "No location permission")
            return
        }

        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                10000
            ).setMaxUpdates(1).build()

            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        Log.d(TAG, "Location received: ${location.latitude}, ${location.longitude}")
                        saveLocationToFirebase(location)
                    }
                    // Удаляем callback после получения локации
                    fusedLocationClient.removeLocationUpdates(this)
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            // Автоматически останавливаем через 30 секунд
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    fusedLocationClient.removeLocationUpdates(locationCallback)
                    Log.d(TAG, "Location updates stopped after timeout")
                } catch (e: Exception) {
                    Log.d(TAG, "Location callback already removed")
                }
            }, 30000)

        } catch (securityException: SecurityException) {
            Log.e(TAG, "Security exception: ${securityException.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting location: ${e.message}")
        }
    }

    private fun hasLocationPermission(): Boolean {
        return try {
            val fineLocationGranted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val coarseLocationGranted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            fineLocationGranted || coarseLocationGranted
        } catch (e: Exception) {
            Log.e(TAG, "Error checking location permission", e)
            false
        }
    }

    private fun saveLocationToFirebase(location: android.location.Location) {
        try {
            val userId = auth.currentUser?.uid
            if (userId == null) {
                Log.w(TAG, "User not authenticated")
                return
            }

            val timestamp = System.currentTimeMillis()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val formattedTime = dateFormat.format(Date(timestamp))

            val locationData = mapOf(
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "accuracy" to location.accuracy,
                "timestamp" to timestamp,
                "formattedTime" to formattedTime,
                "altitude" to (location.altitude ?: 0.0),
                "speed" to (location.speed ?: 0.0f),
                "bearing" to (location.bearing ?: 0.0f)
            )

            // Сохраняем в Firebase
            database.child("user_locations_worker").child(userId)
                .child(timestamp.toString())
                .setValue(locationData)
                .addOnSuccessListener {
                    Log.d(TAG, "Location saved to Firebase successfully")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to save location to Firebase: ${e.message}")
                }

            // Также обновляем последнюю известную локацию
            database.child("user_locations").child(userId)
                .setValue(locationData)
                .addOnSuccessListener {
                    Log.d(TAG, "Last location updated in Firebase")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update last location: ${e.message}")
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error saving location to Firebase", e)
        }
    }
}