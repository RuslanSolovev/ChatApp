package com.example.chatapp.gruha

import android.content.Context
import android.media.MediaPlayer
import com.example.chatapp.R

class SoundManager(context: Context) {
    private var sound: MediaPlayer? = null

    init {
        sound = MediaPlayer.create(context, R.raw.alarm_sound1)
    }

    fun playSound() {
        sound?.apply {
            if (isPlaying) stop()
            seekTo(0)
            start()
        }
    }

    fun release() {
        sound?.release()
        sound = null
    }
}
