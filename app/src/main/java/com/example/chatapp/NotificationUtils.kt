package com.example.chatapp.utils

import android.content.Context
import android.os.Handler
import android.util.Log
import com.example.chatapp.NotificationRequest
import com.example.chatapp.api.RetrofitInstance
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

object NotificationUtils {

    private const val TAG = "NotificationUtils"

    // Используйте реальные значения из панели OneSignal
    private const val ONESIGNAL_APP_ID = "0083de8f-7ca0-4824-ac88-9c037278237e"

    // ВАЖНО: Обновите на ваш реальный REST API Key из OneSignal
    private const val REST_API_KEY = "YOUR_REST_API_KEY_HERE" // ЗАМЕНИТЕ НА РЕАЛЬНЫЙ КЛЮЧ!

    /**
     * Сохраняет Player ID в Firebase с защитой от дублирования
     */
    fun saveCurrentUserOneSignalIdToDatabase(context: Context) {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUserId.isNullOrBlank()) {
            Log.w(TAG, "Попытка сохранить OneSignal ID для неавторизованного пользователя.")
            return
        }

        try {
            // Отложенная проверка чтобы избежать блокировки
            Handler().postDelayed({
                getOneSignalIdSafely(currentUserId)
            }, 1000)

        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while scheduling OneSignal ID save", e)
        }
    }

    /**
     * Безопасное получение OneSignal ID
     */
    private fun getOneSignalIdSafely(userId: String) {
        try {
            val oneSignalClass = Class.forName("com.onesignal.OneSignal")

            // Пытаемся получить DeviceState (для новых версий OneSignal 4.x)
            try {
                val getDeviceStateMethod = oneSignalClass.getMethod("getDeviceState")
                val deviceState = getDeviceStateMethod.invoke(null)

                if (deviceState != null) {
                    val getUserIdMethod = deviceState.javaClass.getMethod("getUserId")
                    val oneSignalId = getUserIdMethod.invoke(deviceState) as? String

                    if (!oneSignalId.isNullOrEmpty()) {
                        saveIdToFirebase(userId, oneSignalId)
                        return
                    }
                }
            } catch (e1: Exception) {
                Log.d(TAG, "Method getDeviceState failed, trying legacy method")
            }

            // Legacy метод для старых версий
            try {
                val getPermissionSubscriptionStateMethod = oneSignalClass.getMethod("getPermissionSubscriptionState")
                val permissionState = getPermissionSubscriptionStateMethod.invoke(null)

                if (permissionState != null) {
                    val getSubscriptionStatusMethod = permissionState.javaClass.getMethod("getSubscriptionStatus")
                    val subscriptionStatus = getSubscriptionStatusMethod.invoke(permissionState)

                    if (subscriptionStatus != null) {
                        val getUserIdMethod = subscriptionStatus.javaClass.getMethod("getUserId")
                        val oneSignalId = getUserIdMethod.invoke(subscriptionStatus) as? String

                        if (!oneSignalId.isNullOrEmpty()) {
                            saveIdToFirebase(userId, oneSignalId)
                            return
                        }
                    }
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Both OneSignal ID methods failed", e2)
            }

            Log.w(TAG, "Could not retrieve OneSignal ID for user $userId")

        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "OneSignal SDK not found", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while getting OneSignal ID", e)
        }
    }

    /**
     * Сохраняет полученный OneSignal ID в Firebase с проверкой
     */
    private fun saveIdToFirebase(userId: String, oneSignalId: String) {
        val database = FirebaseDatabase.getInstance().reference

        database.child("users").child(userId).child("oneSignalId")
            .get()
            .addOnSuccessListener { snapshot ->
                val currentId = snapshot.getValue(String::class.java)
                if (currentId != oneSignalId) {
                    database.child("users").child(userId).child("oneSignalId")
                        .setValue(oneSignalId)
                        .addOnSuccessListener {
                            Log.d(TAG, "OneSignal ID ($oneSignalId) успешно сохранен для пользователя $userId")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Ошибка сохранения OneSignal ID для пользователя $userId", e)
                        }
                } else {
                    Log.d(TAG, "OneSignal ID для пользователя $userId уже актуален")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Ошибка проверки текущего OneSignal ID", e)
                // Резервная запись
                database.child("users").child(userId).child("oneSignalId")
                    .setValue(oneSignalId)
                    .addOnFailureListener { e2 ->
                        Log.e(TAG, "Резервное сохранение OneSignal ID также не удалось", e2)
                    }
            }
    }

    /**
     * Отправляет философскую цитату с защитой от ошибок
     */
    fun sendPhilosophyQuoteNotification(context: Context) {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Log.e(TAG, "Пользователь не авторизован для отправки философской цитаты")
            return
        }

        // Проверяем API ключ
        if (REST_API_KEY == "YOUR_REST_API_KEY_HERE") {
            Log.e(TAG, "REST_API_KEY не настроен. Уведомление не отправлено.")
            return
        }

        FirebaseDatabase.getInstance().reference
            .child("users").child(currentUserId).child("oneSignalId")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val oneSignalId = snapshot.getValue(String::class.java)
                    if (!oneSignalId.isNullOrBlank()) {
                        sendQuoteNotification(oneSignalId, currentUserId)
                    } else {
                        Log.e(TAG, "OneSignal ID пуст для пользователя $currentUserId")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка получения OneSignal ID: ${error.message}")
                }
            })
    }

    /**
     * Отправка уведомления с цитатой
     */
    private fun sendQuoteNotification(oneSignalId: String, userId: String) {
        try {
            val randomQuote = PhilosophyQuotes.getRandomQuote()
            val authHeader = "key $REST_API_KEY"

            val notification = NotificationRequest(
                app_id = ONESIGNAL_APP_ID,
                include_player_ids = listOf(oneSignalId),
                contents = mapOf("en" to randomQuote.text),
                headings = mapOf("en" to "💭 Мудрая мысль от ${randomQuote.author}"),
                android_channel_id = "292588fb-8a77-4b57-8566-b8bb9552ff68",
                small_icon = "ic_notification",
                data = mapOf(
                    "type" to "philosophy_quote",
                    "author" to randomQuote.author
                )
            )

            Log.d(TAG, "Отправка философской цитаты пользователю $userId")

            val call = RetrofitInstance.oneSignalApi.sendNotification(authHeader, notification)
            call.enqueue(object : Callback<ResponseBody> {
                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Философская цитата успешно отправлена")
                    } else {
                        Log.e(TAG, "Ошибка отправки цитаты: ${response.code()}")
                    }
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    Log.e(TAG, "Сетевая ошибка при отправке цитаты: ${t.message}")
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания уведомления с цитатой", e)
        }
    }

    /**
     * Отправляет уведомление о новом сообщении
     */
    fun sendChatNotification(
        context: Context,
        userId: String,
        messageText: String,
        senderName: String,
        chatId: String
    ) {
        if (REST_API_KEY == "YOUR_REST_API_KEY_HERE") {
            Log.e(TAG, "REST_API_KEY не настроен. Уведомление не отправлено.")
            return
        }

        FirebaseDatabase.getInstance().reference
            .child("users").child(userId).child("oneSignalId")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val oneSignalId = snapshot.getValue(String::class.java)
                    if (!oneSignalId.isNullOrBlank()) {
                        sendChatNotificationInternal(oneSignalId, userId, messageText, senderName, chatId)
                    } else {
                        Log.e(TAG, "OneSignal ID пуст для пользователя $userId")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка получения OneSignal ID: ${error.message}")
                }
            })
    }

    private fun sendChatNotificationInternal(
        oneSignalId: String,
        userId: String,
        messageText: String,
        senderName: String,
        chatId: String
    ) {
        try {
            val authHeader = "key $REST_API_KEY"

            val notification = NotificationRequest(
                app_id = ONESIGNAL_APP_ID,
                include_player_ids = listOf(oneSignalId),
                contents = mapOf("en" to messageText),
                headings = mapOf("en" to "Новое сообщение от $senderName"),
                android_channel_id = "292588fb-8a77-4b57-8566-b8bb9552ff68",
                small_icon = "ic_notification",
                data = mapOf(
                    "type" to "chat_message",
                    "chatId" to chatId,
                    "senderName" to senderName
                )
            )

            Log.d(TAG, "Отправка уведомления о сообщении пользователю $userId")

            val call = RetrofitInstance.oneSignalApi.sendNotification(authHeader, notification)
            call.enqueue(object : Callback<ResponseBody> {
                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Уведомление о сообщении успешно отправлено")
                    } else {
                        Log.e(TAG, "Ошибка отправки уведомления: ${response.code()}")
                    }
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    Log.e(TAG, "Сетевая ошибка при отправке уведомления: ${t.message}")
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания уведомления о сообщении", e)
        }
    }

    /**
     * Отправляет уведомление о приглашении в шахматы
     */
    fun sendChessInvitationNotification(
        context: Context,
        userId: String,
        messageText: String,
        inviterName: String,
        gameId: String
    ) {
        if (REST_API_KEY == "YOUR_REST_API_KEY_HERE") {
            Log.e(TAG, "REST_API_KEY не настроен. Уведомление не отправлено.")
            return
        }

        FirebaseDatabase.getInstance().reference
            .child("users").child(userId).child("oneSignalId")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val oneSignalId = snapshot.getValue(String::class.java)
                    if (!oneSignalId.isNullOrBlank()) {
                        sendChessNotificationInternal(oneSignalId, userId, messageText, inviterName, gameId)
                    } else {
                        Log.e(TAG, "OneSignal ID пуст для пользователя $userId")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка получения OneSignal ID: ${error.message}")
                }
            })
    }

    private fun sendChessNotificationInternal(
        oneSignalId: String,
        userId: String,
        messageText: String,
        inviterName: String,
        gameId: String
    ) {
        try {
            val authHeader = "key $REST_API_KEY"

            val notification = NotificationRequest(
                app_id = ONESIGNAL_APP_ID,
                include_player_ids = listOf(oneSignalId),
                headings = mapOf("en" to "Приглашение в шахматы"),
                contents = mapOf("en" to messageText),
                android_channel_id = "292588fb-8a77-4b57-8566-b8bb9552ff68",
                small_icon = "ic_notification",
                data = mapOf(
                    "type" to "chess_invitation",
                    "gameId" to gameId,
                    "inviterName" to inviterName
                )
            )

            Log.d(TAG, "Отправка уведомления о приглашении пользователю $userId")

            val call = RetrofitInstance.oneSignalApi.sendNotification(authHeader, notification)
            call.enqueue(object : Callback<ResponseBody> {
                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Уведомление о приглашении успешно отправлено")
                    } else {
                        Log.e(TAG, "Ошибка отправки уведомления о приглашении: ${response.code()}")
                    }
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    Log.e(TAG, "Сетевая ошибка при отправке уведомления о приглашении: ${t.message}")
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания уведомления о приглашении", e)
        }
    }
}