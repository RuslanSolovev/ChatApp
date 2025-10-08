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
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.chatapp.R
import com.example.chatapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ProfileActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var currentUser: User

    // UI elements
    private lateinit var ivProfilePicture: ImageView
    private lateinit var btnUploadPhoto: Button
    private lateinit var etEmail: EditText
    private lateinit var etName: EditText
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

    companion object {
        private const val TAG = "ProfileActivity"
        private const val BUCKET_NAME = "chatskii"
        private const val REGION = "ru-central1"
        private const val SERVICE = "s3"
        private const val REQUEST_TYPE = "aws4_request"
        private const val ALGORITHM = "AWS4-HMAC-SHA256"

        // Временное решение - в продакшене заменить на серверную логику
        private const val ACCESS_KEY_ID = "YCAJEIgiTghuX8JsxiQJUQIlM"
        private const val SECRET_ACCESS_KEY = "YCOVaI5JOrYqyLHWSiIYvV3Qa-N5T3lGmCMFwIvk"
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
        etName = findViewById(R.id.etFirstName)
        etLastName = findViewById(R.id.etLastName)
        etMiddleName = findViewById(R.id.etMiddleName)
        etAdditionalInfo = findViewById(R.id.etAdditionalInfo)
        btnSaveProfile = findViewById(R.id.btnSaveProfile)
        btnDeleteProfile = findViewById(R.id.btnDeleteProfile)

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
        btnUploadPhoto.setOnClickListener { openImagePicker() }
        btnSaveProfile.setOnClickListener { saveProfileData() }
        btnDeleteProfile.setOnClickListener { deleteProfileData() }
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
        lifecycleScope.launch(Dispatchers.Main) {
            btnUploadPhoto.isEnabled = false
            btnUploadPhoto.text = getString(R.string.uploading)

            try {
                val tempFile = withContext(Dispatchers.IO) { createTempFileFromUri(imageUri) }
                val fileName = "profile_${userId}_${System.currentTimeMillis()}.jpg"
                val imageUrl = withContext(Dispatchers.IO) {
                    uploadToYandexStorage(tempFile, fileName)
                }

                currentUser.profileImageUrl = imageUrl
                updateProfileImage(imageUrl)
                saveImageUrlToDatabase(imageUrl)

                Toast.makeText(
                    this@ProfileActivity,
                    "Изображение успешно загружено",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки изображения", e)
                Toast.makeText(
                    this@ProfileActivity,
                    "Ошибка загрузки: ${e.message ?: "Неизвестная ошибка"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                btnUploadPhoto.isEnabled = true
                btnUploadPhoto.text = getString(R.string.upload_photo)
            }
        }
    }

    private fun uploadToYandexStorage(file: File, fileName: String): String {
        val endpoint = "https://storage.yandexcloud.net"
        val url = "$endpoint/$BUCKET_NAME/$fileName"

        val now = System.currentTimeMillis()
        val date = formatDate(now, "yyyyMMdd")
        val time = formatDate(now, "yyyyMMdd'T'HHmmss'Z'")

        // 1. Подготавливаем заголовки
        val headers = mapOf(
            "Host" to "storage.yandexcloud.net",
            "X-Amz-Date" to time,
            "X-Amz-Content-Sha256" to "UNSIGNED-PAYLOAD",
            "x-amz-acl" to "public-read"
        )

        // 2. Создаем канонический запрос
        val signedHeaders = headers.keys.joinToString(";").lowercase()
        val canonicalHeaders = headers.entries.joinToString("\n") {
            "${it.key.lowercase()}:${it.value}"
        } + "\n"

        val canonicalRequest = """
            |PUT
            |/$BUCKET_NAME/$fileName
            |
            |$canonicalHeaders
            |$signedHeaders
            |UNSIGNED-PAYLOAD
        """.trimMargin()

        Log.d(TAG, "Canonical Request:\n$canonicalRequest")

        // 3. Создаем строку для подписи
        val credentialScope = "$date/$REGION/$SERVICE/$REQUEST_TYPE"
        val stringToSign = """
            |$ALGORITHM
            |$time
            |$credentialScope
            |${sha256(canonicalRequest)}
        """.trimMargin()

        Log.d(TAG, "String to Sign:\n$stringToSign")

        // 4. Вычисляем подпись
        val signature = calculateSignature(
            stringToSign,
            date,
            REGION,
            SERVICE
        )

        // 5. Формируем заголовок авторизации
        val authorizationHeader =
            "$ALGORITHM Credential=$ACCESS_KEY_ID/$credentialScope, " +
                    "SignedHeaders=$signedHeaders, Signature=$signature"

        Log.d(TAG, "Authorization: $authorizationHeader")

        // 6. Формируем запрос
        val client = OkHttpClient()
        val requestBody = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .header("Authorization", authorizationHeader)
            .header("x-amz-acl", "public-read")
            .header("x-amz-content-sha256", "UNSIGNED-PAYLOAD")
            .header("x-amz-date", time)
            .header("Host", "storage.yandexcloud.net")
            .build()

        // 7. Выполняем запрос
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Empty error body"
                Log.e(TAG, "HTTP Error ${response.code}: $errorBody")
                throw IOException("HTTP Error ${response.code}: $errorBody")
            }
            return url
        }
    }

    private fun formatDate(timestamp: Long, pattern: String): String {
        val formatter = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
        formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return formatter.format(java.util.Date(timestamp))
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun calculateSignature(
        stringToSign: String,
        date: String,
        region: String,
        service: String
    ): String {
        val kSecret = ("AWS4$SECRET_ACCESS_KEY").toByteArray(Charsets.UTF_8)
        val kDate = hmacSha256(kSecret, date)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        val kSigning = hmacSha256(kService, "aws4_request")
        return hmacSha256Hex(kSigning, stringToSign)
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val algorithm = "HmacSHA256"
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        val bytes = hmacSha256(key, data)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun createTempFileFromUri(uri: Uri): File {
        return File.createTempFile("upload_", ".jpg", externalCacheDir).apply {
            contentResolver.openInputStream(uri)?.use { input ->
                outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Ошибка чтения файла")
        }
    }

    private fun updateProfileImage(imageUrl: String) {
        Glide.with(this)
            .load(imageUrl)
            .circleCrop()
            .placeholder(R.drawable.ic_default_profile)
            .error(R.drawable.ic_default_profile)
            .into(ivProfilePicture)
    }

    private fun saveImageUrlToDatabase(imageUrl: String) {
        val userId = auth.currentUser?.uid ?: return
        database.reference.child("users").child(userId).child("profileImageUrl")
            .setValue(imageUrl)
            .addOnSuccessListener {
                Log.d(TAG, "URL изображения сохранён: $imageUrl")
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Ошибка сохранения URL: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun loadProfileData() {
        val userId = auth.currentUser?.uid ?: return
        database.reference.child("users").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.getValue(User::class.java)?.let {
                        currentUser = it
                        updateUI()
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(
                        this@ProfileActivity,
                        "Ошибка загрузки профиля: ${error.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    private fun updateUI() {
        etEmail.setText(currentUser.email)
        etName.setText(currentUser.name)
        etLastName.setText(currentUser.lastName)
        etMiddleName.setText(currentUser.middleName)
        etAdditionalInfo.setText(currentUser.additionalInfo)
        currentUser.profileImageUrl?.let { updateProfileImage(it) }
            ?: ivProfilePicture.setImageResource(R.drawable.ic_default_profile)
    }

    private fun saveProfileData() {
        currentUser.apply {
            name = etName.text.toString().trim()
            lastName = etLastName.text.toString().trim()
            middleName = etMiddleName.text.toString().trim()
            additionalInfo = etAdditionalInfo.text.toString().trim()
        }

        if (currentUser.name.isEmpty() || currentUser.lastName.isEmpty()) {
            Toast.makeText(this, "Имя и фамилия обязательны", Toast.LENGTH_SHORT).show()
            return
        }

        btnSaveProfile.isEnabled = false
        btnSaveProfile.text = "Сохранение..."

        database.reference.child("users").child(currentUser.uid).setValue(currentUser)
            .addOnSuccessListener {
                Toast.makeText(this, "Профиль сохранён", Toast.LENGTH_SHORT).show()
                btnSaveProfile.isEnabled = true
                btnSaveProfile.text = "Сохранить профиль"
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
                btnSaveProfile.isEnabled = true
                btnSaveProfile.text = "Сохранить профиль"
            }
    }

    private fun deleteProfileData() {
        val userId = auth.currentUser?.uid ?: return
        btnDeleteProfile.isEnabled = false
        btnDeleteProfile.text = "Удаление..."

        val updates = mapOf<String, Any?>(
            "name" to null,
            "lastName" to null,
            "middleName" to null,
            "additionalInfo" to null,
            "profileImageUrl" to null
        )

        database.reference.child("users").child(userId).updateChildren(updates)
            .addOnSuccessListener {
                currentUser.profileImageUrl?.let { deleteImageFromServer(it) }
                Toast.makeText(this, "Данные профиля удалены", Toast.LENGTH_SHORT).show()
                resetProfileUI()
                btnDeleteProfile.isEnabled = true
                btnDeleteProfile.text = "Удалить данные"
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Ошибка удаления: ${e.message}", Toast.LENGTH_SHORT).show()
                btnDeleteProfile.isEnabled = true
                btnDeleteProfile.text = "Удалить данные"
            }
    }

    private fun deleteImageFromServer(imageUrl: String) {
        val deleteUrl = "https://auspicious-cosmic-can.glitch.me/delete"
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(deleteUrl)
            .delete()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Ошибка удаления изображения", e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e(TAG, "Ошибка удаления: ${response.code}")
                }
            }
        })
    }

    private fun resetProfileUI() {
        etName.text.clear()
        etLastName.text.clear()
        etMiddleName.text.clear()
        etAdditionalInfo.text.clear()
        ivProfilePicture.setImageResource(R.drawable.ic_default_profile)
        currentUser.profileImageUrl = null
    }
}