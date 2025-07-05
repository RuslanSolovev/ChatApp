package com.example.chatapp

import android.app.Application
import android.util.Log
import com.onesignal.OneSignal
import com.yandex.mapkit.MapKitFactory

const val ONESIGNAL_APP_ID = "0083de8f-7ca0-4824-ac88-9c037278237e"

class StepCounterApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Проверка App ID
        if (ONESIGNAL_APP_ID == "0083de8f-7ca0-4824-ac88-9c037278237e") {
            Log.w("StepCounterApp", "ONESIGNAL_APP_ID не изменён! Используйте свой реальный App ID.")
        }

        // Инициализация OneSignal
        OneSignal.initWithContext(this)
        OneSignal.setAppId(ONESIGNAL_APP_ID)

        // Логирование FCM токена
        OneSignal.setNotificationOpenedHandler { result ->
            val data = result.notification.additionalData
            val chatId = data?.optString("chatId", null)
            Log.d("OneSignal", "Notification opened with chatId: $chatId")
        }





        try {
            Log.d("MapKit", "Initializing Yandex MapKit")
            MapKitFactory.setApiKey("6b7f7e6b-d322-42b2-8471-d8aecc6570d1")
            MapKitFactory.initialize(this)
            Log.d("MapKit", "Initialization successful")
        } catch (e: Exception) {
            Log.e("MapKit", "Initialization failed", e)
        }
    }
}