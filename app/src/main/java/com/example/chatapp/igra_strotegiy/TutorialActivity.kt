package com.example.chatapp.igra_strotegiy

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.R

class TutorialActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tutorial)

        val tvTutorialText = findViewById<TextView>(R.id.tvTutorialText)
        val btnBack = findViewById<Button>(R.id.btnBack)

        val tutorialText = """
        🎮 ОСНОВЫ ИГРЫ "ЦИВИЛИЗАЦИЯ"
        
        ⚡ ЦЕЛЬ ИГРЫ:
        Развивайте свою цивилизацию от Каменного века до Футуристической эры!
        Уничтожьте вражескую базу для победы.
        
        🏗️ СТРОИТЕЛЬСТВО:
        • Хижина - производит еду
        • Колодец - производит воду  
        • Лесопилка - производит дерево
        • Казармы - для найма юнитов
        • Научный центр - для исследований
        
        🔬 ИССЛЕДОВАНИЯ:
        • Тратьте ресурсы на новые технологии
        • Исследования открывают новые возможности
        • Для перехода в новую эпоху нужны исследования
        
        ⚔️ ВОЙНА:
        • Нанятые юниты автоматически атакуют врагов
        • Враги атакуют каждые 3 хода
        • Защищайте Ратушу - это ваша главная база
        
        💡 СОВЕТЫ:
        1. Сначала постройте ресурсные здания
        2. Исследуйте технологии для бонусов
        3. Создавайте армию для защиты
        4. Планируйте развитие по эпохам
        
        Удачи в создании великой цивилизации! 🏛️
        """.trimIndent()

        tvTutorialText.text = tutorialText

        btnBack.setOnClickListener {
            finish()
        }
    }
}