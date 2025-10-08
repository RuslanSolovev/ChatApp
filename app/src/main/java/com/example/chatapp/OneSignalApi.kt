package com.example.chatapp.api

import com.example.chatapp.NotificationRequest
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OneSignalApi {
    @POST("notifications")
    fun sendNotification(
        @Header("Authorization") authHeader: String,
        @Body notification: NotificationRequest
    ): Call<ResponseBody>
}