package com.example.chatapp.utils

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object NotificationSender {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .sslSocketFactory(getSSLContext().socketFactory, getTrustManager())
            .build()
    }

    private fun getSSLContext(): SSLContext {
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(getTrustManager()), null)
        }
    }

    private fun getTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                chain.forEach { cert ->
                    if (cert.issuerX500Principal.name.contains("ISRG Root X1")) {
                        return
                    }
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }

    fun sendNotification(
        fcmToken: String,
        title: String,
        body: String,
        senderName: String,
        callback: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        if (fcmToken.isBlank() || title.isBlank() || body.isBlank()) {
            callback(false, "Обязательные поля: token, title, body")
            return
        }

        val jsonBody = """
            {
                "token": "$fcmToken",
                "title": "$title",
                "body": "$body"
            }
        """.trimIndent()

        Log.d("NotificationSender", "Request body: $jsonBody")

        val request = Request.Builder()
            .url("https://auspicious-cosmic-can.glitch.me/send")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("NotificationSender", "Network error: ${e.message}")
                callback(false, "Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful) {
                        Log.e("NotificationSender", "HTTP ${response.code} - $responseBody")
                        callback(false, "HTTP ${response.code}: $responseBody")
                    } else {
                        Log.d("NotificationSender", "Success: $responseBody")
                        callback(true, null)
                    }
                } catch (e: Exception) {
                    callback(false, "Response error: ${e.message}")
                }
            }
        })
    }
}
