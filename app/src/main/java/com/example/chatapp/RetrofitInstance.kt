package com.example.chatapp.api


import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitInstance {

    private const val BASE_URL_GIGA = "https://gigachat.devices.sberbank.ru/"
    private const val ONE_SIGNAL_BASE_URL = "https://api.onesignal.com/"

    // GigaChat API
    val api: GigaChatApiService by lazy {
        val client = UnsafeOkHttpClient.create()
        Retrofit.Builder()
            .baseUrl(BASE_URL_GIGA)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GigaChatApiService::class.java)
    }


    // Клиент для OneSignal
    private val oneSignalClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }



    // Retrofit для OneSignal
    private val oneSignalRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(ONE_SIGNAL_BASE_URL)
            .client(oneSignalClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }



    val oneSignalApi: OneSignalApi by lazy {
        oneSignalRetrofit.create(OneSignalApi::class.java)
    }
}

