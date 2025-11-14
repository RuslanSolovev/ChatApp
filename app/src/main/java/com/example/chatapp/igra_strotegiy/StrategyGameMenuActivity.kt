package com.example.chatapp.igra_strotegiy

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.R

class StrategyGameMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 🔲 Включаем полноэкранный режим ДО setContentView
        hideSystemUI()

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_strategy_game_menu)

        val videoView: VideoView = findViewById(R.id.videoView)
        val menuContainer: View = findViewById(R.id.menuContainer)

        val uri = Uri.parse("android.resource://$packageName/${R.raw.prevy_igra2}")
        videoView.setVideoURI(uri)

        videoView.setOnCompletionListener {
            // Возвращаем UI после видео
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            findViewById<View>(R.id.videoContainer).visibility = View.GONE
            menuContainer.visibility = View.VISIBLE
            setupClickListeners()
        }

        videoView.start()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }

    private fun setupClickListeners() {
        findViewById<View>(R.id.btnSinglePlayer).setOnClickListener {
            startActivity(Intent(this, StrategyGameActivity::class.java))
        }
        findViewById<View>(R.id.btnMultiplayer).setOnClickListener {
            startActivity(Intent(this, MultiplayerLobbyActivity::class.java))
        }
        findViewById<View>(R.id.btnTutorial).setOnClickListener {
            startActivity(Intent(this, TutorialActivity::class.java))
        }
        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }
}