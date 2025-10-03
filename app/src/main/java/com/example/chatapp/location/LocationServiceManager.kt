package com.example.chatapp.location

import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

// LocationServiceManager.kt
object LocationServiceManager {

    fun startLocationService(context: Context) {
        val intent = Intent(context, LocationUpdateService::class.java).apply {
            action = LocationUpdateService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopLocationService(context: Context) {
        val intent = Intent(context, LocationUpdateService::class.java).apply {
            action = LocationUpdateService.ACTION_STOP
        }
        context.startService(intent)
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
                    callback(false)
                }
            })
    }
}