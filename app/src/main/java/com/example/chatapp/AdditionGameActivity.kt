package com.example.chatapp

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AdditionGameActivity : AppCompatActivity() {

    private lateinit var tvProblem: TextView
    private lateinit var etUserAnswer: EditText
    private lateinit var btnSubmit: Button
    private lateinit var tvFeedback: TextView
    private lateinit var tvErrors: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvCorrectAnswers: TextView
    private lateinit var tvBestScore: TextView
    private var correctAnswer: Int = 0
    private var errors: Int = 0
    private var correctAnswers: Int = 0
    private var maxErrors: Int = 3
    private var timeLeft: Long = 10000 // 10 секунд для ответа
    private var timer: CountDownTimer? = null
    private var difficultyLevel: Int = 1 // По умолчанию уровень 1 (однозначные числа)
    private lateinit var sharedPreferences: SharedPreferences
    private var bestScore: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_addition_game)

        // Инициализация элементов интерфейса
        tvProblem = findViewById(R.id.tvProblem)
        etUserAnswer = findViewById(R.id.etUserAnswer)
        btnSubmit = findViewById(R.id.btnSubmit)
        tvFeedback = findViewById(R.id.tvFeedback)
        tvErrors = findViewById(R.id.tvErrors)
        tvTimer = findViewById(R.id.tvTimer)
        tvCorrectAnswers = findViewById(R.id.tvCorrectAnswers)
        tvBestScore = findViewById(R.id.tvBestScore)

        // Получаем уровень сложности из Intent
        difficultyLevel = intent.getIntExtra("difficulty", 1)

        // Инициализируем SharedPreferences для хранения рекорда
        sharedPreferences = getSharedPreferences("AdditionGamePrefs", MODE_PRIVATE)
        bestScore = sharedPreferences.getInt("bestScore_level_$difficultyLevel", 0)
        tvBestScore.text = "Рекорд: $bestScore"

        // Устанавливаем максимальное количество ошибок
        maxErrors = 3
        errors = 0
        correctAnswers = 0
        tvErrors.text = "Ошибки: $errors/$maxErrors"
        tvCorrectAnswers.text = "Правильных ответов: $correctAnswers"

        // Генерация первой задачи
        generateProblem()

        // Запуск таймера
        startTimer()

        // Обработчик клика по кнопке отправки
        btnSubmit.setOnClickListener {
            val userAnswer = etUserAnswer.text.toString().toIntOrNull()

            if (userAnswer != null) {
                if (userAnswer == correctAnswer) {
                    tvFeedback.text = "Правильный ответ!"
                    correctAnswers++
                    tvCorrectAnswers.text = "Правильных ответов: $correctAnswers"

                    // Сохраняем рекорд для текущего уровня, если новый рекорд
                    if (correctAnswers > bestScore) {
                        bestScore = correctAnswers
                        sharedPreferences.edit().putInt("bestScore_level_$difficultyLevel", bestScore).apply()
                        tvBestScore.text = "Рекорд: $bestScore"
                    }

                    generateProblem() // Генерируем новую задачу
                    etUserAnswer.text.clear() // Очищаем поле ввода
                    resetTimer() // Сбрасываем таймер
                } else {
                    handleError("Неправильный ответ!")
                }
            }
        }
    }

    // Функция для обработки ошибок
    private fun handleError(message: String) {
        errors++
        tvFeedback.text = message
        tvErrors.text = "Ошибки: $errors/$maxErrors"
        if (errors >= maxErrors) {
            timer?.cancel() // Останавливаем таймер
            showGameOverDialog()
        }
    }

    // Функция для отображения диалогового окна завершения игры
    private fun showGameOverDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Игра завершена!")
        builder.setMessage("Вы набрали $correctAnswers правильных ответов. Что хотите сделать дальше?")
        builder.setCancelable(false)

        builder.setPositiveButton("Сыграть ещё раз") { _, _ ->
            restartGame()
        }

        builder.setNegativeButton("Выбрать сложность") { _, _ ->
            val intent = Intent(this, AdditionGameMenuActivity::class.java)
            startActivity(intent)
            finish()
        }

        builder.setNeutralButton("Главное меню") { _, _ ->
            val intent = Intent(this, IgraActivity::class.java)
            startActivity(intent)
            finish()
        }

        val dialog = builder.create()
        dialog.show()
    }

    // Функция для перезапуска игры
    private fun restartGame() {
        errors = 0
        correctAnswers = 0
        tvErrors.text = "Ошибки: $errors/$maxErrors"
        tvCorrectAnswers.text = "Правильных ответов: $correctAnswers"
        generateProblem()
        resetTimer()
        etUserAnswer.isEnabled = true
        btnSubmit.isEnabled = true
        tvFeedback.text = ""
    }

    // Функция для генерации задачи в зависимости от уровня сложности
    private fun generateProblem() {
        val number1 = when (difficultyLevel) {
            1 -> (1..9).random()
            2 -> (10..99).random()
            3 -> (100..999).random()
            4 -> (1000..9999).random()
            else -> (1..9).random()
        }
        val number2 = when (difficultyLevel) {
            1 -> (1..9).random()
            2 -> (10..99).random()
            3 -> (100..999).random()
            4 -> (1000..9999).random()
            else -> (1..9).random()
        }
        correctAnswer = number1 + number2
        tvProblem.text = "$number1 + $number2"
    }

    // Функция для запуска таймера
    private fun startTimer() {
        timer = object : CountDownTimer(timeLeft, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeLeft = millisUntilFinished
                tvTimer.text = "Осталось времени: ${millisUntilFinished / 1000} сек"
            }

            override fun onFinish() {
                handleError("Время вышло!")
            }
        }.start()
    }

    // Функция для сброса таймера
    private fun resetTimer() {
        timeLeft = 10000 // 10 секунд
        timer?.cancel()
        startTimer()
    }
}
