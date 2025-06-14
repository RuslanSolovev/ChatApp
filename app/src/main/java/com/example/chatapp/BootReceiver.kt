
package com.example.chatapp.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.chatapp.StepCounterService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.d("StepCounter", "Device booted, scheduling service start")

            // Задержка для стабильности системы
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d("StepCounter", "Starting service after boot")
                StepCounterService.startService(context)
            }, 30000) // 30 секунд задержки
        }
    }
}
/*
 * Receiver для автоматического запуска сервиса после перезагрузки устройства.
 * Функционал:
 *  - Реагирует на событие завершения загрузки системы
 *  - Запускает StepCounterService с задержкой 30 секунд
 *  - Обеспечивает работу шагомера без ручного запуска приложения
 */