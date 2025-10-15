package com.example.chatapp.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.chatapp.location.LocationServiceManager
import com.example.chatapp.step.StepCounterService

class BootCompleteReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootCompleteReceiver"
        private const val DELAY_AFTER_BOOT = 5000L // Уменьшил до 5 секунд
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "BootCompleteReceiver triggered with action: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "Device boot completed, scheduling services restart...")

                // Запускаем немедленно, без задержки
                restartServicesAfterBoot(context)
            }
        }
    }

    private fun restartServicesAfterBoot(context: Context) {
        try {
            Log.d(TAG, "=== STARTING SERVICES AFTER BOOT ===")

            // Запускаем сервисы параллельно и немедленно
            restartStepService(context)
            checkAndRestartLocationService(context)

            Log.d(TAG, "=== SERVICES RESTART INITIATED ===")

        } catch (e: Exception) {
            Log.e(TAG, "Error in restartServicesAfterBoot", e)
        }
    }

    private fun checkAndRestartLocationService(context: Context) {
        // Вместо проверки SharedPreferences, сразу запускаем если нужно
        // Проверка будет внутри сервиса
        restartLocationService(context)
    }

    private fun restartLocationService(context: Context) {
        try {
            Log.d(TAG, "Starting location service after boot...")
            LocationServiceManager.startLocationService(context)
            Log.d(TAG, "Location service start command sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location service after boot", e)
        }
    }

    private fun restartStepService(context: Context) {
        try {
            Log.d(TAG, "Starting step service after boot...")
            StepCounterService.startService(context)
            Log.d(TAG, "Step service start command sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start step service after boot", e)
        }
    }
}