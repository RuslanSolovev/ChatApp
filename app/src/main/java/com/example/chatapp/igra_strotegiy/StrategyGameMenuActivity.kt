package com.example.chatapp.igra_strotegiy

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.R

class StrategyGameMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_strategy_game_menu)

        val btnSinglePlayer = findViewById<Button>(R.id.btnSinglePlayer)
        val btnMultiplayer = findViewById<Button>(R.id.btnMultiplayer)
        val btnTutorial = findViewById<Button>(R.id.btnTutorial)
        val btnBack = findViewById<Button>(R.id.btnBack)

        btnSinglePlayer.setOnClickListener {
            startActivity(Intent(this, StrategyGameActivity::class.java))
        }

        btnMultiplayer.setOnClickListener {
            startActivity(Intent(this, MultiplayerLobbyActivity::class.java))
        }

        btnTutorial.setOnClickListener {
            startActivity(Intent(this, TutorialActivity::class.java))
        }

        btnBack.setOnClickListener {
            finish()
        }
    }
}