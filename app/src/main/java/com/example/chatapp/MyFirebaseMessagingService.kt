package com.example.chatapp.services

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Log.d("FCM_TOKEN", "Refreshed token: $token")
        // OneSignal автоматически обрабатывает токен
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Пустая реализация - OneSignal будет обрабатывать уведомления
    }
}