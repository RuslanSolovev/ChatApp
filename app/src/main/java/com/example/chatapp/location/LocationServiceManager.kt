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
            val intent = Intent(context, LocationUpdateService::class.java).apply {
                action = LocationUpdateService.ACTION_START
            }

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

    fun stopLocationService(context: Context) {
        try {
            Log.d(TAG, "Stopping location service...")
            val intent = Intent(context, LocationUpdateService::class.java).apply {
                action = LocationUpdateService.ACTION_STOP
            }
            context.startService(intent) // Отправляем команду STOP через startService
            Log.d(TAG, "Location service stop command sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop location service", e)
        }
    }

    fun isTrackingActive(context: Context, callback: (Boolean) -> Unit) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return callback(false)

        FirebaseDatabase.getInstance().reference
            .child("tracking_status")
            .child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    callback(snapshot.getValue(Boolean::class.java) ?: false)
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Error checking tracking status", error.toException())
                    callback(false)
                }
            })
    }

    fun isServiceRunning(context: Context): Boolean {
        return (context.applicationContext as? com.example.chatapp.step.StepCounterApp)
            ?.getServiceState("location") ?: false
    }
}