package com.example.chatapp.step

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.chatapp.location.LocationUpdateService

class StepCounterRestartReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "StepCounterRestartReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "StepCounterRestartReceiver triggered: ${intent.action}")

        LocationUpdateService.startService(context)

        when (intent.action) {
            "com.example.chatapp.RESTART_STEP_SERVICE",
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_REBOOT -> {
                Log.d(TAG, "Перезапуск сервиса шагомера")
                StepCounterService.startService(context)
            }
        }
    }
}