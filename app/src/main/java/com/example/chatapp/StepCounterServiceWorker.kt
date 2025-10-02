package com.example.chatapp

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.chatapp.StepCounterService // Убедитесь, что путь правильный

class StepCounterServiceWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "StepCounterServiceWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "StepCounterServiceWorker запущен")

            val context = applicationContext

            // Создаем Intent для запуска вашего сервиса
            val serviceIntent = Intent(context, StepCounterService::class.java).apply {
                // Убедитесь, что в StepCounterService есть константа ACTION_START или используйте любое другое действие
                // Например: action = StepCounterService.ACTION_START
                // Если такого действия нет, можно не указывать action, просто запустить сервис.
                // Или добавьте константу в StepCounterService, например:
                // companion object { const val ACTION_START = "com.example.chatapp.action.START_STEP_SERVICE" }
                // action = "com.example.chatapp.action.START_STEP_SERVICE" // Используйте вашу константу
            }

            try {
                // Пытаемся запустить сервис
                // StepCounterService, судя по коду, запускается через статический метод startService,
                // который внутри использует startService или startForegroundService.
                // Мы можем вызвать его напрямую.
                StepCounterService.startService(context)
                // Альтернатива: context.startService(serviceIntent) если используете Intent напрямую
                Log.d(TAG, "Запрос на запуск StepCounterService отправлен")
            } catch (e: Exception) {
                Log.w(TAG, "StepCounterServiceWorker: Не удалось запустить StepCounterService. Причина: ${e.message}", e)
                // Возвращаем success(), чтобы WorkManager продолжил планирование
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Критическая ошибка в StepCounterServiceWorker", e)
            Result.success() // Или Result.failure() если хотите остановить повторы
        }
    }
}
