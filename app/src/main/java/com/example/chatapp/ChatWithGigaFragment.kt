package com.example.chatapp.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.SavedDialog
import com.example.chatapp.SavedDialogsAdapter
import com.example.chatapp.adapters.GigaMessageAdapter
import com.example.chatapp.api.AuthRetrofitInstance
import com.example.chatapp.api.GigaChatRequest
import com.example.chatapp.api.Message
import com.example.chatapp.api.RetrofitInstance
import com.example.chatapp.models.GigaMessage
import com.example.chatapp.viewmodels.DialogsViewModel
import com.example.chatapp.viewmodels.GigaChatViewModel
import com.example.chatapp.viewmodels.GigaChatViewModelFactory
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.*

class ChatWithGigaFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var messageAdapter: GigaMessageAdapter
    private lateinit var editTextMessage: EditText
    private lateinit var btnSendMessage: ImageButton
    private lateinit var btnClearDialog: ImageButton
    private lateinit var viewModel: GigaChatViewModel
    private lateinit var dialogsViewModel: DialogsViewModel
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var savedDialogsRecyclerView: RecyclerView
    private lateinit var savedDialogsAdapter: SavedDialogsAdapter
    private lateinit var btnSaveDialog: ImageButton
    private var accessToken: String = ""
    private val authScope = "GIGACHAT_API_PERS"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chat_with_giga, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, systemBars.bottom)
            insets
        }

        viewModel = ViewModelProvider(
            this,
            GigaChatViewModelFactory(requireActivity())
        ).get(GigaChatViewModel::class.java)

        // Инициализация ViewModel для диалогов
        dialogsViewModel = ViewModelProvider(this).get(DialogsViewModel::class.java)

        // Инициализация DrawerLayout
        drawerLayout = view.findViewById(R.id.drawer_layout)

        recyclerView = view.findViewById(R.id.recyclerViewMessages)
        editTextMessage = view.findViewById(R.id.editTextMessage)
        btnSendMessage = view.findViewById(R.id.btnSendMessage)
        btnClearDialog = view.findViewById(R.id.btnClearDialog)
        btnSaveDialog = view.findViewById(R.id.btnSaveDialog)

        messageAdapter = GigaMessageAdapter()
        recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = messageAdapter
        }

        viewModel.messages.forEach { message ->
            messageAdapter.addMessage(message)
        }

        btnSendMessage.setOnClickListener {
            val userMessage = editTextMessage.text.toString().trim()
            if (userMessage.isNotEmpty()) {
                viewModel.addMessage(userMessage, true)
                messageAdapter.addMessage(GigaMessage(userMessage, true))
                recyclerView.scrollToPosition(viewModel.messages.size - 1)
                editTextMessage.text.clear()
                getBotResponse(userMessage)
            }
        }

        btnClearDialog.setOnClickListener {
            showClearDialogConfirmation()
        }

        // Инициализация кнопки сохранения
        btnSaveDialog.setOnClickListener { showSaveDialogPrompt() }

        // Инициализация RecyclerView для сохраненных диалогов
        savedDialogsRecyclerView = view.findViewById(R.id.recyclerViewSavedDialogs)
        savedDialogsAdapter = SavedDialogsAdapter(
            onDialogSelected = { loadSavedDialog(it) },
            onDialogDeleted = { dialogsViewModel.deleteDialog(it.id) }
        )

        savedDialogsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = savedDialogsAdapter
        }

        // Наблюдаем за изменениями в сохраненных диалогах
        dialogsViewModel.savedDialogs.observe(viewLifecycleOwner) { dialogs ->
            savedDialogsAdapter.updateDialogs(dialogs)
        }

        // Кнопка для открытия панели диалогов
        val btnOpenDialogs = view.findViewById<ImageButton>(R.id.btnOpenDialogs)
        btnOpenDialogs.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }
    }

    private fun showSaveDialogPrompt() {
        val editText = EditText(requireContext())
        editText.hint = "Введите название диалога"

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Сохранить диалог")
            .setView(editText)
            .setPositiveButton("Сохранить") { _, _ ->
                val title = editText.text.toString().trim()
                if (title.isNotEmpty()) {
                    dialogsViewModel.saveDialog(title, viewModel.messages.toList())
                    Toast.makeText(requireContext(), "Диалог сохранен", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Введите название диалога", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun loadSavedDialog(savedDialog: SavedDialog) {
        // Закрываем панель
        drawerLayout.closeDrawer(GravityCompat.END)

        // Очищаем текущий диалог
        viewModel.clearAllMessages()
        messageAdapter.updateMessages(emptyList())

        // Загружаем сохраненный диалог
        val loadedMessages = dialogsViewModel.loadDialog(savedDialog)
        loadedMessages.forEach { message ->
            viewModel.addMessage(message.text, message.isUser)
            messageAdapter.addMessage(message)
        }

        recyclerView.scrollToPosition(viewModel.messages.size - 1)
        Toast.makeText(requireContext(), "Диалог загружен", Toast.LENGTH_SHORT).show()
    }

    private fun showClearDialogConfirmation() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Очистить диалог")
            .setMessage("Вы уверены, что хотите очистить весь диалог?")
            .setPositiveButton("Да") { _, _ ->
                viewModel.clearAllMessages()
                messageAdapter.updateMessages(emptyList())
                recyclerView.scrollToPosition(0)
            }
            .setNegativeButton("Нет") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
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
                    showError("Ошибка авторизации в API")
                }
            }

            override fun onFailure(call: Call<com.example.chatapp.api.AuthResponse>, t: Throwable) {
                Log.e("API_ERROR", "Ошибка подключения: ${t.message}")
                showError("Ошибка подключения к серверу")
            }
        })
    }

    private fun sendMessageWithToken(token: String, userMessage: String) {
        val messagesList = viewModel.messages.map { message ->
            Message(
                role = if (message.isUser) "user" else "assistant",
                content = message.text
            )
        }

        val request = GigaChatRequest(
            model = "GigaChat",
            messages = messagesList,
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
                viewModel.addMessage(botMessage, false)
                messageAdapter.addMessage(GigaMessage(botMessage, false))
                recyclerView.scrollToPosition(viewModel.messages.size - 1)
            }

            override fun onFailure(call: Call<com.example.chatapp.api.GigaChatResponse>, t: Throwable) {
                val errorMessage = "Ошибка подключения: ${t.message}"
                viewModel.addMessage(errorMessage, false)
                messageAdapter.addMessage(GigaMessage(errorMessage, false))
                recyclerView.scrollToPosition(viewModel.messages.size - 1)
            }
        })
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }
}