package com.example.chatapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import androidx.cardview.widget.CardView

class AdditionGameMenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_addition_game_menu)

        findViewById<CardView>(R.id.card_single_digit).setOnClickListener {
            startGame(1)
        }
        findViewById<CardView>(R.id.card_two_digit).setOnClickListener {
            startGame(2)
        }
        findViewById<CardView>(R.id.card_three_digit).setOnClickListener {
            startGame(3)
        }
        findViewById<CardView>(R.id.card_four_digit).setOnClickListener {
            startGame(4)
        }
    }

    private fun startGame(difficulty: Int) {
        val intent = Intent(this, AdditionGameActivity::class.java)
        intent.putExtra("difficulty", difficulty)
        startActivity(intent)
    }
}