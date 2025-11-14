package com.example.chatapp.gruha

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.R

class TimerActivity : AppCompatActivity() {

    private lateinit var roundTime: EditText
    private lateinit var restTime: EditText
    private lateinit var roundsCount: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var timerText: TextView

    private var currentRound = 1
    private var isRest = false
    private var timer: CountDownTimer? = null
    private var soundManager: SoundManager? = null

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

        soundManager = SoundManager(this)

        // Добавляем обработчики
        startButton.setOnClickListener {
            startTimer()
        }

        stopButton.setOnClickListener {
            stopTimer()
        }
    }

    private fun startTimer() {
        if (!validateInput()) {
            Toast.makeText(this, "Заполните все поля", Toast.LENGTH_SHORT).show()
            return
        }

        val round = roundTime.text.toString().toLong() * 1000
        val rest = restTime.text.toString().toLong() * 1000
        val rounds = roundsCount.text.toString().toInt()

        timer = object : CountDownTimer(round, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                timerText.text = "Раунд $currentRound: $seconds"
            }

            override fun onFinish() {
                soundManager?.playSound()

                if (currentRound <= rounds) {
                    isRest = true
                    timer = object : CountDownTimer(rest, 1000) {
                        override fun onTick(millisUntilFinished: Long) {
                            val seconds = millisUntilFinished / 1000
                            timerText.text = "Отдых: $seconds"
                        }

                        override fun onFinish() {
                            soundManager?.playSound()
                            isRest = false
                            currentRound++
                            startTimer()
                        }
                    }.start()
                } else {
                    timerText.text = "Тренировка завершена!"
                }
            }
        }.start()
    }

    private fun stopTimer() {
        timer?.cancel()
        currentRound = 1
        isRest = false
        timerText.text = "Готов к старту"
    }

    private fun validateInput(): Boolean {
        return roundTime.text.isNotEmpty() &&
                restTime.text.isNotEmpty() &&
                roundsCount.text.isNotEmpty()
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
        soundManager?.release()
    }
}
