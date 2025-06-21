package com.example.chatapp.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatapp.ChatSpisok
import com.example.chatapp.GuessNumberMenuActivity
import com.example.chatapp.IgraActivity
import com.example.chatapp.LocationActivity
import com.example.chatapp.R
import com.example.chatapp.StepCounterActivity
import com.example.chatapp.adapters.ChatAdapter
import com.example.chatapp.databinding.ActivityMainBinding
import com.example.chatapp.models.Chat
import com.example.chatapp.models.User
import com.example.chatapp.utils.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private lateinit var igra: Button
    private lateinit var hagi: Button
    private lateinit var btnLocation: Button
    private lateinit var textView: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация кнопок
        igra = findViewById(R.id.igra)
        hagi = findViewById(R.id.hagi)
        btnLocation = findViewById(R.id.btnLocation)
        textView = findViewById(R.id.textView)

        // Инициализация FirebaseAuth
        auth = FirebaseAuth.getInstance()

        // Проверка аутентификации
        if (auth.currentUser == null) {
            startAuthActivity()
            return
        }

        // Инициализация базы данных Firebase
        database = FirebaseDatabase.getInstance().reference


        // Настройка обработчиков кликов для кнопок
        setupClickListeners()

        // Обработчик для кнопки перехода в LocationActivity
        btnLocation.setOnClickListener {
            startActivity(Intent(this, LocationActivity::class.java))
        }

        textView.setOnClickListener {
            startActivity(Intent(this, ChatSpisok::class.java))
        }
    }

    private fun startAuthActivity() {
        startActivity(Intent(this, AuthActivity::class.java))
        finish()
    }





    private fun setupClickListeners() {


        binding.igra.setOnClickListener {
            val intent = Intent(this, IgraActivity::class.java)
            startActivity(intent)
        }

        binding.btnLogout.setOnClickListener {
            auth.signOut()
            startAuthActivity()
        }

        binding.btnMyProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        binding.hagi.setOnClickListener {
            startActivity(Intent(this, StepCounterActivity::class.java))
        }
    }






    override fun onStart() {
        super.onStart()
        if (auth.currentUser == null) {
            startAuthActivity()
        }
    }

}
