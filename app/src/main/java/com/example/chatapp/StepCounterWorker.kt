package com.example.chatapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*

class StepCounterWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var stepsDetected = 0f
    private var initialSteps = 0f

    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()

    companion object {
        private const val TAG = "StepCounterWorker"
        private const val SENSOR_TIMEOUT = 15000L // 15 секунд
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "StepCounterWorker started for Android 12+")
            startStepCounter()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "StepCounterWorker failed: ${e.message}")
            // Для Android 12+ более агрессивный retry
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Result.retry()
            } else {
                Result.success()
            }
        }
    }

    private fun startStepCounter() {
        sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepCounterSensor == null) {
            Log.w(TAG, "Step counter sensor not available")
            return
        }

        // Восстанавливаем предыдущее состояние
        val prefs = applicationContext.getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
        initialSteps = prefs.getFloat("last_total_steps", 0f)

        // Регистрируем слушатель
        val success = sensorManager.registerListener(
            this,
            stepCounterSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )

        if (success) {
            Log.d(TAG, "Step sensor registered successfully")

            // Автоматически отписываемся через таймаут
            Handler(Looper.getMainLooper()).postDelayed({
                sensorManager.unregisterListener(this)
                Log.d(TAG, "Step sensor unregistered after timeout")
            }, SENSOR_TIMEOUT)
        } else {
            Log.e(TAG, "Failed to register step sensor")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let { sensorEvent ->
            if (sensorEvent.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                val totalSteps = sensorEvent.values[0]

                if (initialSteps == 0f) {
                    // Первое получение - сохраняем как начальное значение
                    initialSteps = totalSteps
                    stepsDetected = 0f
                } else {
                    // Вычисляем новые шаги
                    val newSteps = totalSteps - initialSteps
                    if (newSteps > 0) {
                        stepsDetected = newSteps
                        Log.d(TAG, "New steps detected: $newSteps")
                        saveStepsToFirebase(newSteps.toInt())
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Не требуется
    }

    private fun saveStepsToFirebase(newSteps: Int) {
        try {
            val userId = auth.currentUser?.uid
            if (userId == null) {
                Log.w(TAG, "User not authenticated")
                return
            }

            val todayDateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

            // Получаем текущие шаги за день
            database.child("users").child(userId).child("stepsData").child(todayDateKey)
                .get()
                .addOnSuccessListener { snapshot ->
                    val currentSteps = snapshot.getValue(Int::class.java) ?: 0
                    val totalSteps = currentSteps + newSteps

                    // Сохраняем обновленное количество шагов
                    database.child("users").child(userId).child("stepsData").child(todayDateKey)
                        .setValue(totalSteps)
                        .addOnSuccessListener {
                            Log.d(TAG, "Steps saved to Firebase: $totalSteps for $todayDateKey")

                            // Сохраняем в SharedPreferences для будущих сессий
                            val prefs = applicationContext.getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
                            prefs.edit().putFloat("last_total_steps", initialSteps + stepsDetected).apply()
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to save steps to Firebase: ${e.message}")
                        }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get current steps: ${e.message}")
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error saving steps to Firebase", e)
        }
    }
}