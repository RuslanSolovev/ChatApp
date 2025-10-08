package com.example.chatapp



import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.fragments.GamesFragment
import kotlin.random.Random

class GuessNumberGameActivity : AppCompatActivity() {
    private lateinit var tvFeedback: TextView
    private lateinit var tvBestScore: TextView
    private lateinit var etUserGuess: EditText
    private lateinit var btnSubmit: Button

    private var targetNumber: Int = 0
    private var attempts: Int = 0
    private var level: Int = 100 // Значение по умолчанию

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guess_number_game)

        // Инициализация элементов интерфейса
        tvFeedback = findViewById(R.id.tvFeedback)
        tvBestScore = findViewById(R.id.tvBestScore)
        etUserGuess = findViewById(R.id.etUserGuess)
        btnSubmit = findViewById(R.id.btnSubmit)

        // Получение уровня сложности
        level = intent.getIntExtra("LEVEL", 100)

        // Установка текста в зависимости от уровня
        tvFeedback.text = "Угадайте число от 1 до $level"

        // Генерация случайного числа
        targetNumber = Random.nextInt(1, level + 1)
        attempts = 0

        // Настройка SharedPreferences
        sharedPreferences = getSharedPreferences("GuessNumberGamePrefs", MODE_PRIVATE)
        updateBestScore()

        // Обработка кнопки "Угадать"
        btnSubmit.setOnClickListener {
            val userGuess = etUserGuess.text.toString().toIntOrNull()
            if (userGuess == null) {
                Toast.makeText(this, "Введите число", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            attempts++
            when {
                userGuess < targetNumber -> tvFeedback.text = "Ваше число меньше загаданного."
                userGuess > targetNumber -> tvFeedback.text = "Ваше число больше загаданного."
                else -> {
                    saveBestScore()
                    showEndGameDialog()
                }
            }
        }
    }

    private fun updateBestScore() {
        val bestScore = sharedPreferences.getInt("BEST_SCORE_$level", Int.MAX_VALUE)
        tvBestScore.text = if (bestScore == Int.MAX_VALUE) {
            "Лучший результат: -"
        } else {
            "Лучший результат: $bestScore попыток"
        }
    }

    private fun saveBestScore() {
        val bestScore = sharedPreferences.getInt("BEST_SCORE_$level", Int.MAX_VALUE)
        if (attempts < bestScore) {
            sharedPreferences.edit().putInt("BEST_SCORE_$level", attempts).apply()
            Toast.makeText(this, "Новый рекорд!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEndGameDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Поздравляем!")
            .setMessage("Вы угадали число за $attempts попыток.\nЧто хотите сделать дальше?")
            .setPositiveButton("Сыграть ещё раз") { _, _ ->
                recreate()
            }
            .setNegativeButton("Выбрать сложность") { _, _ ->
                finish()
            }
        .setNeutralButton("Главное меню") { _, _ ->
            val intent = Intent(this, GamesFragment::class.java)
            startActivity(intent)
            finish()
        }
            .create()
        dialog.show()
    }
}
