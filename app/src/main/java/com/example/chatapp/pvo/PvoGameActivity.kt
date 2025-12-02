// PvoGameActivity.kt
package com.example.chatapp.pvo

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.R

class PvoGameActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private var currentBestScore = 0

    // Регистрация для получения результата из VideoPVOActivity
    private val videoResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == VideoPVOActivity.RESULT_VIDEO_COMPLETED) {
            val data = result.data
            if (data != null) {
                // Получаем данные из видео активности
                val score = data.getIntExtra(VideoPVOActivity.EXTRA_SCORE, 0)
                val isSuccess = data.getBooleanExtra(VideoPVOActivity.EXTRA_IS_MISSION_SUCCESS, false)
                val distance = data.getIntExtra(VideoPVOActivity.EXTRA_DISTANCE, 0)
                val missilesDestroyed = data.getIntExtra(VideoPVOActivity.EXTRA_MISSILES_DESTROYED, 0)
                val time = data.getFloatExtra(VideoPVOActivity.EXTRA_TIME, 0f)

                // Обновляем gameView
                gameView.onVideoCompleted()

                // Обновляем лучший счет если нужно
                val sharedPreferences = getSharedPreferences("GamePrefs", MODE_PRIVATE)
                if (score > currentBestScore) {
                    currentBestScore = score
                    sharedPreferences.edit().putInt("best_score", score).apply()
                    gameView.setBestScore(score)
                }

                // Для провала вызываем onGameOverListener
                if (!isSuccess) {
                    gameView.onMissionFailed(score)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pvo_game)

        gameView = findViewById(R.id.gameView)

        // Устанавливаем лучший счет
        val sharedPreferences = getSharedPreferences("GamePrefs", MODE_PRIVATE)
        currentBestScore = sharedPreferences.getInt("best_score", 0)
        gameView.setBestScore(currentBestScore)

        // Обычный game over (для обычной игры)
        gameView.setOnGameOverListener { score ->
            if (score > currentBestScore) {
                currentBestScore = score
                sharedPreferences.edit().putInt("best_score", score).apply()
                gameView.setBestScore(score)
            }
        }

        // Listener для успешного/неуспешного прохождения миссии
        gameView.setOnMissionSuccessListener { missionData ->
            launchVideoActivity(missionData)
        }
    }

    private fun launchVideoActivity(missionData: GameView.MissionData) {
        val intent = Intent(this, VideoPVOActivity::class.java).apply {
            putExtra(VideoPVOActivity.EXTRA_SCORE, missionData.score)
            putExtra(VideoPVOActivity.EXTRA_DISTANCE, missionData.distance)
            putExtra(VideoPVOActivity.EXTRA_MISSILES_DESTROYED, missionData.missilesDestroyed)
            putExtra(VideoPVOActivity.EXTRA_TIME, missionData.time)
            putExtra(VideoPVOActivity.EXTRA_IS_MISSION_SUCCESS, missionData.isSuccess)
        }
        videoResultLauncher.launch(intent)
    }
}