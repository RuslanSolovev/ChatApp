package com.example.chatapp

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.databinding.ActivityCreateChatBinding
import com.example.chatapp.databinding.ActivitySozdanieBesedaBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class SozdanieBeseda: AppCompatActivity() {

    private lateinit var binding: ActivitySozdanieBesedaBinding
    private lateinit var auth: FirebaseAuth
    private val database: FirebaseDatabase = Firebase.database
    private val currentUserId get() = auth.currentUser?.uid ?: ""
    private lateinit var fabCreateChatBeseda2: Button


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySozdanieBesedaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        fabCreateChatBeseda2 = findViewById(R.id.fabCreateChatBeseda2)

        // Настройка обработчиков кликов для кнопок
        setupClickListeners()


    }

    private fun setupClickListeners() {

    }






}
