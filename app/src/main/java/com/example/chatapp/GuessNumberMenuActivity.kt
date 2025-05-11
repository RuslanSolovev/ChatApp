package com.example.chatapp


import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class GuessNumberMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guess_number_menu)

        // Инициализация кнопок уровней сложности
        findViewById<Button>(R.id.btn_easy).setOnClickListener {
            startGame(100)
        }
        findViewById<Button>(R.id.btn_medium).setOnClickListener {
            startGame(1000)
        }
        findViewById<Button>(R.id.btn_hard).setOnClickListener {
            startGame(10000)
        }
        findViewById<Button>(R.id.btn_expert).setOnClickListener {
            startGame(100000)
        }
    }

    private fun startGame(level: Int) {
        val intent = Intent(this, GuessNumberGameActivity::class.java)
        intent.putExtra("LEVEL", level)
        startActivity(intent)
    }
}
