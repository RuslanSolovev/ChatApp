package com.example.chatapp.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitInstance {
    private const val BASE_URL = "https://gigachat.devices.sberbank.ru/"

    // В RetrofitInstance.kt
    val api: GigaChatApiService by lazy {
        val client = UnsafeOkHttpClient.create()
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GigaChatApiService::class.java)
    }
}