package com.example.chatapp.pamyat

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.chatapp.R

class MemoryGameActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Убираем ActionBar
        supportActionBar?.hide()

        // Включаем режим подгонки под системные окна
        WindowCompat.setDecorFitsSystemWindows(window, true)

        setContentView(R.layout.activity_memory_game)

        // Загружаем фрагмент игры
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container_memory, MemoryGameFragment())
                .commit()
        }
    }
}