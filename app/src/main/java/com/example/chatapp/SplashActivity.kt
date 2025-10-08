package com.example.chatapp.activities

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser

        if (currentUser != null) {
            // Пользователь авторизован - переходим в MainActivity
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            // Пользователь не авторизован - переходим в AuthActivity
            startActivity(Intent(this, AuthActivity::class.java))
        }
        finish()
    }
}