package com.example.chatapp.activities

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.adapters.GigaMessageAdapter
import com.example.chatapp.api.AuthRetrofitInstance
import com.example.chatapp.api.RetrofitInstance
import com.example.chatapp.models.GigaMessage
import com.example.chatapp.viewmodels.GigaChatViewModel
import com.example.chatapp.viewmodels.GigaChatViewModelFactory
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.*

class ChatWithGigaActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var messageAdapter: GigaMessageAdapter
    private lateinit var editTextMessage: EditText
    private lateinit var btnSendMessage: Button
    private lateinit var btnClearDialog: ImageButton // Новая кнопка
    private lateinit var viewModel: GigaChatViewModel
    private var accessToken: String = ""
    private val authScope = "GIGACHAT_API_PERS"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_with_giga)

        // Инициализация ViewModel
        viewModel = ViewModelProvider(
            this,
            GigaChatViewModelFactory(this)
        ).get(GigaChatViewModel::class.java)

        recyclerView = findViewById(R.id.recyclerViewMessages)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Создание адаптера без передачи списка
        messageAdapter = GigaMessageAdapter()
        recyclerView.adapter = messageAdapter

        // Обновление адаптера при загрузке сообщений из ViewModel
        viewModel.messages.forEach { message ->
            messageAdapter.addMessage(message)
        }

        editTextMessage = findViewById(R.id.editTextMessage)
        btnSendMessage = findViewById(R.id.btnSendMessage)
        btnClearDialog = findViewById(R.id.btnClearDialog) // Новая кнопка

        // Добавление обработчика кнопки отправки сообщения
        btnSendMessage.setOnClickListener {
            val userMessage = editTextMessage.text.toString().trim()
            if (userMessage.isNotEmpty()) {
                // Добавляем сообщение пользователя
                viewModel.addMessage(userMessage, true)
                messageAdapter.addMessage(GigaMessage(userMessage, true))
                recyclerView.scrollToPosition(viewModel.messages.size - 1)
                editTextMessage.text.clear()

                // Получаем ответ от бота
                getBotResponse(userMessage)
            }
        }

        // Обработчик кнопки очистки диалога
        btnClearDialog.setOnClickListener {
            // Создаем диалоговое окно для подтверждения
            val dialog = android.app.AlertDialog.Builder(this)
                .setTitle("Очистить диалог")
                .setMessage("Вы уверены, что хотите очистить весь диалог?")
                .setPositiveButton("Да") { _, _ ->
                    // Очищаем данные в ViewModel
                    viewModel.clearAllMessages()

                    // Очищаем адаптер
                    messageAdapter.updateMessages(emptyList())

                    // Прокручиваем RecyclerView в начало
                    recyclerView.scrollToPosition(0)
                }
                .setNegativeButton("Нет") { dialog, _ ->
                    // Закрываем диалог без действий
                    dialog.dismiss()
                }
                .create()

            // Показываем диалог
            dialog.show()
        }
    }

    private fun getBotResponse(userMessage: String) {
        if (accessToken.isEmpty()) {
            fetchAuthToken { token ->
                sendMessageWithToken(token, userMessage)
            }
        } else {
            sendMessageWithToken(accessToken, userMessage)
        }
    }

    private fun fetchAuthToken(onTokenReceived: (String) -> Unit) {
        val rqUid = UUID.randomUUID().toString()
        val authHeader = "Basic M2JhZGQ0NzktNGVjNy00ZmYyLWE4ZGQtNTMyOTViZDgzYzlkOjU4OGRkZDg1LTMzZmMtNDNkYi04MmJmLWFmZDM5Nzk5NmM2MQ=="

        val call = AuthRetrofitInstance.authApi.getAuthToken(
            rqUid = rqUid,
            authHeader = authHeader,
            scope = authScope
        )

        call.enqueue(object : Callback<com.example.chatapp.api.AuthResponse> {
            override fun onResponse(
                call: Call<com.example.chatapp.api.AuthResponse>,
                response: Response<com.example.chatapp.api.AuthResponse>
            ) {
                if (response.isSuccessful) {
                    accessToken = response.body()?.access_token ?: ""
                    onTokenReceived(accessToken)
                } else {
                    Log.e("API_ERROR", "Ошибка авторизации: ${response.code()} ${response.message()}")
                    response.errorBody()?.let {
                        Log.e("API_ERROR", "Тело ошибки: ${it.string()}")
                    }
                }
            }

            override fun onFailure(call: Call<com.example.chatapp.api.AuthResponse>, t: Throwable) {
                Log.e("API_ERROR", "Ошибка подключения: ${t.message}")
            }
        })
    }

    private fun sendMessageWithToken(token: String, userMessage: String) {
        val request = com.example.chatapp.api.GigaChatRequest(
            model = "GigaChat",
            messages = listOf(com.example.chatapp.api.Message("user", userMessage)),
            max_tokens = 5000
        )

        val call = RetrofitInstance.api.sendMessage("Bearer $token", request)

        call.enqueue(object : Callback<com.example.chatapp.api.GigaChatResponse> {
            override fun onResponse(
                call: Call<com.example.chatapp.api.GigaChatResponse>,
                response: Response<com.example.chatapp.api.GigaChatResponse>
            ) {
                val botMessage = response.body()?.choices?.firstOrNull()?.message?.content
                    ?: "Ошибка: пустой ответ"
                // Добавляем сообщение бота
                viewModel.addMessage(botMessage, false)
                messageAdapter.addMessage(GigaMessage(botMessage, false))
                recyclerView.scrollToPosition(viewModel.messages.size - 1)
            }

            override fun onFailure(call: Call<com.example.chatapp.api.GigaChatResponse>, t: Throwable) {
                val errorMessage = "Ошибка подключения: ${t.message}"
                viewModel.addMessage(errorMessage, false)
                messageAdapter.addMessage(GigaMessage(errorMessage, false))
            }
        })
    }
}