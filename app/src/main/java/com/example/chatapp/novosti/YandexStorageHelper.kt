package com.example.chatapp.novosti

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class YandexStorageHelper(private val context: Context) {

    companion object {
        private const val TAG = "YandexStorageHelper"
        private const val BUCKET_NAME = "chatskii"
        private const val ENDPOINT = "https://storage.yandexcloud.net"
        private const val REGION = "ru-central1"
        private const val SERVICE = "s3"
        private const val REQUEST_TYPE = "aws4_request"
        private const val ALGORITHM = "AWS4-HMAC-SHA256"

        // Временно, пока не вынесем в конфиг
        private const val ACCESS_KEY_ID = "YCAJEIgiTghuX8JsxiQJUQIlM"
        private const val SECRET_ACCESS_KEY = "YCOVaI5JOrYqyLHWSiIYvV3Qa-N5T3lGmCMFwIvk"
    }

    suspend fun uploadNewsImage(imageUri: Uri, prefix: String = "news"): String {
        return withContext(Dispatchers.IO) {
            val tempFile = createTempFileFromUri(imageUri)
            val fileName = "$prefix/${UUID.randomUUID()}.jpg"
            val url = "$ENDPOINT/$BUCKET_NAME/$fileName"

            val now = System.currentTimeMillis()
            val date = formatDate(now, "yyyyMMdd")
            val time = formatDate(now, "yyyyMMdd'T'HHmmss'Z'")

            // Подготовка запроса
            val headers = mapOf(
                "Host" to "storage.yandexcloud.net",
                "X-Amz-Date" to time,
                "X-Amz-Content-Sha256" to "UNSIGNED-PAYLOAD",
                "x-amz-acl" to "public-read"
            )

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

            val credentialScope = "$date/$REGION/$SERVICE/$REQUEST_TYPE"
            val stringToSign = """
                |$ALGORITHM
                |$time
                |$credentialScope
                |${sha256(canonicalRequest)}
            """.trimMargin()

            val signature = calculateSignature(
                stringToSign,
                date,
                REGION,
                SERVICE
            )

            val authorizationHeader =
                "$ALGORITHM Credential=$ACCESS_KEY_ID/$credentialScope, " +
                        "SignedHeaders=$signedHeaders, Signature=$signature"

            // Формирование запроса
            val client = OkHttpClient()
            val requestBody = tempFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(url)
                .put(requestBody)
                .header("Authorization", authorizationHeader)
                .header("x-amz-acl", "public-read")
                .header("x-amz-content-sha256", "UNSIGNED-PAYLOAD")
                .header("x-amz-date", time)
                .header("Host", "storage.yandexcloud.net")
                .build()

            // Выполнение запроса
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Empty error body"
                    throw IOException("HTTP Error ${response.code}: $errorBody")
                }
                url
            }
        }
    }

    private fun createTempFileFromUri(uri: Uri): File {
        return File.createTempFile("upload_", ".jpg", context.externalCacheDir).apply {
            context.contentResolver.openInputStream(uri)?.use { input ->
                outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("File read error")
        }
    }

    private fun formatDate(timestamp: Long, pattern: String): String {
        val formatter = java.text.SimpleDateFormat(pattern, Locale.US)
        formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return formatter.format(Date(timestamp))
    }

    private fun sha256(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
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
}