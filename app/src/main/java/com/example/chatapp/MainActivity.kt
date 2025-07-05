package com.example.chatapp.activities

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.ChatObsudit
import com.example.chatapp.ChatSpisok
import com.example.chatapp.ChatWithGigaActivity
import com.example.chatapp.IgraActivity
import com.example.chatapp.LocationActivity
import com.example.chatapp.R
import com.example.chatapp.StepCounterActivity
import com.example.chatapp.databinding.ActivityMainBinding
import com.example.chatapp.models.User
import com.example.chatapp.utils.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference

    companion object {
        private const val TAG = "MainActivity"
        private const val CHANNEL_ID = "chat_messages"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Создаем канал уведомлений
        createNotificationChannel()

        // Инициализация Firebase
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().reference

        // Проверка аутентификации
        if (auth.currentUser == null) {
            startAuthActivity()
            return
        }

        // Настройка обработчиков кликов
        setupClickListeners()

        // Загрузка данных пользователя

    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Сообщения чата",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых сообщениях в чате"
                enableLights(true)
                lightColor = Color.BLUE
                enableVibration(true)
                vibrationPattern = longArrayOf(100, 200, 100, 200)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Канал уведомлений создан: $CHANNEL_ID")
        } else {
            Log.d(TAG, "Создание канала не требуется (API < 26)")
        }
    }



    private fun setupClickListeners() {
        // Навигация по приложению
        binding.igra.setOnClickListener {
            startActivity(Intent(this, IgraActivity::class.java))
        }

        binding.hagi.setOnClickListener {
            startActivity(Intent(this, StepCounterActivity::class.java))
        }

        binding.beseda.setOnClickListener {
            startActivity(Intent(this, ChatObsudit::class.java))
        }

        binding.btnLocation.setOnClickListener {
            startActivity(Intent(this, LocationActivity::class.java))
        }

        binding.textView.setOnClickListener {
            startActivity(Intent(this, ChatSpisok::class.java))
        }

        binding.btnGigaChat.setOnClickListener {
            startActivity(Intent(this, ChatWithGigaActivity::class.java))
        }

        // Действия с аккаунтом
        binding.btnLogout.setOnClickListener {
            auth.signOut()
            startAuthActivity()
        }

        binding.btnMyProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }

    private fun startAuthActivity() {
        startActivity(Intent(this, AuthActivity::class.java))
        finish()
    }

    override fun onStart() {
        super.onStart()
        // Проверка аутентификации при возвращении в приложение
        if (auth.currentUser == null) {
            startAuthActivity()
        }
    }

    override fun onResume() {
        super.onResume()
        // Обновляем статус активности пользователя
        auth.currentUser?.uid?.let { uid ->
            database.child("users").child(uid).child("isActive").setValue(true)
        }
    }

    override fun onPause() {
        super.onPause()
        // Обновляем статус активности пользователя
        auth.currentUser?.uid?.let { uid ->
            database.child("users").child(uid).child("isActive").setValue(false)
        }
    }
}