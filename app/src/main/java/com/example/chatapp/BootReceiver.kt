package com.example.chatapp.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.chatapp.services.LocationUpdateService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.d("LocationUpdate", "Device booted, scheduling location service start")

            // Задержка для стабильности системы
            Handler(Looper.getMainLooper()).postDelayed({
                val auth = FirebaseAuth.getInstance()
                val userId = auth.currentUser?.uid ?: return@postDelayed
                val database = Firebase.database.reference

                database.child("location_settings").child(userId).get()
                    .addOnSuccessListener { snapshot ->
                        val settings = snapshot.getValue(com.example.chatapp.models.LocationSettings::class.java)
                        if (settings?.enabled == true) {
                            Log.d("LocationUpdate", "Starting service after boot")
                            val serviceIntent = Intent(context, LocationUpdateService::class.java).apply {
                                action = LocationUpdateService.ACTION_START
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                        }
                    }
            }, 30000) // 30 секунд задержки
        }
    }
}