package com.example.chatapp.utils

import android.content.Context
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
    private const val ONESIGNAL_APP_ID = "0083de8f-7ca0-4824-ac88-9c037278237e" // Из панели OneSignal
    // ОБЯЗАТЕЛЬНО обновите на ваш НОВЫЙ ключ!
    private const val REST_API_KEY = "os_v2_app_acb55d34ubecjleitqbxe6bdp3mc7ojm7hjujbeirfw6zvgcpcsks5bjjiq7xcwuqkqg4ma2abp3nzdfcvjjwibtirx6d4vgr4wmyya"

    /**
     * Получает Player ID от OneSignal SDK и сохраняет его в Firebase Realtime Database
     * для текущего авторизованного пользователя.
     * Должен вызываться после успешной инициализации OneSignal и Firebase Auth.
     */
    fun saveCurrentUserOneSignalIdToDatabase(context: Context) {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUserId.isNullOrBlank()) {
            Log.w(TAG, "Попытка сохранить OneSignal ID для неавторизованного пользователя.")
            return
        }

        try {
            // Проверяем, доступен ли класс OneSignal (на случай, если SDK не подключен правильно)
            val oneSignalClass = Class.forName("com.onesignal.OneSignal")

            // Пытаемся получить DeviceState (для новых версий OneSignal 4.x)
            try {
                val getDeviceStateMethod = oneSignalClass.getMethod("getDeviceState")
                val deviceState = getDeviceStateMethod.invoke(null)

                if (deviceState != null) {
                    val getUserIdMethod = deviceState.javaClass.getMethod("getUserId")
                    val oneSignalId = getUserIdMethod.invoke(deviceState) as? String

                    if (!oneSignalId.isNullOrEmpty()) {
                        saveIdToFirebase(currentUserId, oneSignalId)
                        return
                    } else {
                        Log.w(TAG, "OneSignal UserId is null or empty for user $currentUserId")
                    }
                } else {
                    Log.w(TAG, "OneSignal DeviceState is null for user $currentUserId")
                }
            } catch (e1: Exception) {
                Log.d(TAG, "Method getDeviceState not found or failed for user $currentUserId, trying getPermissionSubscriptionState", e1)

                // Пытаемся получить PermissionSubscriptionState (для старых версий OneSignal 3.x)
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
                                saveIdToFirebase(currentUserId, oneSignalId)
                                return
                            } else {
                                Log.w(TAG, "OneSignal UserId from SubscriptionStatus is null or empty for user $currentUserId")
                            }
                        } else {
                            Log.w(TAG, "OneSignal SubscriptionStatus is null for user $currentUserId")
                        }
                    } else {
                        Log.w(TAG, "OneSignal PermissionSubscriptionState is null for user $currentUserId")
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to get OneSignal ID using both methods for user $currentUserId", e2)
                }
            }
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "OneSignal SDK not found. Please make sure it's added to your project dependencies.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while getting OneSignal ID for user $currentUserId", e)
        }
    }

    /**
     * Сохраняет полученный OneSignal ID в Firebase.
     */
    private fun saveIdToFirebase(userId: String, oneSignalId: String) {
        val database = FirebaseDatabase.getInstance().reference
        // Проверяем, отличается ли новый ID от существующего
        database.child("users").child(userId).child("oneSignalId")
            .get()
            .addOnSuccessListener { snapshot ->
                val currentId = snapshot.getValue(String::class.java)
                if (currentId != oneSignalId) {
                    // Если ID новый или отличается, обновляем его в базе данных
                    database.child("users").child(userId).child("oneSignalId")
                        .setValue(oneSignalId)
                        .addOnSuccessListener {
                            Log.d(TAG, "OneSignal ID ($oneSignalId) успешно сохранен для пользователя $userId")
                            LogUtils.d("OneSignal ID ($oneSignalId) успешно сохранен для пользователя $userId")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Ошибка сохранения OneSignal ID для пользователя $userId", e)
                            LogUtils.e("Ошибка сохранения OneSignal ID для пользователя $userId", e)
                        }
                } else {
                    Log.d(TAG, "OneSignal ID для пользователя $userId уже актуален ($oneSignalId)")
                    LogUtils.d("OneSignal ID для пользователя $userId уже актуален ($oneSignalId)")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Ошибка проверки текущего OneSignal ID для пользователя $userId", e)
                LogUtils.e("Ошибка проверки текущего OneSignal ID для пользователя $userId", e)
                // В случае ошибки чтения все равно пытаемся записать
                database.child("users").child(userId).child("oneSignalId")
                    .setValue(oneSignalId)
                    .addOnFailureListener { e2 ->
                        Log.e(TAG, "Ошибка сохранения OneSignal ID (резервная попытка) для пользователя $userId", e2)
                        LogUtils.e("Ошибка сохранения OneSignal ID (резервная попытка) для пользователя $userId", e2)
                    }
            }
    }

    /**
     * Отправляет push-уведомление о новом сообщении в чате через OneSignal API.
     *
     * @param context Контекст приложения.
     * @param userId UserID получателя уведомления.
     * @param messageText Основной текст уведомления (текст сообщения).
     * @param senderName Имя отправителя сообщения.
     * @param chatId ID чата (передается в данных уведомления).
     */
    fun sendChatNotification(
        context: Context,
        userId: String,
        messageText: String,
        senderName: String,
        chatId: String
    ) {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        FirebaseDatabase.getInstance().reference
            .child("users").child(userId).child("oneSignalId")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val oneSignalId = snapshot.getValue(String::class.java)
                    if (!oneSignalId.isNullOrBlank()) {
                        try {
                            val authHeader = "key $REST_API_KEY"

                            val notification = NotificationRequest(
                                app_id = ONESIGNAL_APP_ID,
                                include_player_ids = listOf(oneSignalId),
                                contents = mapOf("en" to messageText),
                                headings = mapOf("en" to "Новое сообщение от $senderName"),
                                android_channel_id = "292588fb-8a77-4b57-8566-b8bb9552ff68",
                                small_icon = "res://drawable/ic_notification", // Используйте ресурс Android
                                data = mapOf(
                                    "type" to "chat_message", // Тип уведомления
                                    "chatId" to chatId,
                                    "senderId" to currentUserId,
                                    "senderName" to senderName
                                )
                            )

                            LogUtils.d("Sending OneSignal notification to $userId ($oneSignalId): $notification")

                            val call = RetrofitInstance.oneSignalApi.sendNotification(
                                authHeader,
                                notification
                            )
                            call.enqueue(object : Callback<ResponseBody> {
                                override fun onResponse(
                                    call: Call<ResponseBody>,
                                    response: Response<ResponseBody>
                                ) {
                                    if (response.isSuccessful) {
                                        LogUtils.d("Уведомление о сообщении успешно отправлено пользователю $userId ($oneSignalId)")
                                    } else {
                                        LogUtils.e("Ошибка отправки уведомления о сообщении пользователю $userId ($oneSignalId): ${response.code()}")
                                        response.errorBody()?.string()?.let {
                                            LogUtils.e("Текст ошибки: $it")
                                        }
                                    }
                                }

                                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                                    LogUtils.e("Сетевая ошибка при отправке уведомления о сообщении пользователю $userId ($oneSignalId): ${t.message}", t)
                                }
                            })

                        } catch (e: Exception) {
                            LogUtils.e("Ошибка создания уведомления о сообщении для пользователя $userId", e)
                        }
                    } else {
                        LogUtils.e("OneSignal ID пуст для пользователя $userId. Уведомление о сообщении не отправлено.")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    LogUtils.e("Ошибка получения OneSignal ID для пользователя $userId при отправке сообщения: ${error.message}")
                }
            })
    }

    /**
     * Отправляет push-уведомление о приглашении в шахматную игру через OneSignal API.
     *
     * @param context Контекст приложения.
     * @param userId UserID получателя уведомления (приглашенного игрока).
     * @param messageText Основной текст уведомления.
     * @param inviterName Имя пользователя, отправившего приглашение.
     * @param gameId ID шахматной игры (для передачи в данные уведомления).
     */
    fun sendChessInvitationNotification(
        context: Context,
        userId: String,
        messageText: String,
        inviterName: String,
        gameId: String
    ) {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // Получаем oneSignalId получателя из Firebase
        FirebaseDatabase.getInstance().reference
            .child("users").child(userId).child("oneSignalId")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val oneSignalId = snapshot.getValue(String::class.java)
                    if (!oneSignalId.isNullOrBlank()) {
                        try {
                            // Формируем заголовок Authorization
                            val authHeader = "key $REST_API_KEY"

                            // Создаем объект запроса уведомления
                            val notification = NotificationRequest(
                                app_id = ONESIGNAL_APP_ID,
                                include_player_ids = listOf(oneSignalId), // Отправляем конкретному пользователю
                                headings = mapOf("en" to "Приглашение в шахматы"), // Заголовок
                                contents = mapOf("en" to messageText), // Основной текст
                                // Можно настроить другой канал, если нужно
                                android_channel_id = "292588fb-8a77-4b57-8566-b8bb9552ff68", // Используйте существующий или создайте новый в OneSignal
                                small_icon = "res://drawable/ic_notification", // Используйте ресурс Android
                                // Добавляем данные, которые можно будет получить при открытии уведомления
                                data = mapOf(
                                    "type" to "chess_invitation", // Тип уведомления
                                    "gameId" to gameId,
                                    "inviterId" to currentUserId,
                                    "inviterName" to inviterName
                                )
                                // Опционально: можно добавить кнопки "Принять"/"Отклонить" через action buttons
                                // buttons = listOf(
                                //     mapOf("id" to "accept", "text" to "Принять", "icon" to "res://drawable/ic_check"),
                                //     mapOf("id" to "decline", "text" to "Отклонить", "icon" to "res://drawable/ic_close")
                                // )
                            )

                            LogUtils.d("Отправка OneSignal уведомления о приглашении пользователю $userId ($oneSignalId): $notification")

                            // Отправляем запрос через Retrofit
                            val call = RetrofitInstance.oneSignalApi.sendNotification(
                                authHeader,
                                notification
                            )
                            call.enqueue(object : Callback<ResponseBody> {
                                override fun onResponse(
                                    call: Call<ResponseBody>,
                                    response: Response<ResponseBody>
                                ) {
                                    if (response.isSuccessful) {
                                        LogUtils.d("Уведомление о приглашении успешно отправлено пользователю $userId ($oneSignalId)")
                                    } else {
                                        LogUtils.e("Ошибка отправки уведомления о приглашении пользователю $userId ($oneSignalId): ${response.code()}")
                                        response.errorBody()?.string()?.let {
                                            LogUtils.e("Текст ошибки: $it")
                                        }
                                    }
                                }

                                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                                    LogUtils.e("Сетевая ошибка при отправке уведомления о приглашении пользователю $userId ($oneSignalId): ${t.message}", t)
                                }
                            })

                        } catch (e: Exception) {
                            LogUtils.e("Ошибка создания уведомления о приглашении для пользователя $userId", e)
                        }
                    } else {
                        // oneSignalId отсутствует или пуст
                        LogUtils.e("OneSignal ID пуст для пользователя $userId. Уведомление о приглашении не отправлено.")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    LogUtils.e("Ошибка получения OneSignal ID для пользователя $userId при отправке приглашения: ${error.message}")
                }
            })
    }
}