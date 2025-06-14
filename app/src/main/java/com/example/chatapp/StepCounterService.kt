
package com.example.chatapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*

class StepCounterService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var prefs: SharedPreferences
    private var lastSensorTimestamp = 0L
    private var initialSteps = 0f
    private var lastTotalSteps = 0f

    companion object {
        private const val NOTIFICATION_ID = 12345
        private const val CHANNEL_ID = "step_counter_channel"
        const val ACTION_STEPS_UPDATED = "com.example.chatapp.ACTION_STEPS_UPDATED"

        fun startService(context: Context) {
            val intent = Intent(context, StepCounterService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepCounterSensor == null) {
            Log.e("StepCounter", "Step counter sensor not available")
            stopSelf()
        } else {
            sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d("StepCounter", "Step counter sensor registered")
        }

        // Загрузка сохраненных значений
        initialSteps = prefs.getFloat("initial_step_count", 0f)
        lastTotalSteps = prefs.getFloat("last_total_steps", 0f)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Step Counter",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Counting your steps"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Step Counter")
            .setContentText("Counting your steps")
            .setSmallIcon(R.drawable.ic_walk)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                val now = System.currentTimeMillis()
                // Защита от слишком частых обновлений (не чаще 1 раза в секунду)
                if (now - lastSensorTimestamp > 1000) {
                    processSteps(it.values[0])
                    lastSensorTimestamp = now
                }
            }
        }
    }

    private fun processSteps(totalSteps: Float) {
        val lastBoot = prefs.getLong("last_boot_time", 0)
        val bootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

        // Сброс при первом запуске или после перезагрузки
        if (initialSteps == 0f || bootTime > lastBoot) {
            Log.d("StepCounter", "Initializing step counter: $totalSteps")
            initialSteps = totalSteps
            lastTotalSteps = totalSteps

            prefs.edit().apply {
                putFloat("initial_step_count", totalSteps)
                putFloat("last_total_steps", totalSteps)
                putLong("last_boot_time", bootTime)
                apply()
            }
            return
        }

        // Нормальное обновление шагов
        val diff = totalSteps - lastTotalSteps
        if (diff > 0) {
            addStepsToStats(diff.toInt())
            lastTotalSteps = totalSteps
            prefs.edit().putFloat("last_total_steps", totalSteps).apply()
        }
    }

    private fun addStepsToStats(steps: Int) {
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val todaySteps = prefs.getInt(todayKey, 0) + steps

        // Получаем текущее максимальное значение ДО редактирования
        val maxStepsKey = "max_steps_30days"
        val currentMax = prefs.getInt(maxStepsKey, 0)
        val newMax = if (todaySteps > currentMax) todaySteps else currentMax

        prefs.edit().apply {
            putInt(todayKey, todaySteps)
            putInt(maxStepsKey, newMax)
            apply()
        }

        syncWithFirebase(todayKey, todaySteps)
        notifyUiUpdate(todayKey, todaySteps)
    }

    private fun syncWithFirebase(date: String, steps: Int) {
        FirebaseAuth.getInstance().currentUser?.let { user ->
            FirebaseDatabase.getInstance().reference
                .child("users")
                .child(user.uid)
                .child("stepsData")
                .child(date)
                .setValue(steps)
                .addOnSuccessListener {
                    Log.d("StepCounter", "Steps synced: $steps on $date")
                }
        }
    }

    private fun notifyUiUpdate(date: String, steps: Int) {
        sendBroadcast(Intent(ACTION_STEPS_UPDATED).apply {
            putExtra("date", date)
            putExtra("steps", steps)
        })
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        Log.d("StepCounter", "Service destroyed")
    }
}
/*
 * Фоновый сервис для подсчета шагов.
 * Функционал:
 *  - Работает как Foreground Service с постоянным уведомлением
 *  - Регистрирует датчик шагов для получения данных
 *  - Обрабатывает сброс счетчика после перезагрузки
 *  - Сохраняет данные в SharedPreferences
 *  - Синхронизирует шаги с Firebase в реальном времени
 *  - Отправляет Broadcast для обновления UI
 * 
 * Улучшения:
 *  - Корректная обработка перезагрузки устройства
 *  - Оптимизировано обновление данных (не чаще 1 раза в секунду)
 *  - Логирование ключевых событий
 */