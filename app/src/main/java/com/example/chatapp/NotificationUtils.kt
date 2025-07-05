package com.example.chatapp.utils

import android.content.Context
import android.util.Base64
import com.example.chatapp.NotificationRequest
import com.example.chatapp.R
import com.example.chatapp.api.RetrofitInstance
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

object NotificationUtils {

    // Используйте реальные значения из панели OneSignal
    private const val ONESIGNAL_APP_ID = "0083de8f-7ca0-4824-ac88-9c037278237e" // Из панели OneSignal
    private const val REST_API_KEY = "os_v2_app_acb55d34ubecjleitqbxe6bdp3z7xfrhf66ujjnmt3y4umujcvwybjkdxn7sinx4hynxgr2xd2rvua7mhce5xrvixdl637vicdof4ca" // Из панели OneSignal

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
                                    "chatId" to chatId,
                                    "senderId" to currentUserId
                                )
                            )

                            LogUtils.d("Sending OneSignal notification: $notification")

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
                                        LogUtils.d("Уведомление успешно отправлено")
                                    } else {
                                        LogUtils.e("Ошибка отправки: ${response.code()}")
                                        response.errorBody()?.string()?.let {
                                            LogUtils.e("Текст ошибки: $it")
                                        }
                                    }
                                }

                                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                                    LogUtils.e("Сетевая ошибка: ${t.message}", t)
                                }
                            })

                        } catch (e: Exception) {
                            LogUtils.e("Ошибка создания уведомления", e)
                        }
                    } else {
                        LogUtils.e("OneSignal ID пуст для пользователя $userId")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    LogUtils.e("Ошибка получения OneSignal ID: ${error.message}")
                }
            })
    }
}