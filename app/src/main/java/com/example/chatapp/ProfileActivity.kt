package com.example.chatapp.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.chatapp.R
import com.example.chatapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class ProfileActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var currentUser: User

    // UI elements
    private lateinit var ivProfilePicture: ImageView
    private lateinit var btnUploadPhoto: Button
    private lateinit var etEmail: EditText
    private lateinit var etFirstName: EditText
    private lateinit var etLastName: EditText
    private lateinit var etMiddleName: EditText
    private lateinit var etAdditionalInfo: EditText
    private lateinit var btnSaveProfile: Button
    private lateinit var btnDeleteProfile: Button

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                uploadProfilePicture(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.profile_activity)

        initViews()
        initFirebase()
        loadProfileData()
        setupClickListeners()
    }

    private fun initViews() {
        ivProfilePicture = findViewById(R.id.ivProfilePicture)
        btnUploadPhoto = findViewById(R.id.btnUploadPhoto)
        etEmail = findViewById(R.id.etEmail)
        etFirstName = findViewById(R.id.etFirstName)
        etLastName = findViewById(R.id.etLastName)
        etMiddleName = findViewById(R.id.etMiddleName)
        etAdditionalInfo = findViewById(R.id.etAdditionalInfo)
        btnSaveProfile = findViewById(R.id.btnSaveProfile)
        btnDeleteProfile = findViewById(R.id.btnDeleteProfile)

        // Disable email editing
        etEmail.isEnabled = false
    }

    private fun initFirebase() {
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        currentUser = User().apply {
            uid = auth.currentUser?.uid ?: ""
            email = auth.currentUser?.email ?: ""
        }
    }

    private fun setupClickListeners() {
        btnUploadPhoto.setOnClickListener {
            openImagePicker()
        }

        btnSaveProfile.setOnClickListener {
            saveProfileData()
        }

        btnDeleteProfile.setOnClickListener {
            deleteProfileData()
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
        pickImageLauncher.launch(intent)
    }

    private fun uploadProfilePicture(imageUri: Uri) {
        val userId = auth.currentUser?.uid ?: return

        // Получаем InputStream из URI
        val inputStream = contentResolver.openInputStream(imageUri)

        // Создаём тело запроса для отправки файла
        val body = inputStream?.let { stream ->
            stream.use {
                it.readBytes().toRequestBody("image/jpeg".toMediaTypeOrNull())
            }
        }

        if (body == null) {
            Toast.makeText(this, "Ошибка чтения файла", Toast.LENGTH_SHORT).show()
            return
        }

        // Создаём multipart/form-data запрос
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "profile_image.jpg", body)
            .addFormDataPart("userId", userId) // Добавляем ID пользователя
            .build()

        // Отправляем запрос на ваш сервер
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://auspicious-cosmic-can.glitch.me/upload") // URL вашего сервера
            .post(requestBody)
            .build()

        btnUploadPhoto.isEnabled = false
        btnUploadPhoto.text = getString(R.string.uploading)

        // Выполняем запрос асинхронно
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    handleUploadError(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (!responseBody.isNullOrEmpty()) {
                        try {
                            // Предполагаем, что сервер возвращает JSON с полем "url"
                            val imageUrl = JSONObject(responseBody).getString("url")
                            Log.d("ProfileActivity", "Получен URL изображения: $imageUrl")

                            // Если сервер возвращает HTTP, преобразуем его в HTTPS
                            val secureImageUrl = imageUrl.replace("http://", "https://")

                            runOnUiThread {
                                currentUser.profileImageUrl = secureImageUrl
                                updateProfileImage(secureImageUrl)
                                saveImageUrlToDatabase(secureImageUrl) // Сохраняем URL в базе данных
                                btnUploadPhoto.isEnabled = true
                                btnUploadPhoto.text = getString(R.string.change_photo)
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                handleUploadError(IOException("Ошибка парсинга ответа сервера"))
                            }
                        }
                    } else {
                        runOnUiThread {
                            handleUploadError(IOException("Сервер не вернул URL"))
                        }
                    }
                } else {
                    runOnUiThread {
                        handleUploadError(IOException("Ошибка сервера: ${response.code}"))
                    }
                }
            }
        })
    }

    private fun updateProfileImage(imageUrl: String) {
        Glide.with(this)
            .load(imageUrl)
            .circleCrop()
            .placeholder(R.drawable.ic_default_profile)
            .error(R.drawable.ic_default_profile) // Добавляем placeholder для ошибок
            .into(ivProfilePicture)
    }

    private fun saveImageUrlToDatabase(imageUrl: String) {
        val userId = auth.currentUser?.uid ?: return
        database.reference.child("users").child(userId).child("profileImageUrl")
            .setValue(imageUrl)
            .addOnSuccessListener {
                Log.d("ProfileActivity", "URL изображения успешно сохранён в базе данных: $imageUrl")
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    getString(R.string.image_url_save_error, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun handleUploadError(e: Exception) {
        Log.e("ProfileActivity", "Ошибка загрузки изображения", e)
        runOnUiThread {
            Toast.makeText(
                this,
                "Ошибка загрузки: ${e.message ?: "Неизвестная ошибка"}",
                Toast.LENGTH_LONG
            ).show()
            btnUploadPhoto.isEnabled = true
            btnUploadPhoto.text = getString(R.string.upload_photo)
        }
    }

    private fun loadProfileData() {
        val userId = auth.currentUser?.uid ?: return
        database.reference.child("users").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val user = snapshot.getValue(User::class.java)
                    user?.let {
                        currentUser = it
                        updateUI()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(
                        this@ProfileActivity,
                        getString(R.string.profile_load_error, error.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    private fun updateUI() {
        etEmail.setText(currentUser.email)
        etFirstName.setText(currentUser.firstName)
        etLastName.setText(currentUser.lastName)
        etMiddleName.setText(currentUser.middleName)
        etAdditionalInfo.setText(currentUser.additionalInfo)
        currentUser.profileImageUrl?.let { url ->
            updateProfileImage(url)
        } ?: run {
            ivProfilePicture.setImageResource(R.drawable.ic_default_profile)
        }
    }

    private fun saveProfileData() {
        currentUser.apply {
            firstName = etFirstName.text.toString().trim()
            lastName = etLastName.text.toString().trim()
            middleName = etMiddleName.text.toString().trim()
            additionalInfo = etAdditionalInfo.text.toString().trim()
        }

        if (currentUser.firstName.isEmpty() || currentUser.lastName.isEmpty()) {
            Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show()
            return
        }

        btnSaveProfile.isEnabled = false
        btnSaveProfile.text = getString(R.string.saving)

        database.reference.child("users").child(currentUser.uid).setValue(currentUser)
            .addOnSuccessListener {
                Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
                btnSaveProfile.isEnabled = true
                btnSaveProfile.text = getString(R.string.save_profile)
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    getString(R.string.save_error, e.message),
                    Toast.LENGTH_SHORT
                ).show()
                btnSaveProfile.isEnabled = true
                btnSaveProfile.text = getString(R.string.save_profile)
            }
    }

    private fun deleteProfileData() {
        val userId = auth.currentUser?.uid ?: return

        btnDeleteProfile.isEnabled = false
        btnDeleteProfile.text = getString(R.string.deleting)

        // Удаляем данные профиля (оставляем только важные поля)
        val updates = hashMapOf<String, Any?>(
            "firstName" to null,
            "lastName" to null,
            "middleName" to null,
            "additionalInfo" to null,
            "profileImageUrl" to null
        )

        database.reference.child("users").child(userId).updateChildren(updates)
            .addOnSuccessListener {
                // Удаляем изображение профиля с сервера, если оно существует
                currentUser.profileImageUrl?.let { url ->
                    deleteImageFromServer(url)
                }
                Toast.makeText(this, R.string.profile_data_deleted, Toast.LENGTH_SHORT).show()
                resetProfileUI()
                btnDeleteProfile.isEnabled = true
                btnDeleteProfile.text = getString(R.string.delete_profile_data)
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    getString(R.string.delete_error, e.message),
                    Toast.LENGTH_SHORT
                ).show()
                btnDeleteProfile.isEnabled = true
                btnDeleteProfile.text = getString(R.string.delete_profile_data)
            }
    }

    private fun deleteImageFromServer(imageUrl: String) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://auspicious-cosmic-can.glitch.me/delete") // Замените на URL вашего сервера для удаления
            .delete()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ProfileActivity", "Ошибка удаления изображения", e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e("ProfileActivity", "Ошибка удаления изображения: ${response.code}")
                }
            }
        })
    }

    private fun resetProfileUI() {
        etFirstName.text.clear()
        etLastName.text.clear()
        etMiddleName.text.clear()
        etAdditionalInfo.text.clear()
        ivProfilePicture.setImageResource(R.drawable.ic_default_profile)
        currentUser.profileImageUrl = null
    }
}