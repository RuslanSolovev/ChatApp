// VideoPVOActivity.kt
package com.example.chatapp.pvo

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.R

class VideoPVOActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCORE = "score"
        const val EXTRA_DISTANCE = "distance"
        const val EXTRA_MISSILES_DESTROYED = "missiles_destroyed"
        const val EXTRA_TIME = "time"
        const val EXTRA_IS_MISSION_SUCCESS = "is_mission_success"

        const val RESULT_VIDEO_COMPLETED = 1001
    }

    private lateinit var videoView: VideoView
    private lateinit var btnSkip: Button

    private var score: Int = 0
    private var distance: Int = 0
    private var missilesDestroyed: Int = 0
    private var time: Float = 0f
    private var isMissionSuccess: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_pvo_video)

        // РАЗРЕШАЕМ ВСЕ ОРИЕНТАЦИИ для этого Activity
        // Видео будет автоматически поворачиваться вместе с устройством
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

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
        score = intent.getIntExtra(EXTRA_SCORE, 0)
        distance = intent.getIntExtra(EXTRA_DISTANCE, 0)
        missilesDestroyed = intent.getIntExtra(EXTRA_MISSILES_DESTROYED, 0)
        time = intent.getFloatExtra(EXTRA_TIME, 0f)
        isMissionSuccess = intent.getBooleanExtra(EXTRA_IS_MISSION_SUCCESS, false)
    }

    private fun setupVideoPlayer() {
        // Определяем какое видео показывать в зависимости от результата
        val videoResource = if (isMissionSuccess) {
            R.raw.yderka // Видео для успешного прохождения
        } else {
            R.raw.failure // Видео для провала (если нужно)
        }

        try {
            val videoUri = Uri.parse("android.resource://${packageName}/$videoResource")
            videoView.setVideoURI(videoUri)

            videoView.setOnPreparedListener { mediaPlayer ->
                // Настройка видео
                mediaPlayer.isLooping = false

                // Устанавливаем масштабирование чтобы видео заполняло экран
                videoView.setScaleX(1.0f)
                videoView.setScaleY(1.0f)

                videoView.start()

                // Показываем кнопку пропуска после начала воспроизведения
                btnSkip.visibility = View.VISIBLE
            }

            videoView.setOnCompletionListener {
                // После завершения видео возвращаем результат
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
            e.printStackTrace()
            returnToGame()
        }
    }

    private fun skipVideo() {
        if (::videoView.isInitialized) {
            videoView.stopPlayback()
        }
        returnToGame()
    }

    private fun returnToGame() {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_SCORE, score)
            putExtra(EXTRA_DISTANCE, distance)
            putExtra(EXTRA_MISSILES_DESTROYED, missilesDestroyed)
            putExtra(EXTRA_TIME, time)
            putExtra(EXTRA_IS_MISSION_SUCCESS, isMissionSuccess)
        }
        setResult(RESULT_VIDEO_COMPLETED, resultIntent)
        finish()

        // Плавное исчезновение
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }


    override fun onPause() {
        super.onPause()
        if (::videoView.isInitialized) {
            videoView.pause()
        }
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