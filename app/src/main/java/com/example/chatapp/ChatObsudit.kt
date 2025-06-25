package com.example.chatapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.databinding.ActivityChatObsuditBinding


class ChatObsudit : AppCompatActivity() {

    private lateinit var binding: ActivityChatObsuditBinding
    private lateinit var fabCreateChatBeseda: Button

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Инициализация ViewBinding
    binding = ActivityChatObsuditBinding.inflate(layoutInflater)
    setContentView(binding.root)

    // Настройка обработчиков кликов для кнопок
    setupClickListeners()

    fabCreateChatBeseda = findViewById(R.id.fabCreateChatBeseda)

  }

    private fun setupClickListeners() {


        binding.fabCreateChatBeseda.setOnClickListener {
            val intent = Intent(this, SozdanieBeseda::class.java)
            startActivity(intent)
        }

    }

}
