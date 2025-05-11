package com.example.chatapp


import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button

class IgraActivity : AppCompatActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_igra)

        // Инициализация кнопок
        val btnGuessNumber = findViewById<Button>(R.id.btn_guess_number)
        val btnAdditionGame = findViewById<Button>(R.id.btn_addition_game)

        // Переход на экран выбора сложности для игры "Угадай число"
        btnGuessNumber.setOnClickListener {
            val intent = Intent(this, GuessNumberMenuActivity::class.java)
            startActivity(intent)
        }

        btnAdditionGame.setOnClickListener {
            val intent = Intent(this, AdditionGameMenuActivity::class.java)
            startActivity(intent)
        }


    }
}
