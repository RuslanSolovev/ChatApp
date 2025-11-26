package com.example.chatapp.gruha

import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.chatapp.R

class TimerActivity : AppCompatActivity() {

    private lateinit var roundTime: EditText
    private lateinit var restTime: EditText
    private lateinit var roundsCount: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var timerText: TextView
    private lateinit var statusText: TextView

    private var currentRound = 1
    private var totalRounds = 1
    private var isRest = false
    private var timer: CountDownTimer? = null
    private var soundManager: SoundManager? = null
    private var vibrator: Vibrator? = null

    // Сохранённые значения таймера (чтобы не зависеть от EditText после старта)
    private var configuredRoundTimeMs: Long = 0
    private var configuredRestTimeMs: Long = 0

    // Цвета для разных состояний
    private val roundColor by lazy { ContextCompat.getColor(this, R.color.round_active_color) }
    private val restColor by lazy { ContextCompat.getColor(this, R.color.rest_active_color) }
    private val readyColor by lazy { ContextCompat.getColor(this, R.color.ready_color) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timer)

        // Инициализация элементов
        roundTime = findViewById(R.id.round_time)
        restTime = findViewById(R.id.rest_time)
        roundsCount = findViewById(R.id.rounds_count)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)
        timerText = findViewById(R.id.timer_text)
        statusText = findViewById(R.id.status_text)

        // Инициализация вибратора
        vibrator = getSystemService(Vibrator::class.java)

        // Устанавливаем значения по умолчанию
        roundTime.setText("120")
        restTime.setText("25")
        roundsCount.setText("15")

        soundManager = SoundManager(this)

        // Обработчики кнопок
        startButton.setOnClickListener { startTimer() }
        stopButton.setOnClickListener { stopTimer() }

        // Начальный статус
        updateStatus("Готов к старту")
        timerText.setTextColor(readyColor)
        timerText.text = "00:00"
    }

    private fun startTimer() {
        if (!validateInput()) {
            Toast.makeText(this, "Заполните все поля корректными значениями", Toast.LENGTH_SHORT).show()
            return
        }

        // Сохраняем настройки один раз
        configuredRoundTimeMs = roundTime.text.toString().toLong() * 1000
        configuredRestTimeMs = restTime.text.toString().toLong() * 1000
        totalRounds = roundsCount.text.toString().toInt()

        currentRound = 1

        // Блокируем кнопку старта
        startButton.isEnabled = false
        startButton.alpha = 0.5f

        startRoundTimer()
    }

    private fun startRoundTimer() {
        if (currentRound > totalRounds) {
            completeWorkout()
            return
        }

        isRest = false
        updateStatus("РАУНД $currentRound/$totalRounds")
        timerText.setTextColor(roundColor)

        timer = object : CountDownTimer(configuredRoundTimeMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                val minutes = seconds / 60
                val remainingSeconds = seconds % 60
                timerText.text = String.format("%02d:%02d", minutes, remainingSeconds)
            }

            override fun onFinish() {
                soundManager?.playRoundEndSound()
                vibrate(500)

                if (currentRound < totalRounds) {
                    startRestTimer()
                } else {
                    completeWorkout()
                }
            }
        }.start()
    }

    private fun startRestTimer() {
        isRest = true
        updateStatus("ОТДЫХ")
        timerText.setTextColor(restColor)

        timer = object : CountDownTimer(configuredRestTimeMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                val minutes = seconds / 60
                val remainingSeconds = seconds % 60
                timerText.text = String.format("%02d:%02d", minutes, remainingSeconds)
            }

            override fun onFinish() {
                soundManager?.playRestEndSound()
                vibrate(300)

                currentRound++
                startRoundTimer()
            }
        }.start()
    }

    private fun completeWorkout() {
        soundManager?.playWorkoutCompleteSound()
        vibrate(1000)

        updateStatus("ТРЕНИРОВКА ЗАВЕРШЕНА!")
        timerText.text = "🎉 МОЛОДЕЦ! 🎉"
        timerText.setTextColor(readyColor)

        startButton.isEnabled = true
        startButton.alpha = 1.0f

        Toast.makeText(this, "Тренировка успешно завершена!", Toast.LENGTH_LONG).show()
    }

    private fun stopTimer() {
        timer?.cancel()
        currentRound = 1
        isRest = false

        updateStatus("ТАЙМЕР ОСТАНОВЛЕН")
        timerText.text = "00:00"
        timerText.setTextColor(readyColor)

        startButton.isEnabled = true
        startButton.alpha = 1.0f

        Toast.makeText(this, "Таймер остановлен", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus(status: String) {
        statusText.text = status
    }

    private fun validateInput(): Boolean {
        return try {
            val round = roundTime.text.toString().toLong()
            val rest = restTime.text.toString().toLong()
            val rounds = roundsCount.text.toString().toInt()

            round > 0 && rest > 0 && rounds in 1..20
        } catch (e: NumberFormatException) {
            false
        }
    }

    private fun vibrate(milliseconds: Long) {
        try {
            vibrator?.let { v ->
                if (v.hasVibrator()) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val effect = VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE)
                        v.vibrate(effect)
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(milliseconds)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("TimerActivity", "Vibration not available: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
        soundManager?.release()
    }
}