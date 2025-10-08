package com.example.chatapp.budilnik

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "ALARM_ACTION" -> startAlarm(context)
            "STOP_ALARM_ACTION" -> stopAlarm(context)
        }
    }

    private fun startAlarm(context: Context) {
        val serviceIntent = Intent(context, AlarmService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun stopAlarm(context: Context) {
        context.stopService(Intent(context, AlarmService::class.java))

        // Дополнительно отправляем интент для закрытия активности
        val activityIntent = Intent(context, AlarmActivity::class.java).apply {
            action = "STOP_ALARM_ACTION"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(activityIntent)
    }
}