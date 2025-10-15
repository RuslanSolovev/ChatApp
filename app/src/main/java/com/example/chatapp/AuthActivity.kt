package com.example.chatapp.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.databinding.ActivityAuthBinding
import com.example.chatapp.models.User
import com.example.chatapp.utils.NotificationUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference

    // Таймауты для сетевых операций (в миллисекундах)
    private companion object {
        private const val TAG = "AuthActivity"
        private const val USER_CHECK_TIMEOUT = 5000L // 5 секунд
        private const val AUTH_OPERATION_TIMEOUT = 10000L // 10 секунд
        private const val DATABASE_OPERATION_TIMEOUT = 8000L // 8 секунд
        private const val FCM_TOKEN_TIMEOUT = 5000L // 5 секунд
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initFirebase()
        setupClickListeners()

        // Проверка пользователя в фоновом потоке с таймаутом
        checkCurrentUserWithLoading()
    }

    private fun initFirebase() {
        auth = Firebase.auth
        database = Firebase.database.reference
    }

    override fun onResume() {
        super.onResume()
        // Сохранение OneSignal ID в фоновом режиме
        if (auth.currentUser != null) {
            lifecycleScope.launch {
                saveOneSignalIdInBackground()
            }
        }
    }

    private suspend fun saveOneSignalIdInBackground() {
        try {
            withContext(Dispatchers.IO) {
                NotificationUtils.saveCurrentUserOneSignalIdToDatabase(this@AuthActivity)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save OneSignal ID in background", e)
            // Не показываем ошибку пользователю - это не критическая операция
        }
    }

    private fun checkCurrentUserWithLoading() {
        showLoading(true)

        lifecycleScope.launch {
            try {
                // Проверка пользователя с таймаутом
                val userExists = withTimeout(USER_CHECK_TIMEOUT) {
                    checkUserExists()
                }

                if (userExists) {
                    auth.currentUser?.uid?.let { uid ->
                        proceedWithAuth(uid)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showLoading(false)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "User check timeout", e)
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(
                        this@AuthActivity,
                        "Проверка авторизации заняла слишком долго",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking user", e)
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    showError("Ошибка проверки авторизации")
                }
            }
        }
    }

    private suspend fun checkUserExists(): Boolean = withContext(Dispatchers.IO) {
        try {
            auth.currentUser != null
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkUserExists", e)
            false
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
            lifecycleScope.launch {
                registerUserWithTimeout(email, password, username)
            }
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
        lifecycleScope.launch {
            loginUserWithTimeout(email, password)
        }
    }

    private suspend fun registerUserWithTimeout(email: String, password: String, username: String) {
        try {
            withTimeout(AUTH_OPERATION_TIMEOUT) {
                registerUser(email, password, username)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Registration timeout", e)
            withContext(Dispatchers.Main) {
                showLoading(false)
                showError("Регистрация заняла слишком долго. Проверьте соединение.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Registration error", e)
            withContext(Dispatchers.Main) {
                showLoading(false)
                showError("Ошибка регистрации: ${e.message}")
            }
        }
    }

    private suspend fun loginUserWithTimeout(email: String, password: String) {
        try {
            withTimeout(AUTH_OPERATION_TIMEOUT) {
                loginUser(email, password)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Login timeout", e)
            withContext(Dispatchers.Main) {
                showLoading(false)
                showError("Вход занял слишком долго. Проверьте соединение.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            withContext(Dispatchers.Main) {
                showLoading(false)
                showError("Ошибка входа: ${e.message}")
            }
        }
    }

    private suspend fun registerUser(email: String, password: String, username: String) {
        withContext(Dispatchers.IO) {
            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    lifecycleScope.launch {
                        if (task.isSuccessful) {
                            auth.currentUser?.uid?.let { uid ->
                                saveUserToDatabaseWithTimeout(uid, email, username)
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                showError("Ошибка регистрации: ${task.exception?.message}")
                                showLoading(false)
                            }
                        }
                    }
                }
        }
    }

    private suspend fun loginUser(email: String, password: String) {
        withContext(Dispatchers.IO) {
            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    lifecycleScope.launch {
                        if (task.isSuccessful) {
                            auth.currentUser?.uid?.let { uid ->
                                withContext(Dispatchers.Main) {
                                    proceedWithAuth(uid)
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                showError("Ошибка входа: ${task.exception?.message}")
                                showLoading(false)
                            }
                        }
                    }
                }
        }
    }

    private suspend fun saveUserToDatabaseWithTimeout(uid: String, email: String, username: String) {
        try {
            withTimeout(DATABASE_OPERATION_TIMEOUT) {
                saveUserToDatabase(uid, email, username)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Save user to database timeout", e)
            // Откатываем регистрацию при таймауте
            auth.currentUser?.delete()?.addOnCompleteListener {
                lifecycleScope.launch {
                    withContext(Dispatchers.Main) {
                        showError("Ошибка сохранения данных. Попробуйте еще раз.")
                        showLoading(false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save user to database error", e)
            // Откатываем регистрацию при ошибке
            auth.currentUser?.delete()?.addOnCompleteListener {
                lifecycleScope.launch {
                    withContext(Dispatchers.Main) {
                        showError("Ошибка сохранения пользователя")
                        showLoading(false)
                    }
                }
            }
        }
    }

    private suspend fun saveUserToDatabase(uid: String, email: String, username: String) {
        withContext(Dispatchers.IO) {
            val user = User(
                uid = uid,
                email = email,
                name = username,
                isActive = true,
                online = true
            )

            database.child("users").child(uid).setValue(user)
                .addOnSuccessListener {
                    lifecycleScope.launch {
                        registerFcmTokenWithTimeout(uid)
                        // Сохраняем OneSignal ID после регистрации в фоне
                        saveOneSignalIdInBackground()
                    }
                }
                .addOnFailureListener { e ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.Main) {
                            showError("Ошибка сохранения пользователя")
                            // Пытаемся удалить пользователя при ошибке
                            auth.currentUser?.delete()
                            showLoading(false)
                        }
                    }
                }
        }
    }

    private suspend fun registerFcmTokenWithTimeout(uid: String) {
        try {
            withTimeout(FCM_TOKEN_TIMEOUT) {
                registerFcmToken(uid)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "FCM token registration timeout, proceeding without token")
            // Продолжаем без FCM токена
            withContext(Dispatchers.Main) {
                proceedToMain(uid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "FCM token registration error", e)
            // Продолжаем без FCM токена
            withContext(Dispatchers.Main) {
                proceedToMain(uid)
            }
        }
    }

    private suspend fun registerFcmToken(uid: String) {
        withContext(Dispatchers.IO) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                lifecycleScope.launch {
                    if (task.isSuccessful) {
                        task.result?.let { token ->
                            updateUserWithTokenWithTimeout(uid, token)
                        } ?: run {
                            withContext(Dispatchers.Main) {
                                proceedToMain(uid)
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            proceedToMain(uid)
                        }
                    }
                }
            }
        }
    }

    private suspend fun updateUserWithTokenWithTimeout(uid: String, token: String) {
        try {
            withTimeout(DATABASE_OPERATION_TIMEOUT) {
                updateUserWithToken(uid, token)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Update user with token timeout, proceeding without update")
            withContext(Dispatchers.Main) {
                proceedToMain(uid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update user with token error", e)
            withContext(Dispatchers.Main) {
                proceedToMain(uid)
            }
        }
    }

    private suspend fun updateUserWithToken(uid: String, token: String) {
        withContext(Dispatchers.IO) {
            database.child("users").child(uid).updateChildren(
                mapOf(
                    "fcmToken" to token,
                    "lastActive" to ServerValue.TIMESTAMP
                )
            ).addOnCompleteListener {
                lifecycleScope.launch {
                    withContext(Dispatchers.Main) {
                        proceedToMain(uid)
                    }
                }
            }
        }
    }

    private fun proceedWithAuth(uid: String) {
        lifecycleScope.launch {
            registerFcmTokenWithTimeout(uid)
            // Сохраняем OneSignal ID в фоне
            saveOneSignalIdInBackground()
        }
    }

    private fun proceedToMain(uid: String) {
        // Обновляем статус пользователя в фоне
        lifecycleScope.launch {
            updateUserStatus(uid)
        }

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    private suspend fun updateUserStatus(uid: String) {
        try {
            withContext(Dispatchers.IO) {
                database.child("users").child(uid).updateChildren(
                    mapOf(
                        "online" to true,
                        "isActive" to true,
                        "lastActive" to ServerValue.TIMESTAMP
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update user status", e)
            // Не критично, продолжаем
        }
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

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AuthActivity destroyed")
    }
}