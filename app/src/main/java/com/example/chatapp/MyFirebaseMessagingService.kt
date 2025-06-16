package com.example.chatapp.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.chatapp.R
import com.example.chatapp.activities.ChatDetailActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.*

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val database by lazy { FirebaseDatabase.getInstance().reference }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val currentUser = auth.currentUser
        if (currentUser != null) {
            database.child("users").child(currentUser.uid).child("fcmToken")
                .setValue(token)
                .addOnFailureListener { e ->
                    // Повторная попытка через 5 секунд при ошибке
                    android.os.Handler(mainLooper).postDelayed({
                        database.child("users").child(currentUser.uid).child("fcmToken")
                            .setValue(token)
                    }, 5000)
                }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Обрабатываем data payload (работает всегда)
        remoteMessage.data.let { data ->
            val senderId = data["senderId"] ?: ""
            val senderName = data["senderName"] ?: "Unknown"
            val chatName = data["chatName"] ?: "Chat"
            val messageText = data["message"] ?: "New message"
            val chatId = data["chatId"] ?: ""
            val isGroup = data["isGroup"]?.toBoolean() ?: false
            val imageUrl = data["imageUrl"]

            // Не показываем уведомление если пользователь в этом чате
            if (isUserInChat(chatId)) return

            showChatNotification(
                senderId = senderId,
                senderName = senderName,
                chatName = chatName,
                messageText = messageText,
                chatId = chatId,
                isGroup = isGroup,
                imageUrl = imageUrl
            )
        }

        // Обрабатываем notification payload (если приложение в бэкграунде)
        remoteMessage.notification?.let { notification ->
            // Для случаев когда notification приходит отдельно
            showBasicNotification(
                title = notification.title ?: "New message",
                message = notification.body ?: "",
                chatId = remoteMessage.data["chatId"] ?: ""
            )
        }
    }

    private fun isUserInChat(chatId: String): Boolean {
        // Здесь должна быть логика проверки активного чата
        // Например, сравнение с текущим открытым чатом в Activity
        return false
    }

    private fun showChatNotification(
        senderId: String,
        senderName: String,
        chatName: String,
        messageText: String,
        chatId: String,
        isGroup: Boolean,
        imageUrl: String? = null
    ) {
        val notificationId = chatId.hashCode()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Создаем канал для Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "chat_channel_id",
                "Chat Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming chat messages"
                enableLights(true)
                lightColor = Color.BLUE
                enableVibration(true)
                vibrationPattern = longArrayOf(100, 200, 300, 400, 500)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Intent для открытия чата
        val intent = Intent(this, ChatDetailActivity::class.java).apply {
            putExtra("chatId", chatId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Строим уведомление
        val notificationBuilder = NotificationCompat.Builder(this, "chat_channel_id")
            .setSmallIcon(R.drawable.solovei_png)
            .setColor(Color.parseColor("#FF4081")) // Цвет акцента
            .setContentTitle(if (isGroup) "$chatName • $senderName" else senderName)
            .setContentText(messageText)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // Для длинных сообщений
        if (messageText.length > 50) {
            notificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
        }

        // Если есть изображение
        imageUrl?.takeIf { it.isNotBlank() }?.let {
            notificationBuilder.setContentText("Photo received")
        }

        // Отображаем уведомление
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    private fun showBasicNotification(title: String, message: String, chatId: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = Random().nextInt(10000)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "basic_channel_id",
                "Basic Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, ChatDetailActivity::class.java).apply {
            putExtra("chatId", chatId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, "basic_channel_id")
            .setSmallIcon(R.drawable.solovei_png)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(notificationId, notificationBuilder.build())
    }
}