package com.example.chatapp.location

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

object LocationServiceManager {
    private const val TAG = "LocationServiceManager"

    fun startLocationService(context: Context) {
        try {
            Log.d(TAG, "Starting location service...")
            // Сервис теперь всегда запускается с одним действием
            val intent = Intent(context, LocationUpdateService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Location service start command sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location service", e)
        }
    }

    // Удаляем функцию stopLocationService, так как сервис теперь всегда активен
    // fun stopLocationService(context: Context) {...}

    // Эта функция теперь всегда возвращает true, так как трекинг всегда активен
    fun isTrackingActive(callback: (Boolean) -> Unit) {
        // Так как трекинг теперь всегда активен, возвращаем true
        callback(true)
    }

    fun isServiceRunning(context: Context): Boolean {
        return (context.applicationContext as? com.example.chatapp.step.StepCounterApp)
            ?.getServiceState("location") ?: false
    }

    // Дополнительная функция для проверки, запущен ли сервис в данный момент
    fun checkServiceStatus(context: Context, callback: (Boolean) -> Unit) {
        // Проверяем, запущен ли сервис
        callback(isServiceRunning(context))
    }
}