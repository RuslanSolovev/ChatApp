package com.example.chatapp.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.databinding.ActivityCreateChatBinding
import com.example.chatapp.models.Chat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class CreateChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateChatBinding
    private lateinit var auth: FirebaseAuth
    private val database: FirebaseDatabase = Firebase.database
    private val currentUserId get() = auth.currentUser?.uid ?: ""
    private var selectedImageUri: Uri? = null
    private val PICK_IMAGE_REQUEST = 1
    private val PERMISSION_REQUEST_CODE = 100

    companion object {
        private const val TAG = "CreateChatActivity"
        private const val BUCKET_NAME = "chatskii"
        private const val REGION = "ru-central1"
        private const val SERVICE = "s3"
        private const val REQUEST_TYPE = "aws4_request"
        private const val ALGORITHM = "AWS4-HMAC-SHA256"

        // Ваши ключи доступа к Yandex Cloud Storage
        private const val ACCESS_KEY_ID = "YCAJEIgiTghuX8JsxiQJUQIlM"
        private const val SECRET_ACCESS_KEY = "YCOVaI5JOrYqyLHWSiIYvV3Qa-N5T3lGmCMFwIvk"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        binding.btnCreateChat.setOnClickListener {
            createNewChat()
        }

        binding.btnUploadImage.setOnClickListener {
            checkPermissionAndPickImage()
        }
    }

    private fun checkPermissionAndPickImage() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            openImagePicker()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun openImagePicker() {
        val intent = Intent().apply {
            type = "image/*"
            action = Intent.ACTION_GET_CONTENT
        }
        startActivityForResult(Intent.createChooser(intent, "Выберите изображение"), PICK_IMAGE_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openImagePicker()
            } else {
                Toast.makeText(this, "Разрешение на доступ к хранилищу необходимо для загрузки изображений", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null && data.data != null) {
            selectedImageUri = data.data
            Toast.makeText(this, "Изображение выбрано", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNewChat() {
        val chatName = binding.etChatName.text.toString().trim()

        if (chatName.isEmpty()) {
            binding.etChatName.error = "Введите название чата"
            return
        }

        if (currentUserId.isEmpty()) {
            Toast.makeText(this, "Ошибка аутентификации", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnCreateChat.isEnabled = false

        if (selectedImageUri != null) {
            uploadImageAndCreateChat(chatName)
        } else {
            createChatWithoutImage(chatName)
        }
    }

    private fun uploadImageAndCreateChat(chatName: String) {
        val userId = currentUserId
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val tempFile = withContext(Dispatchers.IO) { createTempFileFromUri(selectedImageUri!!) }
                // Используем безопасное имя файла без специальных символов
                val fileName = "chat_${System.currentTimeMillis()}.jpg"
                val imageUrl = withContext(Dispatchers.IO) {
                    uploadToYandexStorage(tempFile, fileName)
                }

                createChatWithImageUrl(chatName, imageUrl)

                Toast.makeText(this@CreateChatActivity, "Изображение загружено", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки изображения", e)
                showError("Ошибка загрузки: ${e.message ?: "Неизвестная ошибка"}")
            }
        }
    }

    private fun uploadToYandexStorage(file: File, fileName: String): String {
        val endpoint = "https://storage.yandexcloud.net"
        val url = "$endpoint/$BUCKET_NAME/$fileName"

        val now = System.currentTimeMillis()
        val date = formatDate(now, "yyyyMMdd")
        val time = formatDate(now, "yyyyMMdd'T'HHmmss'Z'")

        // Подготавливаем заголовки
        val headers = mapOf(
            "host" to "storage.yandexcloud.net",
            "x-amz-date" to time,
            "x-amz-content-sha256" to "UNSIGNED-PAYLOAD",
            "x-amz-acl" to "public-read"
        )

        // Создаем канонический запрос (важно: путь должен быть закодирован)
        val canonicalUri = "/$BUCKET_NAME/$fileName"
        val sortedHeaderNames = headers.keys.sorted()
        val signedHeaders = sortedHeaderNames.joinToString(";")
        val canonicalHeaders = sortedHeaderNames.joinToString("\n") { name ->
            "$name:${headers[name]}"
        } + "\n"

        val canonicalRequest = """
            |PUT
            |$canonicalUri
            |
            |$canonicalHeaders
            |$signedHeaders
            |UNSIGNED-PAYLOAD
        """.trimMargin().replace("\n", "\n") // Убедимся в правильных переводах строк

        Log.d(TAG, "Canonical Request:\n$canonicalRequest")

        // Создаем строку для подписи
        val credentialScope = "$date/$REGION/$SERVICE/$REQUEST_TYPE"
        val stringToSign = """
            |$ALGORITHM
            |$time
            |$credentialScope
            |${sha256Hex(canonicalRequest)}
        """.trimMargin().replace("\n", "\n")

        Log.d(TAG, "String to Sign:\n$stringToSign")

        // Вычисляем подпись
        val signature = calculateSignature(
            stringToSign,
            date,
            REGION,
            SERVICE
        )

        // Формируем заголовок авторизации
        val authorizationHeader =
            "$ALGORITHM Credential=$ACCESS_KEY_ID/$credentialScope, " +
                    "SignedHeaders=$signedHeaders, Signature=$signature"

        Log.d(TAG, "Authorization: $authorizationHeader")

        // Формируем и выполняем запрос
        val client = OkHttpClient()
        val requestBody = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .addHeader("Authorization", authorizationHeader)
            .addHeader("x-amz-acl", "public-read")
            .addHeader("x-amz-content-sha256", "UNSIGNED-PAYLOAD")
            .addHeader("x-amz-date", time)
            .build()

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
        val formatter = java.text.SimpleDateFormat(pattern, Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(timestamp))
    }

    private fun sha256Hex(input: String): String {
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

    private fun createChatWithImageUrl(chatName: String, imageUrl: String) {
        val chatId = database.reference.child("chats").push().key
            ?: run {
                showError("Ошибка генерации ID чата")
                binding.btnCreateChat.isEnabled = true
                return
            }

        val chat = Chat(
            chatId = chatId,
            name = chatName,
            lastMessage = "Чат создан",
            participants = mapOf(currentUserId to true),
            creatorId = currentUserId,
            imageUrl = imageUrl,
            createdAt = System.currentTimeMillis()
        )

        saveChatToDatabase(chat)
    }

    private fun createChatWithoutImage(chatName: String) {
        val chatId = database.reference.child("chats").push().key
            ?: run {
                showError("Ошибка генерации ID чата")
                binding.btnCreateChat.isEnabled = true
                return
            }

        val chat = Chat(
            chatId = chatId,
            name = chatName,
            lastMessage = "Чат создан",
            participants = mapOf(currentUserId to true),
            creatorId = currentUserId,
            createdAt = System.currentTimeMillis()
        )

        saveChatToDatabase(chat)
    }

    private fun saveChatToDatabase(chat: Chat) {
        val updates = hashMapOf<String, Any>(
            "chats/${chat.chatId}" to chat,
            "users/$currentUserId/chats/${chat.chatId}" to true
        )

        database.reference.updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(this, "Чат ${chat.name} создан", Toast.LENGTH_SHORT).show()
                navigateToChatDetail(chat.chatId)
            }
            .addOnFailureListener { e ->
                showError("Ошибка создания чата: ${e.message}")
            }
            .addOnCompleteListener {
                binding.btnCreateChat.isEnabled = true
            }
    }

    private fun navigateToChatDetail(chatId: String) {
        Intent(this, ChatDetailActivity::class.java).apply {
            putExtra("chatId", chatId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }.also { startActivity(it) }
        finish()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        binding.btnCreateChat.isEnabled = true
    }
}