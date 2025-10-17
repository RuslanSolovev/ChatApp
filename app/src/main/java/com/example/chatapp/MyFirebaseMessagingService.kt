package com.example.chatapp.services

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "MyFirebaseMessaging"
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "Refreshed FCM token: $token")
        // OneSignal автоматически обрабатывает токен
        // Не нужно делать ничего дополнительного
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "Received FCM message from: ${remoteMessage.from}")

        // OneSignal будет обрабатывать уведомления
        // Эта реализация оставлена пустой чтобы избежать дублирования
    }
}