package com.example.chatapp

import android.app.Application
import android.util.Log

class StepCounterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("StepCounter", "Application started")
    }
}
/*
 * Базовый класс приложения.
 * Инициализирует приложение при запуске, логирует событие старта.
 */