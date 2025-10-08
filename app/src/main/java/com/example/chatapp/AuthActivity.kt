package com.example.chatapp.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

import com.example.chatapp.databinding.ActivityAuthBinding
import com.example.chatapp.models.User
import com.example.chatapp.utils.NotificationUtils // Импортируем NotificationUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.messaging.FirebaseMessaging

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initFirebase()
        setupClickListeners()

        // Проверка пользователя с блокировкой интерфейса
        checkCurrentUserWithLoading()
    }

    private fun initFirebase() {
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().reference
        // Инициализация OneSignal происходит в Application классе или в манифесте
        // Здесь мы только вызываем метод для сохранения ID
    }

    override fun onResume() {
        super.onResume()
        // Попробуем получить и сохранить OneSignal ID при каждом входе в активность
        // Только если пользователь уже авторизован
        if (auth.currentUser != null) {
            // Вызываем метод из NotificationUtils для сохранения ID
            NotificationUtils.saveCurrentUserOneSignalIdToDatabase(this)
        }
    }

    private fun checkCurrentUserWithLoading() {
        showLoading(true)
        auth.currentUser?.let { user ->
            proceedWithAuth(user.uid)
        } ?: run {
            showLoading(false)
        }
    }

    private fun setupClickListeners() {
        binding.btnRegister.setOnClickListener { handleRegistration() }
        binding.btnLogin.setOnClickListener { handleLogin() }
    }

    private fun handleRegistration() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()

        if (validateInput(email, password, username)) {
            showLoading(true)
            registerUser(email, password, username)
        }
    }

    private fun handleLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Заполните все поля", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading(true)
        loginUser(email, password)
    }

    private fun registerUser(email: String, password: String, username: String) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    auth.currentUser?.uid?.let { uid ->
                        saveUserToDatabase(uid, email, username)
                    }
                } else {
                    showError("Ошибка регистрации: ${task.exception?.message}")
                    showLoading(false)
                }
            }
    }

    private fun loginUser(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    auth.currentUser?.uid?.let { uid ->
                        proceedWithAuth(uid)
                    }
                } else {
                    showError("Ошибка входа: ${task.exception?.message}")
                    showLoading(false)
                }
            }
    }

    private fun saveUserToDatabase(uid: String, email: String, username: String) {
        val user = User(
            uid = uid,
            email = email,
            name = username,
            isActive = true,
            online = true
        )

        database.child("users").child(uid).setValue(user)
            .addOnSuccessListener {
                registerFcmToken(uid)
                // Сохраняем OneSignal ID после регистрации
                NotificationUtils.saveCurrentUserOneSignalIdToDatabase(this)
            }
            .addOnFailureListener { e ->
                showError("Ошибка сохранения пользователя")
                auth.currentUser?.delete()
                showLoading(false)
            }
    }

    private fun registerFcmToken(uid: String) {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                task.result?.let { token ->
                    updateUserWithToken(uid, token)
                } ?: run {
                    proceedToMain(uid)
                }
            } else {
                proceedToMain(uid)
            }
        }
    }

    private fun updateUserWithToken(uid: String, token: String) {
        database.child("users").child(uid).updateChildren(
            mapOf(
                "fcmToken" to token,
                "lastActive" to ServerValue.TIMESTAMP
            )
        ).addOnCompleteListener {
            proceedToMain(uid)
        }
    }

    private fun proceedWithAuth(uid: String) {
        registerFcmToken(uid)
        // Попытка сохранить OneSignal ID при входе
        NotificationUtils.saveCurrentUserOneSignalIdToDatabase(this)
    }

    private fun proceedToMain(uid: String) {
        database.child("users").child(uid).updateChildren(
            mapOf(
                "online" to true,
                "isActive" to true,
                "lastActive" to ServerValue.TIMESTAMP
            )
        )

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        showLoading(false)
    }

    private fun validateInput(email: String, password: String, username: String): Boolean {
        if (email.isEmpty() || password.isEmpty() || username.isEmpty()) {
            Toast.makeText(this, "Заполните все поля", Toast.LENGTH_SHORT).show()
            return false
        }
        if (password.length < 6) {
            Toast.makeText(this, "Пароль должен содержать минимум 6 символов", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun showLoading(show: Boolean) {
        binding.progressSurface.visibility = if (show) View.VISIBLE else View.GONE
        binding.containerMain.alpha = if (show) 0.3f else 1f
    }
}