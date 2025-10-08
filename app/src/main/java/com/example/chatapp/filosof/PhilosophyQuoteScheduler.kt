package com.example.chatapp.utils

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class PhilosophyQuoteWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        // Отправляем философскую цитату
        NotificationUtils.sendPhilosophyQuoteNotification(applicationContext)
        return Result.success()
    }
}

