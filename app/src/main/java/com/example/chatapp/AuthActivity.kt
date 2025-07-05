package com.example.chatapp.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.R
import com.example.chatapp.databinding.ActivityAuthBinding
import com.example.chatapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import com.onesignal.OneSignal
import java.util.concurrent.TimeUnit

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference

    companion object {
        private const val TAG = "AuthActivity"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val MAX_TIME_OFFSET_MINUTES = 30L
        private const val ONESIGNAL_APP_ID = "0083de8f-7ca0-4824-ac88-9c037278237e" // Замените на реальный App ID
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация OneSignal
        OneSignal.initWithContext(this)
        OneSignal.setAppId(ONESIGNAL_APP_ID)

        initFirebase()
        setupClickListeners()
        checkCurrentUser()
    }

    private fun initFirebase() {
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().reference
    }

    private fun checkCurrentUser() {
        auth.currentUser?.let { user ->
            checkDeviceTimeSync(
                onSuccess = { isTimeSynced ->
                    if (isTimeSynced) {
                        checkExistingTokenBeforeUpdate(user.uid)
                    } else {
                        showWarning("Время на устройстве расходится с серверным. Некоторые функции могут работать некорректно")
                        proceedWithAuth(user.uid)
                    }
                },
                onFailure = {
                    showWarning("Не удалось проверить время сервера. Продолжаем авторизацию")
                    proceedWithAuth(user.uid)
                }
            )
        }
    }

    private fun setupClickListeners() {
        binding.apply {
            btnRegister.setOnClickListener { handleRegistration() }
            btnLogin.setOnClickListener { handleLogin() }
        }
    }

    private fun handleRegistration() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()

        when {
            email.isEmpty() -> showError("Введите email")
            password.isEmpty() -> showError("Введите пароль")
            username.isEmpty() -> showError("Введите имя пользователя")
            password.length < 6 -> showError("Пароль должен содержать минимум 6 символов")
            !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> showError("Введите корректный email")
            else -> registerUserWithRetry(email, password, username, MAX_RETRY_ATTEMPTS)
        }
    }

    private fun handleLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        when {
            email.isEmpty() -> showError("Введите email")
            password.isEmpty() -> showError("Введите пароль")
            else -> loginUserWithRetry(email, password, MAX_RETRY_ATTEMPTS)
        }
    }

    private fun registerUserWithRetry(email: String, password: String, username: String, attemptsLeft: Int) {
        showProgress(true)
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    auth.currentUser?.uid?.let { uid ->
                        saveUserToDatabase(uid, email, username)
                    } ?: run {
                        showError("Ошибка создания пользователя")
                        showProgress(false)
                    }
                } else {
                    if (attemptsLeft > 0 && isRetryableError(task.exception)) {
                        binding.root.postDelayed({
                            registerUserWithRetry(email, password, username, attemptsLeft - 1)
                        }, RETRY_DELAY_MS)
                    } else {
                        showError("Ошибка регистрации: ${task.exception?.message ?: "Неизвестная ошибка"}")
                        showProgress(false)
                    }
                }
            }
    }

    private fun isRetryableError(exception: Exception?): Boolean {
        return when (exception?.message) {
            "A network error (such as timeout, interrupted connection or unreachable host) has occurred." -> true
            "The connection to the server was unsuccessful." -> true
            else -> false
        }
    }

    private fun saveUserToDatabase(uid: String, email: String, username: String) {
        val user = createUser(uid = uid, email = email, username = username, fcmToken = null)
        database.child("users").child(uid).setValue(user)
            .addOnSuccessListener {
                registerFcmTokenWithRetry(uid, MAX_RETRY_ATTEMPTS)
            }
            .addOnFailureListener { e ->
                showError("Ошибка сохранения пользователя: ${e.message}")
                auth.currentUser?.delete()?.addOnCompleteListener {
                    showProgress(false)
                }
            }
    }

    private fun loginUserWithRetry(email: String, password: String, attemptsLeft: Int) {
        showProgress(true)
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    auth.currentUser?.uid?.let { uid ->
                        checkDeviceTimeSync(
                            onSuccess = { isTimeSynced ->
                                if (!isTimeSynced) {
                                    showWarning("Время на устройстве расходится с серверным")
                                }
                                proceedWithAuth(uid)
                            },
                            onFailure = {
                                showWarning("Не удалось проверить время сервера")
                                proceedWithAuth(uid)
                            }
                        )
                    } ?: run {
                        showError("Пользователь не найден")
                        showProgress(false)
                    }
                } else {
                    if (attemptsLeft > 0 && isRetryableError(task.exception)) {
                        binding.root.postDelayed({
                            loginUserWithRetry(email, password, attemptsLeft - 1)
                        }, RETRY_DELAY_MS)
                    } else {
                        showError("Ошибка входа: ${task.exception?.message ?: "Неизвестная ошибка"}")
                        showProgress(false)
                    }
                }
            }
    }

    private fun proceedWithAuth(uid: String) {
        checkExistingTokenBeforeUpdate(uid)
    }

    private fun checkExistingTokenBeforeUpdate(uid: String) {
        database.child("users").child(uid).child("fcmToken")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    registerFcmTokenWithRetry(uid, MAX_RETRY_ATTEMPTS)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "Failed to get current token, proceeding anyway", error.toException())
                    registerFcmTokenWithRetry(uid, MAX_RETRY_ATTEMPTS)
                }
            })
    }

    private fun registerFcmTokenWithRetry(uid: String, attemptsLeft: Int) {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                task.result?.let { token ->
                    updateUserWithToken(uid, token)
                } ?: run {
                    if (attemptsLeft > 0) {
                        retryFcmTokenRegistration(uid, attemptsLeft - 1)
                    } else {
                        Log.w(TAG, "FCM token is null, proceeding without it")
                        updateUserStatus(uid, null)
                    }
                }
            } else if (attemptsLeft > 0) {
                retryFcmTokenRegistration(uid, attemptsLeft - 1)
            } else {
                Log.w(TAG, "Failed to get FCM token: ${task.exception?.message}")
                updateUserStatus(uid, null)
            }
        }
    }

    private fun retryFcmTokenRegistration(uid: String, attemptsLeft: Int) {
        binding.root.postDelayed({
            registerFcmTokenWithRetry(uid, attemptsLeft)
        }, RETRY_DELAY_MS)
    }

    private fun updateUserWithToken(uid: String, token: String) {
        val updates = hashMapOf<String, Any>(
            "isActive" to true,
            "lastActive" to ServerValue.TIMESTAMP,
            "fcmToken" to token
        )

        database.child("users").child(uid).updateChildren(updates)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    saveOneSignalId(uid)
                    navigateToMainActivity()
                } else {
                    showError("Ошибка обновления токена, но вход выполнен")
                    saveOneSignalId(uid)
                    navigateToMainActivity()
                }
                showProgress(false)
            }
    }

    private fun updateUserStatus(uid: String, token: String?) {
        val updates = hashMapOf<String, Any>(
            "isActive" to true,
            "lastActive" to ServerValue.TIMESTAMP
        )
        token?.let { updates["fcmToken"] = it }

        database.child("users").child(uid).updateChildren(updates)
            .addOnCompleteListener { task ->
                saveOneSignalId(uid)
                showProgress(false)
                navigateToMainActivity()
            }
    }

    private fun checkDeviceTimeSync(onSuccess: (Boolean) -> Unit, onFailure: () -> Unit) {
        database.child(".info/serverTimeOffset").addListenerForSingleValueEvent(
            object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        val offset = snapshot.getValue(Long::class.java) ?: 0
                        onSuccess(kotlin.math.abs(offset) < TimeUnit.MINUTES.toMillis(MAX_TIME_OFFSET_MINUTES))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing time offset", e)
                        onFailure()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Failed to check server time", error.toException())
                    onFailure()
                }
            }
        )
    }

    private fun createUser(uid: String, email: String, username: String, fcmToken: String?): User {
        return User(
            uid = uid,
            email = email,
            name = username,
            profileImageUrl = "",
            isActive = true,
            fcmToken = fcmToken,
            lastActive = null
        )
    }

    private fun showProgress(show: Boolean) {
        binding.apply {
            progressBar.visibility = if (show) View.VISIBLE else View.GONE
            containerMain.visibility = if (show) View.GONE else View.VISIBLE
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.e(TAG, message)
    }

    private fun showWarning(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.w(TAG, message)
    }

    private fun navigateToMainActivity() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun saveOneSignalId(uid: String) {
        val deviceState = OneSignal.getDeviceState()
        if (deviceState?.userId != null && deviceState.userId.isNotBlank()) {
            database.child("users").child(uid).child("oneSignalId")
                .setValue(deviceState.userId)
                .addOnSuccessListener {
                    Log.d(TAG, "OneSignal ID сохранён: ${deviceState.userId}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Ошибка сохранения OneSignal ID", e)
                }
        } else {
            // Повторная попытка через 2 секунды
            binding.root.postDelayed({
                saveOneSignalId(uid)
            }, 2000)
        }
    }
}