package com.example.chatapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.databinding.ActivityChatObsuditBinding


class ChatObsudit : AppCompatActivity() {

    private lateinit var binding: ActivityChatObsuditBinding

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Инициализация ViewBinding
    binding = ActivityChatObsuditBinding.inflate(layoutInflater)
    setContentView(binding.root)








  }

}