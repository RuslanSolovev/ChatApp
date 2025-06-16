package com.example.chatapp

import android.app.Application
import android.util.Log
import com.yandex.mapkit.MapKitFactory

class StepCounterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Log.d("MapKit", "Initializing Yandex MapKit")
            MapKitFactory.setApiKey("6b7f7e6b-d322-42b2-8471-d8aecc6570d1")
            MapKitFactory.initialize(this)
            Log.d("MapKit", "Initialization successful")
        } catch (e: Exception) {
            Log.e("MapKit", "Initialization failed", e)
            // Можно добавить уведомление об ошибке
        }
    }
}