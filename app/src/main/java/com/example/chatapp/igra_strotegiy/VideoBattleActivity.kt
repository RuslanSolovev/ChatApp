// VideoBattleActivity.kt
package com.example.chatapp.igra_strotegiy

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.R

class VideoBattleActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ATTACKER_UID = "attacker_uid"
        const val EXTRA_IS_TOWN_HALL_ATTACK = "is_town_hall_attack"
    }

    private lateinit var videoView: VideoView
    private lateinit var btnSkip: Button
    private var attackerUid: String? = null
    private var isTownHallAttack: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_battle_video)

        // Скрываем системный UI для полноэкранного режима
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        initViews()
        getIntentData()
        setupVideoPlayer()
    }

    private fun initViews() {
        videoView = findViewById(R.id.videoView)
        btnSkip = findViewById(R.id.btnSkip)

        btnSkip.setOnClickListener {
            skipVideo()
        }
    }

    private fun getIntentData() {
        attackerUid = intent.getStringExtra(EXTRA_ATTACKER_UID)
        isTownHallAttack = intent.getBooleanExtra(EXTRA_IS_TOWN_HALL_ATTACK, false)
    }

    private fun setupVideoPlayer() {
        // Определяем какое видео показывать в зависимости от типа боя
        val videoResource = when {
            isTownHallAttack -> R.raw.battle_army_vs_army
            else -> R.raw.battle_army_vs_army
        }

        try {
            val videoUri = Uri.parse("android.resource://${packageName}/$videoResource")
            videoView.setVideoURI(videoUri)

            videoView.setOnPreparedListener { mediaPlayer ->
                // Настройка видео
                mediaPlayer.isLooping = false
                videoView.start()

                // Показываем кнопку пропуска после начала воспроизведения
                btnSkip.visibility = View.VISIBLE
            }

            videoView.setOnCompletionListener {
                // После завершения видео возвращаемся
                returnToGame()
            }

            // Обработка ошибок видео
            videoView.setOnErrorListener { _, what, extra ->
                // Если видео не загрузилось, сразу переходим к результатам
                returnToGame()
                true
            }

        } catch (e: Exception) {
            // В случае ошибки сразу возвращаемся
            returnToGame()
        }
    }

    private fun skipVideo() {
        videoView.stopPlayback()
        returnToGame()
    }

    private fun returnToGame() {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_ATTACKER_UID, attackerUid)
        }
        setResult(RESULT_OK, resultIntent)
        finish()

        // Плавное исчезновение
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onBackPressed() {
        // Пользователь может пропустить видео кнопкой "Назад"
        skipVideo()
        super.onBackPressed() // Добавляем вызов super
    }

    override fun onPause() {
        super.onPause()
        videoView.pause()
    }

    override fun onResume() {
        super.onResume()
        if (::videoView.isInitialized) {
            videoView.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::videoView.isInitialized) {
            videoView.stopPlayback()
        }
    }
}