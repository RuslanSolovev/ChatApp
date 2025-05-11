package com.example.chatapp.activities

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.chatapp.R
import com.example.chatapp.databinding.ActivityUserProfileBinding
import com.example.chatapp.models.User
import com.google.firebase.database.*

class UserProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserProfileBinding
    private lateinit var database: DatabaseReference
    private lateinit var userId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Убедитесь, что эта строка идет ПЕРЕД setContentView
        setTheme(R.style.Theme_ChatApp_NoActionBar)

        binding = ActivityUserProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Настройка Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Профиль"

        // Получаем ID пользователя из Intent
        userId = intent.getStringExtra("USER_ID") ?: run {
            finish()
            return
        }

        // Инициализация Firebase
        database = FirebaseDatabase.getInstance().reference

        // Настройка Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Профиль"

        // Загрузка данных
        loadUserProfile()
    }

    private fun loadUserProfile() {
        database.child("users").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        val user = snapshot.getValue(User::class.java)?.apply {
                            // Убедимся, что uid установлен
                            uid = userId
                        }

                        user?.let { displayProfile(it) } ?: run {
                            Toast.makeText(this@UserProfileActivity, "Профиль не найден", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    } catch (e: Exception) {
                        Log.e("UserProfile", "Error parsing user data", e)
                        Toast.makeText(this@UserProfileActivity, "Ошибка загрузки профиля", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@UserProfileActivity, "Ошибка загрузки", Toast.LENGTH_SHORT).show()
                    finish()
                }
            })
    }

    private fun displayProfile(user: User) {
        with(binding) {
            // Основная информация
            tvName.text = user.name ?: "Без имени"
            tvEmail.text = user.email ?: "Нет email"

            // Детальная информация
            tvFirstName.text = user.firstName?.takeIf { it.isNotBlank() } ?: "Не указано"
            tvLastName.text = user.lastName?.takeIf { it.isNotBlank() } ?: "Не указано"
            tvMiddleName.text = user.middleName?.takeIf { it.isNotBlank() } ?: "Не указано"

            // Дополнительная информация
            tvAdditionalInfo.text = user.additionalInfo?.takeIf { it.isNotBlank() }
                ?: "Дополнительная информация не указана"

            // Аватар
            user.profileImageUrl?.let { url ->
                Glide.with(this@UserProfileActivity)
                    .load(url)
                    .circleCrop()
                    .placeholder(R.drawable.ic_default_profile)
                    .into(ivProfile)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}