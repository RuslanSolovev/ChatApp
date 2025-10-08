package com.example.chatapp.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide

import com.example.chatapp.R
import com.example.chatapp.databinding.ActivityUserProfileBinding
import com.example.chatapp.models.User
import com.example.chatapp.models.UserLocation
import com.google.firebase.database.*

class UserProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserProfileBinding
    private lateinit var database: DatabaseReference
    private lateinit var userId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_ChatApp_NoActionBar)
        binding = ActivityUserProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Получаем ID пользователя из Intent
        userId = intent.getStringExtra("USER_ID") ?: run {
            finish()
            return
        }

        setupToolbar()
        initFirebase()
        loadUserProfile()
        setupClickListeners()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Профиль"
        }
    }

    private fun initFirebase() {
        database = FirebaseDatabase.getInstance().reference
    }

    private fun setupClickListeners() {
        binding.btnShowOnMap.setOnClickListener {

        }
    }

    private fun loadUserProfile() {
        database.child("users").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        val user = snapshot.getValue(User::class.java)?.apply {
                            uid = userId
                        }
                        user?.let { displayProfile(it) } ?: showProfileNotFound()
                    } catch (e: Exception) {
                        Log.e("UserProfile", "Error parsing user data", e)
                        showError("Ошибка загрузки профиля")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    showError("Ошибка загрузки: ${error.message}")
                }
            })
    }

    private fun displayProfile(user: User) {
        with(binding) {
            // Основная информация
            tvName.text = user.name ?: "Без имени"
            tvEmail.text = user.email ?: "Нет email"

            // Геолокация
            user.lastLocation?.let { location ->
                tvLocation.text = "Широта: ${location.lat}, Долгота: ${location.lng}"
                btnShowOnMap.visibility = android.view.View.VISIBLE
            } ?: run {
                tvLocation.text = "Локация не доступна"
                btnShowOnMap.visibility = android.view.View.GONE
            }

            // Детальная информация
            tvFirstName.text = user.name?.takeIf { it.isNotBlank() } ?: "Не указано"
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
                    .error(R.drawable.ic_default_profile)
                    .into(ivProfile)
            }
        }
    }


    private fun showProfileNotFound() {
        Toast.makeText(this, "Профиль не найден", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}