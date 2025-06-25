package com.example.chatapp.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object AuthRetrofitInstance {
    private const val AUTH_URL = "https://ngw.devices.sberbank.ru:9443/"

    // В AuthRetrofitInstance.kt
    val authApi: AuthApiService by lazy {
        val client = UnsafeOkHttpClient.create()
        Retrofit.Builder()
            .baseUrl(AUTH_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApiService::class.java)
    }
}