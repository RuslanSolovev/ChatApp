package com.example.chatapp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.chatapp.location.LocationUpdateService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

class LocationServiceWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "LocationServiceWorker"
        const val LOCATION_SERVICE_INTERVAL_HOURS = 2L
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "LocationServiceWorker запущен")

            val context = applicationContext

            // Проверяем, авторизован ли пользователь
            val auth = FirebaseAuth.getInstance()
            val userId = auth.currentUser?.uid ?: run {
                Log.w(TAG, "Пользователь не авторизован, пропускаем запуск сервиса")
                return@doWork Result.success()
            }

            // Проверяем настройки локации
            val database = FirebaseDatabase.getInstance().reference
            val settingsSnapshot = database.child("location_settings").child(userId).get().await()
            val settings = settingsSnapshot.getValue(com.example.chatapp.models.LocationSettings::class.java)
            if (settings?.enabled != true) {
                Log.d(TAG, "Сервис локации отключен в настройках")
                return@doWork Result.success()
            }

            // Создаем Intent для запуска сервиса
            val serviceIntent = Intent(context, LocationUpdateService::class.java).apply {
                action = LocationUpdateService.ACTION_START
            }

            try {
                // Запускаем сервис
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "LocationUpdateService успешно запущен")
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось запустить LocationUpdateService", e)
                return@doWork Result.retry() // Повторить попытку позже
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Критическая ошибка в LocationServiceWorker", e)
            Result.retry() // Повторить попытку позже
        }
    }
}