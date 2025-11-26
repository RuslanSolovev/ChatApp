package com.example.chatapp.gruha


import android.content.Context
import android.media.MediaPlayer
import com.example.chatapp.R

class SoundManager(context: Context) {
    private var roundEndSound: MediaPlayer? = null
    private var restEndSound: MediaPlayer? = null
    private var workoutCompleteSound: MediaPlayer? = null

    init {
        // Инициализируем разные звуки для разных событий
        roundEndSound = MediaPlayer.create(context, R.raw.alarm_sound2) // Звук окончания раунда
        restEndSound = MediaPlayer.create(context, R.raw.alarm_sound2) // Звук окончания отдыха
        workoutCompleteSound = MediaPlayer.create(context, R.raw.alarm_sound2) // Звук завершения тренировки

        // Настраиваем звуки
        roundEndSound?.setOnCompletionListener {
            it?.seekTo(0)
        }
        restEndSound?.setOnCompletionListener {
            it?.seekTo(0)
        }
        workoutCompleteSound?.setOnCompletionListener {
            it?.seekTo(0)
        }
    }

    fun playRoundEndSound() {
        playSound(roundEndSound, "раунда")
    }

    fun playRestEndSound() {
        playSound(restEndSound, "отдыха")
    }

    fun playWorkoutCompleteSound() {
        playSound(workoutCompleteSound, "тренировки")
    }

    private fun playSound(sound: MediaPlayer?, soundType: String) {
        sound?.apply {
            if (isPlaying) {
                stop()
            }
            seekTo(0)
            start()
            android.util.Log.d("SoundManager", "Воспроизводится звук окончания $soundType")
        } ?: run {
            android.util.Log.e("SoundManager", "Звук для $soundType не инициализирован")
        }
    }

    fun release() {
        roundEndSound?.release()
        restEndSound?.release()
        workoutCompleteSound?.release()
        roundEndSound = null
        restEndSound = null
        workoutCompleteSound = null
    }
}