package com.example.chatapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import android.widget.TextView

class GuessNumberMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guess_number_menu)

        // Инициализация карточек уровней сложности
        findViewById<CardView>(R.id.card_easy).setOnClickListener {
            startGame(100)
        }
        findViewById<CardView>(R.id.card_medium).setOnClickListener {
            startGame(1000)
        }
        findViewById<CardView>(R.id.card_hard).setOnClickListener {
            startGame(10000)
        }
        findViewById<CardView>(R.id.card_expert).setOnClickListener {
            startGame(100000)
        }
    }

    private fun startGame(level: Int) {
        val intent = Intent(this, GuessNumberGameActivity::class.java)
        intent.putExtra("LEVEL", level)
        startActivity(intent)
    }
}