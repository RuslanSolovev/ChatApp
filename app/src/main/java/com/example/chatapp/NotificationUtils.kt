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

    // ⚠️ ОБНОВЛЕННЫЙ ПРАВИЛЬНЫЙ APP ID
    private const val ONESIGNAL_APP_ID = "0083de8f-7ca0-4824-ac88-9c037278237e"

    // ⚠️ ОБНОВЛЕННЫЙ ПРАВИЛЬНЫЙ REST API KEY
    private const val REST_API_KEY = "os_v2_app_acb55d34ubecjleitqbxe6bdpzgl3ejyyjfu2em64r2vsnypzjosk4x4zz4ymvanhwxm6bwqiglyzyaslkrcurm2f5oxe5huvssdsdq"

    /**
     * Проверка конфигурации OneSignal
     */
    fun isOneSignalConfigured(): Boolean {
        val isConfigured = REST_API_KEY.isNotBlank() &&
                ONESIGNAL_APP_ID.isNotBlank() &&
                ONESIGNAL_APP_ID.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))

        if (!isConfigured) {
            Log.e(TAG, "❌ OneSignal не настроен. Проверьте ключи!")
            Log.e(TAG, "   REST_API_KEY: ${if (REST_API_KEY.isNotBlank()) "✅ установлен" else "❌ пустой"}")
            Log.e(TAG, "   ONESIGNAL_APP_ID: $ONESIGNAL_APP_ID")
            Log.e(TAG, "   Формат App ID: ${if (ONESIGNAL_APP_ID.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) "✅ правильный" else "❌ неправильный"}")
        } else {
            Log.d(TAG, "✅ OneSignal настроен корректно")
            Log.d(TAG, "   App ID: ${ONESIGNAL_APP_ID.take(8)}...")
            Log.d(TAG, "   REST API Key: ${REST_API_KEY.take(8)}...")
        }

        return isConfigured
    }

    /**
     * Сохраняет Player ID в Firebase с защитой от дублирования
     */
    fun saveCurrentUserOneSignalIdToDatabase(context: Context) {
        if (!isOneSignalConfigured()) {
            Log.e(TAG, "❌ OneSignal не настроен. Пропускаем сохранение OneSignal ID.")
            return
        }

        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUserId.isNullOrBlank()) {
            Log.w(TAG, "⚠️ Попытка сохранить OneSignal ID для неавторизованного пользователя.")
            return
        }

        try {
            Log.d(TAG, "💾 Начинаем сохранение OneSignal ID для пользователя: ${currentUserId.take(8)}...")

            // Отложенная проверка чтобы избежать блокировки
            Handler().postDelayed({
                getOneSignalIdSafely(currentUserId)
            }, 1000)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected error while scheduling OneSignal ID save", e)
        }
    }

    /**
     * Безопасное получение OneSignal ID
     */
    private fun getOneSignalIdSafely(userId: String) {
        try {
            Log.d(TAG, "🔍 Получение OneSignal ID для пользователя: ${userId.take(8)}...")

            val oneSignalClass = Class.forName("com.onesignal.OneSignal")

            // Пытаемся получить DeviceState (для новых версий OneSignal 4.x)
            try {
                val getDeviceStateMethod = oneSignalClass.getMethod("getDeviceState")
                val deviceState = getDeviceStateMethod.invoke(null)

                if (deviceState != null) {
                    val getUserIdMethod = deviceState.javaClass.getMethod("getUserId")
                    val oneSignalId = getUserIdMethod.invoke(deviceState) as? String

                    if (!oneSignalId.isNullOrEmpty()) {
                        Log.d(TAG, "✅ OneSignal ID получен через getDeviceState: ${oneSignalId.take(8)}...")
                        saveIdToFirebase(userId, oneSignalId)
                        return
                    }
                }
            } catch (e1: Exception) {
                Log.d(TAG, "⚠️ Method getDeviceState failed, trying legacy method: ${e1.message}")
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
                            Log.d(TAG, "✅ OneSignal ID получен через legacy method: ${oneSignalId.take(8)}...")
                            saveIdToFirebase(userId, oneSignalId)
                            return
                        }
                    }
                }
            } catch (e2: Exception) {
                Log.e(TAG, "❌ Both OneSignal ID methods failed: ${e2.message}")
            }

            Log.w(TAG, "⚠️ Could not retrieve OneSignal ID for user ${userId.take(8)}...")

        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "❌ OneSignal SDK not found", e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected error while getting OneSignal ID", e)
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
                            Log.d(TAG, "✅ OneSignal ID (${oneSignalId.take(8)}...) успешно сохранен для пользователя ${userId.take(8)}...")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "❌ Ошибка сохранения OneSignal ID для пользователя ${userId.take(8)}...", e)
                        }
                } else {
                    Log.d(TAG, "ℹ️ OneSignal ID для пользователя ${userId.take(8)}... уже актуален")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Ошибка проверки текущего OneSignal ID", e)
                // Резервная запись
                database.child("users").child(userId).child("oneSignalId")
                    .setValue(oneSignalId)
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ OneSignal ID сохранен через резервный метод")
                    }
                    .addOnFailureListener { e2 ->
                        Log.e(TAG, "❌ Резервное сохранение OneSignal ID также не удалось", e2)
                    }
            }
    }

    /**
     * Отправляет философскую цитату с защитой от ошибок
     */
    fun sendPhilosophyQuoteNotification(context: Context) {
        // Проверяем конфигурацию
        if (!isOneSignalConfigured()) {
            Log.e(TAG, "❌ OneSignal не настроен. Пропускаем отправку цитаты.")
            return
        }

        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Log.e(TAG, "❌ Пользователь не авторизован для отправки философской цитаты")
            return
        }

        Log.d(TAG, "📖 Отправка философской цитаты для пользователя: ${currentUserId.take(8)}...")

        FirebaseDatabase.getInstance().reference
            .child("users").child(currentUserId).child("oneSignalId")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val oneSignalId = snapshot.getValue(String::class.java)
                    if (!oneSignalId.isNullOrBlank()) {
                        sendQuoteNotification(oneSignalId, currentUserId)
                    } else {
                        Log.e(TAG, "❌ OneSignal ID пуст для пользователя ${currentUserId.take(8)}...")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "❌ Ошибка получения OneSignal ID: ${error.message}")
                }
            })
    }

    /**
     * Отправка уведомления с цитатой
     */
    private fun sendQuoteNotification(oneSignalId: String, userId: String) {
        try {
            val randomQuote = PhilosophyQuotes.getRandomQuote()

            // ВАЖНО: Используйте "Basic" перед ключом
            val authHeader = "Basic $REST_API_KEY"

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

            Log.d(TAG, "📤 Отправка философской цитаты пользователю ${userId.take(8)}...")
            Log.d(TAG, "   Player ID: ${oneSignalId.take(8)}...")
            Log.d(TAG, "   App ID: ${ONESIGNAL_APP_ID.take(8)}...")

            val call = RetrofitInstance.oneSignalApi.sendNotification(authHeader, notification)
            call.enqueue(object : Callback<ResponseBody> {
                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "✅ Философская цитата успешно отправлена в OneSignal")
                        // Логируем полный ответ для диагностики
                        try {
                            val responseBody = response.body()?.string()
                            Log.d(TAG, "📨 Ответ OneSignal: $responseBody")
                        } catch (e: Exception) {
                            Log.d(TAG, "📨 Ответ OneSignal получен (без тела)")
                        }
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "Unknown error"
                        Log.e(TAG, "❌ Ошибка отправки цитаты: ${response.code()} - $errorBody")

                        // Подробная диагностика ошибок
                        when (response.code()) {
                            400 -> Log.e(TAG, "❌ Неверный запрос - проверьте формат данных")
                            403 -> Log.e(TAG, "❌ Неверный REST API Key")
                            404 -> Log.e(TAG, "❌ App ID не найден")
                            500 -> Log.e(TAG, "❌ Ошибка сервера OneSignal")
                            else -> Log.e(TAG, "❌ Неизвестная ошибка HTTP: ${response.code()}")
                        }
                    }
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    Log.e(TAG, "🌐 Сетевая ошибка при отправке цитаты: ${t.message}")
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка создания уведомления с цитатой", e)
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
        if (!isOneSignalConfigured()) {
            Log.e(TAG, "❌ OneSignal не настроен. Уведомление не отправлено.")
            return
        }

        Log.d(TAG, "💬 Отправка уведомления о сообщении пользователю: ${userId.take(8)}...")

        FirebaseDatabase.getInstance().reference
            .child("users").child(userId).child("oneSignalId")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val oneSignalId = snapshot.getValue(String::class.java)
                    if (!oneSignalId.isNullOrBlank()) {
                        Log.d(TAG, "✅ У пользователя ${userId.take(8)}... есть OneSignal ID: ${oneSignalId.take(8)}...")
                        sendChatNotificationInternal(oneSignalId, userId, messageText, senderName, chatId)
                    } else {
                        Log.e(TAG, "❌ OneSignal ID пуст для пользователя ${userId.take(8)}...")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "❌ Ошибка получения OneSignal ID: ${error.message}")
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
            val authHeader = "Basic $REST_API_KEY"

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

            Log.d(TAG, "📤 Отправка уведомления о сообщении пользователю ${userId.take(8)}...")
            Log.d(TAG, "   Player ID: ${oneSignalId.take(8)}...")
            Log.d(TAG, "   App ID: ${ONESIGNAL_APP_ID.take(8)}...")
            Log.d(TAG, "   Сообщение: $messageText")
            Log.d(TAG, "   Отправитель: $senderName")
            Log.d(TAG, "   Chat ID: $chatId")

            val call = RetrofitInstance.oneSignalApi.sendNotification(authHeader, notification)
            call.enqueue(object : Callback<ResponseBody> {
                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "✅ Уведомление о сообщении успешно отправлено в OneSignal")
                        // Логируем полный ответ для диагностики
                        try {
                            val responseBody = response.body()?.string()
                            Log.d(TAG, "📨 Ответ OneSignal: $responseBody")
                        } catch (e: Exception) {
                            Log.d(TAG, "📨 Ответ OneSignal получен (без тела)")
                        }
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "Unknown error"
                        Log.e(TAG, "❌ Ошибка отправки уведомления: ${response.code()} - $errorBody")

                        // Подробная диагностика ошибок
                        when (response.code()) {
                            400 -> {
                                Log.e(TAG, "❌ Неверный запрос - проверьте:")
                                Log.e(TAG, "   - App ID: $ONESIGNAL_APP_ID")
                                Log.e(TAG, "   - REST API Key: ${REST_API_KEY.take(8)}...")
                                Log.e(TAG, "   - Player ID: ${oneSignalId.take(8)}...")
                            }
                            403 -> Log.e(TAG, "❌ Неверный REST API Key")
                            404 -> Log.e(TAG, "❌ App ID не найден")
                            500 -> Log.e(TAG, "❌ Ошибка сервера OneSignal")
                            else -> Log.e(TAG, "❌ Неизвестная ошибка HTTP: ${response.code()}")
                        }
                    }
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    Log.e(TAG, "🌐 Сетевая ошибка при отправке уведомления: ${t.message}")
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка создания уведомления о сообщении", e)
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
        if (!isOneSignalConfigured()) {
            Log.e(TAG, "❌ OneSignal не настроен. Уведомление не отправлено.")
            return
        }

        Log.d(TAG, "♟️ Отправка уведомления о приглашении пользователю: ${userId.take(8)}...")

        FirebaseDatabase.getInstance().reference
            .child("users").child(userId).child("oneSignalId")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val oneSignalId = snapshot.getValue(String::class.java)
                    if (!oneSignalId.isNullOrBlank()) {
                        sendChessNotificationInternal(oneSignalId, userId, messageText, inviterName, gameId)
                    } else {
                        Log.e(TAG, "❌ OneSignal ID пуст для пользователя ${userId.take(8)}...")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "❌ Ошибка получения OneSignal ID: ${error.message}")
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
            val authHeader = "Basic $REST_API_KEY"

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

            Log.d(TAG, "📤 Отправка уведомления о приглашении пользователю ${userId.take(8)}...")
            Log.d(TAG, "   Player ID: ${oneSignalId.take(8)}...")
            Log.d(TAG, "   App ID: ${ONESIGNAL_APP_ID.take(8)}...")

            val call = RetrofitInstance.oneSignalApi.sendNotification(authHeader, notification)
            call.enqueue(object : Callback<ResponseBody> {
                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "✅ Уведомление о приглашении успешно отправлено")
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "Unknown error"
                        Log.e(TAG, "❌ Ошибка отправки уведомления о приглашении: ${response.code()} - $errorBody")
                    }
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    Log.e(TAG, "🌐 Сетевая ошибка при отправке уведомления о приглашении: ${t.message}")
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка создания уведомления о приглашении", e)
        }
    }

    /**
     * Тестовый метод для проверки уведомлений
     */
    fun sendTestNotification(context: Context, targetUserId: String) {
        Log.d(TAG, "🧪 ОТПРАВКА ТЕСТОВОГО УВЕДОМЛЕНИЯ")

        if (!isOneSignalConfigured()) {
            Log.e(TAG, "🧪 OneSignal не настроен для теста")
            return
        }

        FirebaseDatabase.getInstance().reference
            .child("users").child(targetUserId).child("oneSignalId")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val oneSignalId = snapshot.getValue(String::class.java)
                    if (!oneSignalId.isNullOrBlank()) {
                        Log.d(TAG, "🧪 Тестовое уведомление для Player ID: ${oneSignalId.take(8)}...")

                        // Отправляем тестовое уведомление
                        sendChatNotificationInternal(
                            oneSignalId,
                            targetUserId,
                            "🧪 Тестовое сообщение",
                            "Test User",
                            "test_chat"
                        )
                    } else {
                        Log.e(TAG, "🧪 ❌ Нет OneSignal ID для тестового уведомления")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "🧪 ❌ Ошибка получения OneSignal ID для теста", error.toException())
                }
            })
    }
}