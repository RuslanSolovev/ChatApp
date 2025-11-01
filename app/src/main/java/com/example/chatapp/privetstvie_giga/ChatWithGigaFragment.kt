package com.example.chatapp.privetstvie_giga

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.SavedDialog
import com.example.chatapp.SavedDialogsAdapter
import com.example.chatapp.api.AuthRetrofitInstance
import com.example.chatapp.api.GigaChatRequest
import com.example.chatapp.api.Message
import com.example.chatapp.api.RetrofitInstance
import com.example.chatapp.viewmodels.DialogsViewModel
import com.google.android.material.internal.ViewUtils.hideKeyboard
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.*
import kotlin.math.abs

class ChatWithGigaFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var messageAdapter: GigaMessageAdapter
    private lateinit var editTextMessage: EditText
    private lateinit var btnSendMessage: ImageButton
    private lateinit var btnClearDialog: ImageButton
    private val viewModel: GigaChatViewModel by viewModels { GigaChatViewModelFactory(requireActivity()) }
    private val dialogsViewModel: DialogsViewModel by viewModels()
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var savedDialogsRecyclerView: RecyclerView
    private lateinit var savedDialogsAdapter: SavedDialogsAdapter
    private lateinit var btnSaveDialog: ImageButton

    // Компоненты для персонализации
    private var greetingGenerator: SmartQuestionGenerator? = null
    private var contextAnalyzer: SmartContextAnalyzer? = null
    private var userProfile: UserProfile? = null

    // API и состояние
    private var accessToken: String = ""
    private val authScope = "GIGACHAT_API_PERS"

    // Флаги состояния
    private var isFirstLaunch = true
    private var isGeneratingResponse = false
    private var chatStartTime: Long = 0

    // Асинхронные компоненты
    private val handler = Handler(Looper.getMainLooper())
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val computationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Флаг инициализации
    private var isInitialized = false
    private var greetingJob: Job? = null

    companion object {
        private const val TAG = "ChatWithGigaFragment"
        private const val SCROLL_DELAY = 100L
        private const val GREETING_DELAY = 5000L // Увеличили задержку до 5 секунд
        private const val KEYBOARD_DELAY = 100L
        private const val INIT_DELAY = 1000L // Задержка перед началом инициализации
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chat_with_giga, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Сначала быстрая инициализация UI
        initBasicUI(view)

        // Отложенная инициализация тяжелых компонентов
        handler.postDelayed({
            initializeAsyncComponents()
        }, INIT_DELAY)
    }

    /**
     * Базовая инициализация UI (быстрая, не блокирующая)
     */
    private fun initBasicUI(view: View) {
        try {
            // Инициализация основных UI элементов
            recyclerView = view.findViewById(R.id.recyclerViewMessages)
            editTextMessage = view.findViewById(R.id.editTextMessage)
            btnSendMessage = view.findViewById(R.id.btnSendMessage)
            btnClearDialog = view.findViewById(R.id.btnClearDialog)
            btnSaveDialog = view.findViewById(R.id.btnSaveDialog)
            drawerLayout = view.findViewById(R.id.drawer_layout)

            // Быстрая настройка адаптеров
            setupBasicAdapters()

            // Быстрая настройка слушателей
            setupBasicListeners()

            // Загрузка существующих сообщений
            loadExistingMessagesFast()

            // Настройка системных инсетов
            setupSystemInsetsAsync(view)

            Log.d(TAG, "Basic UI initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in basic UI initialization", e)
        }
    }

    /**
     * Базовая настройка адаптеров
     */
    private fun setupBasicAdapters() {
        try {
            messageAdapter = GigaMessageAdapter()
            recyclerView.apply {
                layoutManager = LinearLayoutManager(requireContext()).apply {
                    stackFromEnd = true
                }
                adapter = messageAdapter
                itemAnimator = null // Отключаем анимацию для производительности
            }

            savedDialogsRecyclerView = requireView().findViewById(R.id.recyclerViewSavedDialogs)
            savedDialogsAdapter = SavedDialogsAdapter(
                onDialogSelected = { loadSavedDialogAsync(it) },
                onDialogDeleted = { deleteDialogAsync(it.id) }
            )

            savedDialogsRecyclerView.apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = savedDialogsAdapter
            }

            Log.d(TAG, "Basic adapters setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up basic adapters", e)
        }
    }

    /**
     * Базовая настройка слушателей
     */
    private fun setupBasicListeners() {
        try {
            // Отправка сообщения
            btnSendMessage.setOnClickListener {
                sendUserMessageAsync()
            }

            // Отправка сообщения по Enter
            editTextMessage.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    sendUserMessageAsync()
                    true
                } else {
                    false
                }
            }

            // Очистка диалога
            btnClearDialog.setOnClickListener {
                showClearDialogConfirmationAsync()
            }

            // Сохранение диалога
            btnSaveDialog.setOnClickListener {
                showSaveDialogPromptAsync()
            }

            // Навигация по диалогам
            setupDialogsNavigationAsync()

            // Слушатель изменения текста
            setupTextWatcherAsync()

            // Скрытие клавиатуры
            recyclerView.setOnTouchListener { _, _ ->
                hideKeyboardAsync()
                false
            }

            Log.d(TAG, "Basic listeners setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up basic listeners", e)
        }
    }

    /**
     * Асинхронная инициализация тяжелых компонентов
     */
    private fun initializeAsyncComponents() {
        if (isInitialized) return

        uiScope.launch {
            try {
                Log.d(TAG, "Starting async components initialization...")

                // Параллельная загрузка всех компонентов
                val initializationJob = ioScope.async {
                    loadAllComponentsInBackground()
                }

                // Ждем завершения инициализации с таймаутом
                val components = withTimeout(10000) {
                    initializationJob.await()
                }

                // Обновляем UI в главном потоке
                withContext(Dispatchers.Main) {
                    userProfile = components.first
                    contextAnalyzer = components.second
                    greetingGenerator = components.third

                    isInitialized = true

                    // Загружаем сохраненные диалоги
                    loadSavedDialogsAsync()

                    // Показываем приветствие с задержкой
                    scheduleDelayedGreeting()

                    setupScrollBehaviorAsync()
                    chatStartTime = System.currentTimeMillis()

                    Log.d(TAG, "All components initialized successfully")
                }

            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Component initialization timeout", e)
                withContext(Dispatchers.Main) {
                    showFallbackGreetingAsync()
                    isInitialized = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing components", e)
                withContext(Dispatchers.Main) {
                    showFallbackGreetingAsync()
                    isInitialized = true
                }
            }
        }
    }

    /**
     * Загрузка всех компонентов в фоне
     */
    private suspend fun loadAllComponentsInBackground(): Triple<UserProfile?, SmartContextAnalyzer?, SmartQuestionGenerator?> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Loading components in background...")

                // Параллельная загрузка всех тяжелых компонентов
                val profileDeferred = async {
                    try {
                        loadUserProfileAsync()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading profile", e)
                        null
                    }
                }

                val analyzerDeferred = async {
                    try {
                        SmartContextAnalyzer(requireContext().applicationContext)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating analyzer", e)
                        null
                    }
                }

                val profile = profileDeferred.await()
                val analyzer = analyzerDeferred.await()

                val generator = try {
                    SmartQuestionGenerator(requireContext().applicationContext, profile)
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating generator", e)
                    null
                }

                Log.d(TAG, "Background component loading completed")
                return@withContext Triple(profile, analyzer, generator)
            } catch (e: Exception) {
                Log.e(TAG, "Error in background component loading", e)
                return@withContext Triple(null, null, null)
            }
        }

    /**
     * Отложенное показание приветствия
     */
    private fun scheduleDelayedGreeting() {
        // Отменяем предыдущее задание если есть
        greetingJob?.cancel()

        greetingJob = uiScope.launch {
            try {
                Log.d(TAG, "Scheduling delayed greeting...")

                // Ждем 5 секунд перед показом приветствия
                delay(GREETING_DELAY)

                if (isAdded && !isDetached && view != null) {
                    showSmartChatGreetingAsync()
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Greeting scheduling cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error in scheduled greeting", e)
            }
        }
    }

    /**
     * Быстрая загрузка существующих сообщений
     */
    private fun loadExistingMessagesFast() {
        try {
            if (viewModel.messages.isNotEmpty()) {
                messageAdapter.updateMessages(viewModel.messages.toList())

                // Прокручиваем к последнему сообщению с задержкой
                recyclerView.postDelayed({
                    try {
                        recyclerView.scrollToPosition(viewModel.messages.size - 1)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error scrolling to position", e)
                    }
                }, SCROLL_DELAY)
            }
            Log.d(TAG, "Existing messages loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading existing messages", e)
        }
    }

    /**
     * Асинхронная настройка системных инсетов
     */
    private fun setupSystemInsetsAsync(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            try {
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val navigationBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top

                Log.d(TAG, "System bars - status: $statusBarHeight, navigation: $navigationBarHeight")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up system insets", e)
            }
            insets
        }
    }

    /**
     * Настройка навигации по диалогам
     */
    private fun setupDialogsNavigationAsync() {
        try {
            val btnOpenDialogs = requireView().findViewById<ImageButton>(R.id.btnOpenDialogs)
            btnOpenDialogs.setOnClickListener {
                drawerLayout.openDrawer(GravityCompat.END)
            }

            val btnCloseDialogs = requireView().findViewById<ImageButton>(R.id.btnCloseDialogs)
            btnCloseDialogs.setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.END)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up dialogs navigation", e)
        }
    }

    /**
     * Настройка отслеживания текста
     */
    private fun setupTextWatcherAsync() {
        editTextMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                try {
                    btnSendMessage.isEnabled = !s.isNullOrEmpty() && !isGeneratingResponse
                } catch (e: Exception) {
                    Log.e(TAG, "Error in text watcher", e)
                }
            }
        })
    }

    /**
     * Асинхронная настройка поведения прокрутки
     */
    private fun setupScrollBehaviorAsync() {
        try {
            messageAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    if (positionStart == messageAdapter.itemCount - 1) {
                        recyclerView.post {
                            try {
                                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                                val lastPosition = layoutManager.findLastCompletelyVisibleItemPosition()
                                if (lastPosition == -1 || lastPosition >= positionStart - 2) {
                                    recyclerView.smoothScrollToPosition(messageAdapter.itemCount - 1)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in scroll behavior", e)
                            }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up scroll behavior", e)
        }
    }

    /**
     * Асинхронная загрузка сохраненных диалогов
     */
    private fun loadSavedDialogsAsync() {
        uiScope.launch {
            try {
                // Наблюдаем за изменениями в сохраненных диалогах
                dialogsViewModel.savedDialogs.observe(viewLifecycleOwner) { dialogs ->
                    try {
                        savedDialogsAdapter.updateDialogs(dialogs)

                        // Показываем/скрываем подсказку о пустом списке
                        val tvEmptyDialogs = requireView().findViewById<android.widget.TextView>(R.id.tvEmptyDialogs)
                        tvEmptyDialogs.visibility = if (dialogs.isEmpty()) View.VISIBLE else View.GONE
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating dialogs UI", e)
                    }
                }
                Log.d(TAG, "Saved dialogs loading initiated")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading saved dialogs", e)
            }
        }
    }

    /**
     * Загрузка профиля пользователя (IO операция)
     */
    private suspend fun loadUserProfileAsync(): UserProfile? = withContext(Dispatchers.IO) {
        try {
            val currentUser = Firebase.auth.currentUser
            if (currentUser == null) {
                Log.d(TAG, "User not authenticated, using contextual welcome")
                return@withContext null
            }

            val snapshot = Firebase.database.reference
                .child("user_profiles")
                .child(currentUser.uid)
                .get()
                .await()

            if (snapshot.exists()) {
                val profile = snapshot.getValue(UserProfile::class.java)
                Log.d(TAG, "User profile loaded for chat: ${profile != null}")
                return@withContext profile
            }
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user profile for chat", e)
            return@withContext null
        }
    }

    /**
     * Показ умного приветствия в чате (асинхронно)
     */
    private fun showSmartChatGreetingAsync() {
        if (!shouldShowGreeting()) return

        uiScope.launch {
            try {
                Log.d(TAG, "Showing smart chat greeting...")

                // Загружаем ПОЛНОЕ приветствие из MainActivity в фоне
                val completeWelcomePhrase = withContext(Dispatchers.IO) {
                    try {
                        loadCompleteWelcomePhraseForChat()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading welcome phrase", e)
                        null
                    }
                }

                if (!completeWelcomePhrase.isNullOrEmpty()) {
                    // Разделяем фразу на части для красивого отображения в фоне
                    val (greetingPart, questionPart) = withContext(Dispatchers.Default) {
                        try {
                            splitWelcomePhrase(completeWelcomePhrase)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error splitting welcome phrase", e)
                            Pair("Привет!", "Рад вас видеть!")
                        }
                    }

                    // Показываем приветствие
                    addWelcomeMessageAsync(greetingPart)

                    // Показываем вопрос с дополнительной задержкой
                    handler.postDelayed({
                        uiScope.launch {
                            addContextualQuestionAsync(questionPart)
                            Log.d(TAG, "Complete welcome phrase displayed: $completeWelcomePhrase")
                        }
                    }, 2000) // Дополнительные 2 секунды между частями

                } else {
                    // Fallback: генерируем приветствие локально в фоне
                    showFallbackChatGreetingAsync()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error showing smart chat greeting", e)
                showFallbackGreetingAsync()
            }
        }
    }

    /**
     * Загрузка полного приветствия для чата
     */
    private fun loadCompleteWelcomePhraseForChat(): String? {
        return try {
            val sharedPref = requireContext().getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
            val phrase = sharedPref.getString("complete_welcome_phrase", null)
            // Очищаем после использования
            sharedPref.edit().remove("complete_welcome_phrase").apply()
            phrase
        } catch (e: Exception) {
            Log.e(TAG, "Error loading complete welcome phrase", e)
            null
        }
    }

    /**
     * Разделение полной фразы на приветствие и вопрос (вычислительная задача)
     */
    private fun splitWelcomePhrase(completePhrase: String): Pair<String, String> {
        return try {
            val sentences = completePhrase.split('.', '!', '?').map { it.trim() }.filter { it.isNotEmpty() }

            when {
                sentences.size >= 2 -> {
                    val greeting = sentences[0] + if (completePhrase.contains('.')) "." else "!"
                    val question = sentences.subList(1, sentences.size).joinToString(" ") + "?"
                    Pair(greeting, question)
                }
                sentences.size == 1 -> {
                    val parts = completePhrase.split(',').map { it.trim() }
                    if (parts.size >= 2) {
                        val greeting = parts[0] + ","
                        val question = parts.subList(1, parts.size).joinToString(" ") + "?"
                        Pair(greeting, question)
                    } else {
                        Pair(completePhrase, "Чем могу помочь?")
                    }
                }
                else -> Pair("Привет!", "Как ваши дела?")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error splitting welcome phrase", e)
            Pair("Привет!", "Рад вас видеть!")
        }
    }

    /**
     * Fallback приветствие для чата (асинхронно)
     */
    private fun showFallbackChatGreetingAsync() {
        uiScope.launch {
            try {
                // Часть 1: Основное приветствие в фоне
                val greeting = withContext(Dispatchers.Default) {
                    try {
                        greetingGenerator?.generateContextualGreeting() ?: "Привет!"
                    } catch (e: Exception) {
                        Log.e(TAG, "Error generating greeting", e)
                        "Привет!"
                    }
                }
                addWelcomeMessageAsync(greeting)

                // Часть 2: Контекстный вопрос с задержкой
                handler.postDelayed({
                    uiScope.launch {
                        try {
                            val question = withContext(Dispatchers.Default) {
                                try {
                                    greetingGenerator?.generateFollowUpQuestion() ?: "Как ваши дела?"
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error generating question", e)
                                    "Как ваши дела?"
                                }
                            }
                            addContextualQuestionAsync(question)

                            // Сохраняем для будущего использования в фоне
                            saveWelcomePhraseForChatAsync("$greeting $question")
                            Log.d(TAG, "Fallback greeting generated: $greeting $question")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error generating fallback question", e)
                            addContextualQuestionAsync("Как ваши дела?")
                        }
                    }
                }, 2000) // Дополнительные 2 секунды

            } catch (e: Exception) {
                Log.e(TAG, "Error in fallback chat greeting", e)
                showFallbackGreetingAsync()
            }
        }
    }

    /**
     * Проверка необходимости показа приветствия
     */
    private fun shouldShowGreeting(): Boolean {
        return try {
            if (viewModel.messages.isEmpty()) return true

            val lastMessageTime = viewModel.messages.lastOrNull()?.timestamp ?: 0L
            val timeSinceLastMessage = System.currentTimeMillis() - lastMessageTime

            timeSinceLastMessage > 2 * 60 * 60 * 1000 ||
                    viewModel.messages.size < 3 ||
                    isFirstLaunch
        } catch (e: Exception) {
            Log.e(TAG, "Error checking greeting condition", e)
            true
        }
    }

    /**
     * Добавление приветственного сообщения (UI операция)
     */
    private fun addWelcomeMessageAsync(phrase: String) {
        uiScope.launch {
            try {
                viewModel.addMessage(phrase, false)
                messageAdapter.addMessage(GigaMessage(phrase, false))

                recyclerView.post {
                    try {
                        recyclerView.smoothScrollToPosition(viewModel.messages.size - 1)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error scrolling after welcome", e)
                    }
                }
                Log.d(TAG, "Welcome message added: $phrase")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding welcome message", e)
            }
        }
    }

    /**
     * Добавление контекстного вопроса (UI операция)
     */
    private fun addContextualQuestionAsync(question: String) {
        uiScope.launch {
            try {
                viewModel.addMessage(question, false)
                messageAdapter.addMessage(GigaMessage(question, false))

                recyclerView.post {
                    try {
                        recyclerView.smoothScrollToPosition(viewModel.messages.size - 1)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error scrolling after question", e)
                    }
                }
                Log.d(TAG, "Contextual question added: $question")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding contextual question", e)
            }
        }
    }

    /**
     * Показ запасного приветствия
     */
    private fun showFallbackGreetingAsync() {
        uiScope.launch {
            try {
                val userName = getCurrentUserName()
                val greeting = getTimeBasedGreeting()
                val fallbackMessage = "$greeting, $userName! Рад вас видеть! Чем могу помочь?"
                addWelcomeMessageAsync(fallbackMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing fallback greeting", e)
                addWelcomeMessageAsync("Привет! Рад вас видеть! Чем могу помочь?")
            }
        }
    }

    /**
     * Асинхронная отправка сообщения пользователя
     */
    private fun sendUserMessageAsync() {
        if (isGeneratingResponse) {
            Toast.makeText(requireContext(), "Подождите, идет генерация ответа...", Toast.LENGTH_SHORT).show()
            return
        }

        val userMessage = editTextMessage.text.toString().trim()
        if (userMessage.isEmpty()) return

        uiScope.launch {
            try {
                // Быстро добавляем сообщение пользователя
                viewModel.addMessage(userMessage, true)
                messageAdapter.addMessage(GigaMessage(userMessage, true))
                editTextMessage.text.clear()
                hideKeyboardAsync()

                // Прокручиваем в фоне
                recyclerView.post {
                    try {
                        recyclerView.smoothScrollToPosition(viewModel.messages.size - 1)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error scrolling after user message", e)
                    }
                }

                // Получаем ответ в фоне
                getBotResponseAsync(userMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending user message", e)
                showErrorAsync("Ошибка отправки сообщения")
            }
        }
    }

    /**
     * Асинхронное получение ответа от бота
     */
    private fun getBotResponseAsync(userMessage: String) {
        if (isGeneratingResponse) return

        isGeneratingResponse = true
        updateSendButtonStateAsync()

        ioScope.launch {
            try {
                if (accessToken.isEmpty()) {
                    fetchAuthTokenAsync { token ->
                        uiScope.launch {
                            sendMessageWithTokenAsync(token, userMessage)
                        }
                    }
                } else {
                    sendMessageWithTokenAsync(accessToken, userMessage)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting bot response", e)
                uiScope.launch {
                    showErrorAsync("Ошибка получения ответа")
                    isGeneratingResponse = false
                    updateSendButtonStateAsync()
                }
            }
        }
    }

    /**
     * Обновление состояния кнопки отправки
     */
    private fun updateSendButtonStateAsync() {
        uiScope.launch {
            try {
                btnSendMessage.isEnabled = !isGeneratingResponse &&
                        editTextMessage.text.toString().trim().isNotEmpty()
            } catch (e: Exception) {
                Log.e(TAG, "Error updating send button state", e)
            }
        }
    }

    /**
     * Асинхронное получение токена авторизации
     */
    private fun fetchAuthTokenAsync(onTokenReceived: (String) -> Unit) {
        try {
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
                        uiScope.launch {
                            showErrorAsync("Ошибка авторизации в API")
                            isGeneratingResponse = false
                            updateSendButtonStateAsync()
                        }
                    }
                }

                override fun onFailure(call: Call<com.example.chatapp.api.AuthResponse>, t: Throwable) {
                    Log.e("API_ERROR", "Ошибка подключения: ${t.message}")
                    uiScope.launch {
                        showErrorAsync("Ошибка подключения к серверу")
                        isGeneratingResponse = false
                        updateSendButtonStateAsync()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching auth token", e)
            uiScope.launch {
                showErrorAsync("Ошибка получения токена")
                isGeneratingResponse = false
                updateSendButtonStateAsync()
            }
        }
    }

    /**
     * Асинхронная отправка сообщения с токеном
     */
    private suspend fun sendMessageWithTokenAsync(token: String, userMessage: String) = withContext(Dispatchers.IO) {
        try {
            // Создаем системное сообщение с информацией о пользователе в фоне
            val systemMessage = buildPersonalizedSystemMessageAsync()

            val messagesList = mutableListOf<Message>()

            // Добавляем системное сообщение
            messagesList.add(Message(role = "system", content = systemMessage))

            // Добавляем историю сообщений
            val recentMessages = viewModel.messages.takeLast(15)
            messagesList.addAll(recentMessages.map { message ->
                Message(
                    role = if (message.isUser) "user" else "assistant",
                    content = message.text
                )
            })

            val request = GigaChatRequest(
                model = "GigaChat",
                messages = messagesList,
                max_tokens = 2000
            )

            val call = RetrofitInstance.api.sendMessage("Bearer $token", request)

            call.enqueue(object : Callback<com.example.chatapp.api.GigaChatResponse> {
                override fun onResponse(
                    call: Call<com.example.chatapp.api.GigaChatResponse>,
                    response: Response<com.example.chatapp.api.GigaChatResponse>
                ) {
                    uiScope.launch {
                        try {
                            if (response.isSuccessful) {
                                val botMessage = response.body()?.choices?.firstOrNull()?.message?.content
                                    ?: "Ошибка: пустой ответ"
                                viewModel.addMessage(botMessage, false)
                                messageAdapter.addMessage(GigaMessage(botMessage, false))

                                recyclerView.post {
                                    try {
                                        recyclerView.smoothScrollToPosition(viewModel.messages.size - 1)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error scrolling after bot message", e)
                                    }
                                }
                            } else {
                                val errorMessage = "Ошибка API: ${response.code()}"
                                viewModel.addMessage(errorMessage, false)
                                messageAdapter.addMessage(GigaMessage(errorMessage, false))

                                recyclerView.post {
                                    try {
                                        recyclerView.smoothScrollToPosition(viewModel.messages.size - 1)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error scrolling after error", e)
                                    }
                                }
                                Log.e("API_ERROR", "Ошибка ответа: ${response.errorBody()?.string()}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing API response", e)
                        } finally {
                            isGeneratingResponse = false
                            updateSendButtonStateAsync()
                        }
                    }
                }

                override fun onFailure(call: Call<com.example.chatapp.api.GigaChatResponse>, t: Throwable) {
                    uiScope.launch {
                        try {
                            val errorMessage = "Ошибка подключения: ${t.message}"
                            viewModel.addMessage(errorMessage, false)
                            messageAdapter.addMessage(GigaMessage(errorMessage, false))

                            recyclerView.post {
                                try {
                                    recyclerView.smoothScrollToPosition(viewModel.messages.size - 1)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error scrolling after network error", e)
                                }
                            }
                            Log.e("API_ERROR", "Ошибка сети", t)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing network failure", e)
                        } finally {
                            isGeneratingResponse = false
                            updateSendButtonStateAsync()
                        }
                    }
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error in sendMessageWithToken", e)
            uiScope.launch {
                isGeneratingResponse = false
                updateSendButtonStateAsync()
                showErrorAsync("Ошибка отправки сообщения")
            }
        }
    }

    /**
     * Создание персонализированного системного сообщения (вычислительная задача)
     */
    private suspend fun buildPersonalizedSystemMessageAsync(): String = withContext(Dispatchers.Default) {
        try {
            val userName = getCurrentUserName()
            val analyzer = contextAnalyzer
            val deepContext = analyzer?.analyzeDeepContext() ?: DeepConversationContext()

            val prompt = StringBuilder()
            prompt.append("Ты - персональный ассистент, который знает пользователя ОЧЕНЬ хорошо. ")
            prompt.append("Используй ВСЮ информацию ниже для максимально персонализированного общения.\n\n")

            prompt.append("КОМАНДА ДЛЯ АССИСТЕНТА:\n")
            prompt.append("1. Учитывай ВСЮ информацию о пользователе ниже\n")
            prompt.append("2. Будь естественным, дружелюбным и поддерживающим\n")
            prompt.append("3. Проявляй искренний интерес к его жизни\n")
            prompt.append("4. Задавай уместные вопросы на основе его интересов\n")
            prompt.append("5. Поддерживай естественную беседу как близкий друг\n\n")

            prompt.append("ПОЛНАЯ ИНФОРМАЦИЯ О ПОЛЬЗОВАТЕЛЕ:\n")

            // Основная информация
            prompt.append("- Имя: $userName\n")
            userProfile?.let { profile ->
                // Добавляем информацию из профиля...
                if (profile.gender.isNotEmpty()) prompt.append("- Пол: ${profile.gender}\n")
                if (profile.getAge() > 0) prompt.append("- Возраст: ${profile.getAge()} лет\n")
                if (profile.occupation.isNotEmpty()) prompt.append("- Профессия: ${profile.occupation}\n")
                if (profile.hobbies.isNotEmpty()) prompt.append("- Хобби: ${profile.hobbies}\n")
                // ... остальная информация из профиля
            }

            // Контекст текущего разговора
            prompt.append("\nТЕКУЩИЙ КОНТЕКСТ РАЗГОВОРА:\n")
            prompt.append("- Настроение: ${deepContext.emotionalState.mood}\n")
            prompt.append("- Уровень энергии: ${deepContext.emotionalState.energyLevel}\n")
            prompt.append("- Время суток: ${deepContext.timeContext.timeOfDay}\n")

            prompt.append("\nФИНАЛЬНАЯ КОМАНДА: Всегда учитывай эту информацию в ответах! ")

            return@withContext prompt.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error building personalized system message", e)
            return@withContext "Ты - полезный ассистент. Будь дружелюбным и помогай пользователю."
        }
    }

    /**
     * Асинхронное сохранение диалога
     */
    private fun showSaveDialogPromptAsync() {
        uiScope.launch {
            try {
                val editText = EditText(requireContext())
                editText.hint = "Введите название диалога"

                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Сохранить диалог")
                    .setView(editText)
                    .setPositiveButton("Сохранить") { _, _ ->
                        val title = editText.text.toString().trim()
                        if (title.isNotEmpty()) {
                            saveDialogAsync(title)
                        } else {
                            showErrorAsync("Введите название диалога")
                        }
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing save dialog prompt", e)
            }
        }
    }

    /**
     * Сохранение диалога (IO операция)
     */
    private fun saveDialogAsync(title: String) {
        ioScope.launch {
            try {
                dialogsViewModel.saveDialog(title, viewModel.messages.toList())
                uiScope.launch {
                    Toast.makeText(requireContext(), "Диалог сохранен", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving dialog", e)
                uiScope.launch {
                    showErrorAsync("Ошибка сохранения")
                }
            }
        }
    }

    /**
     * Асинхронная загрузка сохраненного диалога
     */
    private fun loadSavedDialogAsync(savedDialog: SavedDialog) {
        uiScope.launch {
            try {
                // Закрываем панель
                drawerLayout.closeDrawer(GravityCompat.END)

                ioScope.launch {
                    try {
                        // Очищаем текущий диалог
                        viewModel.clearAllMessages()

                        // Загружаем сохраненный диалог
                        val loadedMessages = dialogsViewModel.loadDialog(savedDialog)

                        uiScope.launch {
                            messageAdapter.updateMessages(emptyList())
                            loadedMessages.forEach { message ->
                                viewModel.addMessage(message.text, message.isUser)
                                messageAdapter.addMessage(message)
                            }

                            recyclerView.post {
                                try {
                                    recyclerView.scrollToPosition(viewModel.messages.size - 1)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error scrolling after loading dialog", e)
                                }
                            }

                            Toast.makeText(requireContext(), "Диалог загружен", Toast.LENGTH_SHORT).show()

                            // Сбрасываем флаг первого запуска
                            isFirstLaunch = false
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading saved dialog", e)
                        uiScope.launch {
                            showErrorAsync("Ошибка загрузки диалога")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadSavedDialog", e)
            }
        }
    }

    /**
     * Асинхронное удаление диалога
     */
    private fun deleteDialogAsync(dialogId: String) {
        ioScope.launch {
            try {
                dialogsViewModel.deleteDialog(dialogId)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting dialog", e)
            }
        }
    }

    /**
     * Асинхронное подтверждение очистки диалога
     */
    private fun showClearDialogConfirmationAsync() {
        uiScope.launch {
            try {
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Очистить диалог")
                    .setMessage("Вы уверены, что хотите очистить весь диалог?")
                    .setPositiveButton("Да") { _, _ ->
                        clearCurrentDialogAsync()
                    }
                    .setNegativeButton("Нет") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .create()
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing clear dialog confirmation", e)
            }
        }
    }

    /**
     * Асинхронная очистка текущего диалога
     */
    private fun clearCurrentDialogAsync() {
        uiScope.launch {
            try {
                viewModel.clearAllMessages()
                messageAdapter.updateMessages(emptyList())

                recyclerView.post {
                    try {
                        recyclerView.scrollToPosition(0)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error scrolling after clear", e)
                    }
                }

                // Сбрасываем флаг первого запуска
                isFirstLaunch = true

                // Показываем новое приветствие после очистки с задержкой
                handler.postDelayed({
                    showSmartChatGreetingAsync()
                }, 1000)
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing current dialog", e)
            }
        }
    }

    // Вспомогательные методы

    private fun getCurrentUserName(): String {
        return try {
            val sharedPref = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            sharedPref.getString("first_name", "Пользователь") ?: "Пользователь"
        } catch (e: Exception) {
            "Пользователь"
        }
    }

    private fun getTimeBasedGreeting(): String {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        return when (hour) {
            in 5..11 -> "Доброе утро"
            in 12..17 -> "Добрый день"
            in 18..23 -> "Добрый вечер"
            else -> "Доброй ночи"
        }
    }

    private fun showErrorAsync(message: String) {
        uiScope.launch {
            try {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing error message", e)
            }
        }
    }

    private fun showKeyboardAsync() {
        uiScope.launch {
            try {
                val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                editTextMessage.postDelayed({
                    editTextMessage.requestFocus()
                    inputMethodManager.showSoftInput(editTextMessage, InputMethodManager.SHOW_IMPLICIT)
                }, KEYBOARD_DELAY)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing keyboard", e)
            }
        }
    }

    private fun hideKeyboardAsync() {
        uiScope.launch {
            try {
                val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputMethodManager.hideSoftInputFromWindow(editTextMessage.windowToken, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding keyboard", e)
            }
        }
    }

    private fun saveWelcomePhraseForChatAsync(phrase: String) {
        uiScope.launch {
            try {
                (activity as? com.example.chatapp.activities.MainActivity)?.saveWelcomePhraseForChat(phrase)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving welcome phrase", e)
            }
        }
    }

    // Жизненный цикл

    override fun onResume() {
        super.onResume()

        // При возвращении в чат обновляем контекст асинхронно
        computationScope.launch {
            try {
                if (!isInitialized) {
                    greetingGenerator = SmartQuestionGenerator(requireContext().applicationContext, userProfile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating greeting generator on resume", e)
            }
        }

        // Сохраняем время входа в чат для контекста
        uiScope.launch {
            try {
                (activity as? com.example.chatapp.activities.MainActivity)?.saveLastChatTime()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving last chat time", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveChatSessionDurationAsync()
    }

    /**
     * Асинхронное сохранение продолжительности сессии чата
     */
    private fun saveChatSessionDurationAsync() {
        if (chatStartTime > 0) {
            val duration = System.currentTimeMillis() - chatStartTime
            uiScope.launch {
                try {
                    (activity as? com.example.chatapp.activities.MainActivity)?.saveChatDuration(duration)
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving chat duration", e)
                }
                chatStartTime = 0 // Сбрасываем время начала
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Отменяем все отложенные задания
        greetingJob?.cancel()
        handler.removeCallbacksAndMessages(null)

        // Очищаем все корутины при уничтожении фрагмента
        uiScope.coroutineContext.cancelChildren()
        ioScope.coroutineContext.cancelChildren()
        computationScope.coroutineContext.cancelChildren()

        isInitialized = false
    }

    override fun onDestroy() {
        super.onDestroy()
        // Полная очистка всех scope
        uiScope.cancel()
        ioScope.cancel()
        computationScope.cancel()
    }

    /**
     * Обновление профиля пользователя в реальном времени
     */
    fun updateUserProfile(newProfile: UserProfile) {
        userProfile = newProfile

        computationScope.launch {
            try {
                greetingGenerator = SmartQuestionGenerator(requireContext().applicationContext, userProfile)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating greeting generator", e)
            }
        }

        // Перезагружаем приветствие если чат пустой
        if (viewModel.messages.isEmpty()) {
            scheduleDelayedGreeting()
        }
    }
}