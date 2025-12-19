package com.example.chatapp.privetstvie_giga

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.SavedDialog
import com.example.chatapp.activities.MainActivity
import com.example.chatapp.api.AuthRetrofitInstance
import com.example.chatapp.api.GigaChatRequest
import com.example.chatapp.api.Message
import com.example.chatapp.api.RetrofitInstance
import com.example.chatapp.utils.TTSManager
import com.example.chatapp.viewmodels.DialogsViewModel
import com.google.android.material.internal.ViewUtils.hideKeyboard
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import okhttp3.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.net.URLEncoder
import java.util.*
import kotlin.math.abs

class ChatWithGigaFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var messageAdapter: GigaMessageAdapter
    private lateinit var editTextMessage: EditText
    private lateinit var btnSendMessage: ImageButton
    private val viewModel: GigaChatViewModel by viewModels { GigaChatViewModelFactory(requireActivity()) }
    private val dialogsViewModel: DialogsViewModel by viewModels()

    // Навигация
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var btnBackToMain: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var btnCloseMenu: ImageButton
    private lateinit var btnBackToMenu: ImageButton
    private lateinit var btnCloseDialogs: ImageButton

    // Контейнеры для панелей
    private lateinit var menuContainer: FrameLayout
    private lateinit var dialogsContainer: FrameLayout

    // Пункты меню
    private lateinit var menuSavedDialogs: View
    private lateinit var menuSaveDialog: View
    private lateinit var menuClearDialog: View
    private lateinit var menuSettings: View

    // TTS меню
    private lateinit var menuTTSControl: View
    private lateinit var switchTTS: android.widget.Switch
    private lateinit var tvTTSStatus: TextView

    private lateinit var savedDialogsRecyclerView: RecyclerView
    private lateinit var savedDialogsAdapter: SavedDialogsAdapter
    private lateinit var tvEmptyDialogs: TextView

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

    // TTS
    private lateinit var ttsManager: TTSManager
    private var isTTSEnabled = true
    private var isTTSInitializationStarted = false
    private val pendingTTSQueue = mutableListOf<Pair<String, String>>()

    // Асинхронные компоненты
    private val handler = Handler(Looper.getMainLooper())
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val computationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())



    // Флаг инициализации
    private var isInitialized = false
    private var greetingJob: Job? = null

    // YANDEX SPEECHKIT TTS (fallback)
    private val YC_API_KEY = "AQVN2daCiBDJ8-CoJCdT5f1Rhz7wFEDqClbRpJwM"

    companion object {
        private const val TAG = "ChatWithGigaFragment"
        private const val SCROLL_DELAY = 100L
        private const val GREETING_DELAY = 500L
        private const val KEYBOARD_DELAY = 100L
        private const val INIT_DELAY = 100L
        private const val MAX_TTS_TEXT_LENGTH = 500
        private const val DOUBLE_CLICK_DELAY = 300L

        fun newInstance(): ChatWithGigaFragment {
            return ChatWithGigaFragment()
        }
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

        // ВАЖНО: Проверяем, что фрагмент прикреплен к Activity
        if (!isAdded || activity == null) {
            Log.w(TAG, "Fragment not attached to activity in onViewCreated")
            return
        }

        // Инициализация TTS как можно раньше
        initTTSManager()

        hideSystemUI()
        activity?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        initBasicUI(view)
        setupSystemInsets(view)

        // Загрузка настроек TTS
        loadTTSSettings()

        handler.postDelayed({
            initializeAsyncComponents()
        }, INIT_DELAY)
    }

    override fun onResume() {
        super.onResume()

        // ВАЖНО: При возобновлении всегда включаем полноэкранный режим
        hideSystemUI()

        activity?.let {
            if (it is MainActivity) {
                // Уведомляем MainActivity, что мы в чате
                it.hideSystemUIForChat()
            }
        }

        // Если вернулись из настроек голоса, убедимся что все скрыто
        handler.postDelayed({
            hideSystemUI()
        }, 100)
    }

    /**
     * Инициализация TTS Manager с защитой от повторной инициализации и проверкой Activity
     */
    private fun initTTSManager() {
        if (isTTSInitializationStarted) {
            Log.d(TAG, "TTS initialization already started")
            return
        }

        // ВАЖНО: Проверяем, что фрагмент прикреплен к Activity
        if (!isAdded || activity == null) {
            Log.w(TAG, "Fragment not attached to activity, delaying TTS initialization")
            handler.postDelayed({
                if (isAdded && !isDetached && activity != null) {
                    initTTSManager()
                }
            }, 500)
            return
        }

        isTTSInitializationStarted = true

        ttsManager = TTSManager(requireActivity().applicationContext) { initialized ->
            if (!isAdded || isDetached || activity == null) {
                Log.w(TAG, "Fragment detached during TTS initialization")
                return@TTSManager
            }

            requireActivity().runOnUiThread {
                if (initialized) {
                    Log.d(TAG, "TTS Manager initialized successfully")

                    // Обновляем UI в главном потоке
                    if (::switchTTS.isInitialized) {
                        switchTTS.isEnabled = true
                        tvTTSStatus.text = "Озвучка: ВКЛ"
                    }

                    // Обрабатываем очередь если есть
                    processTTSPendingQueue()

                } else {
                    Log.e(TAG, "TTS Manager initialization failed")
                    isTTSEnabled = false

                    if (::switchTTS.isInitialized) {
                        switchTTS.isChecked = false
                        switchTTS.isEnabled = false
                        tvTTSStatus.text = "Озвучка: недоступна"
                    }

                    showToast("Озвучка недоступна")
                }
            }
        }
    }


    private fun openVoiceSettings() {
        try {
            // Сохраняем текущее состояние чата
            saveChatSessionDuration()

            val fragment = VoiceSettingsFragment.newInstance()
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack("voice_settings")
                .commitAllowingStateLoss()

            // Уведомляем MainActivity о переходе
            (activity as? MainActivity)?.onVoiceSettingsOpenedFromChat()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening voice settings", e)
            Toast.makeText(requireContext(), "Ошибка открытия настроек", Toast.LENGTH_SHORT).show()
        }
    }




    /**
     * Обработка очереди сообщений ожидающих TTS инициализации
     */
    private fun processTTSPendingQueue() {
        if (!isAdded || activity == null) return

        if (pendingTTSQueue.isNotEmpty() && ttsManager.isInitialized) {
            Log.d(TAG, "Processing ${pendingTTSQueue.size} pending TTS messages")

            pendingTTSQueue.forEach { (text, type) ->
                speakText(text, type, false)
            }
            pendingTTSQueue.clear()
        }
    }

    /**
     * Базовая инициализация UI
     */
    private fun initBasicUI(view: View) {
        try {
            recyclerView = view.findViewById(R.id.recyclerViewMessages)
            editTextMessage = view.findViewById(R.id.editTextMessage)
            btnSendMessage = view.findViewById(R.id.btnSendMessage)
            drawerLayout = view.findViewById(R.id.drawer_layout)

            btnBackToMain = view.findViewById(R.id.btnBackToMain)
            btnMenu = view.findViewById(R.id.btnMenu)
            btnCloseMenu = view.findViewById(R.id.btnCloseMenu)
            btnBackToMenu = view.findViewById(R.id.btnBackToMenu)
            btnCloseDialogs = view.findViewById(R.id.btnCloseDialogs)

            menuContainer = view.findViewById(R.id.menuContainer)
            dialogsContainer = view.findViewById(R.id.dialogsContainer)

            menuSavedDialogs = view.findViewById(R.id.menuSavedDialogs)
            menuSaveDialog = view.findViewById(R.id.menuSaveDialog)
            menuClearDialog = view.findViewById(R.id.menuClearDialog)
            menuSettings = view.findViewById(R.id.menuSettings)

            // === КНОПКА НАСТРОЕК ГОЛОСА ===
            val btnVoiceSettings = view.findViewById<ImageButton>(R.id.btnVoiceSettings)
            btnVoiceSettings?.setOnClickListener {
                openVoiceSettings()
            }

            // TTS элементы меню
            menuTTSControl = view.findViewById(R.id.menuTTSControl)
            switchTTS = view.findViewById(R.id.switchTTS)
            tvTTSStatus = view.findViewById(R.id.tvTTSStatus)

            savedDialogsRecyclerView = view.findViewById(R.id.recyclerViewSavedDialogs)
            tvEmptyDialogs = view.findViewById(R.id.tvEmptyDialogs)

            setupBasicAdapters()
            setupBasicListeners()
            loadExistingMessagesFast()
            setupKeyboardHandling()
            setupSystemUISwipeListener()

            Log.d(TAG, "Basic UI initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in basic UI initialization", e)
        }
    }


    private fun setupBasicAdapters() {
        try {
            // Создаем адаптер сообщений с обработчиками двойного и долгого нажатия
            messageAdapter = GigaMessageAdapter(
                onMessageClickListener = { message ->
                    // Двойной клик - повторная озвучка сообщения
                    repeatMessageSpeech(message)
                },
                onMessageLongClickListener = { message ->
                    // Долгое нажатие - показ контекстного меню
                    showMessageContextMenu(message)
                }
            )

            // Настраиваем RecyclerView для сообщений
            recyclerView.apply {
                layoutManager = LinearLayoutManager(requireContext()).apply {
                    stackFromEnd = true
                }
                adapter = messageAdapter
                itemAnimator = null

                // Добавляем слушатель прокрутки для автоматической прокрутки к новым сообщениям
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        super.onScrolled(recyclerView, dx, dy)
                        // Можно добавить логику для скрытия/показа кнопки прокрутки вниз
                    }
                })

                // Добавляем аниматор для плавной прокрутки
                val layoutAnimation = AnimationUtils.loadLayoutAnimation(
                    requireContext(),
                    R.anim.layout_animation_fall_down
                )
                this.layoutAnimation = layoutAnimation
            }

            // Настраиваем адаптер для сохраненных диалогов
            savedDialogsAdapter = SavedDialogsAdapter(
                onDialogSelected = { savedDialog ->
                    // Загрузка выбранного диалога
                    loadSavedDialogAsync(savedDialog)
                },
                onDialogDeleted = { dialogId ->
                    // Удаление диалога
                    deleteDialogAsync(dialogId.toString())
                }
            )

            // Настраиваем RecyclerView для сохраненных диалогов
            savedDialogsRecyclerView.apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = savedDialogsAdapter

                // Добавляем разделитель между элементами
                addItemDecoration(
                    DividerItemDecoration(
                        requireContext(),
                        LinearLayoutManager.VERTICAL
                    ).apply {
                        setDrawable(
                            ContextCompat.getDrawable(
                                requireContext(),
                                R.drawable.divider_horizontal
                            ) ?: ColorDrawable(Color.parseColor("#E0E0E0"))
                        )
                    }
                )
            }

            // Настройка состояния пустого списка диалогов
            tvEmptyDialogs.apply {
                text = "Нет сохраненных диалогов"
                setTextColor(Color.parseColor("#757575"))
                textSize = 14f
                gravity = Gravity.CENTER
                visibility = View.GONE
            }

            // Инициализация слушателя данных для автоматической прокрутки
            messageAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    super.onItemRangeInserted(positionStart, itemCount)

                    // Автоматическая прокрутка при добавлении новых сообщений
                    if (positionStart == messageAdapter.itemCount - 1) {
                        recyclerView.postDelayed({
                            try {
                                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                                val lastVisiblePosition = layoutManager.findLastCompletelyVisibleItemPosition()
                                val totalItemCount = layoutManager.itemCount

                                // Прокручиваем если последнее сообщение не видно или видно не полностью
                                if (lastVisiblePosition == RecyclerView.NO_POSITION ||
                                    lastVisiblePosition < totalItemCount - 2) {
                                    recyclerView.smoothScrollToPosition(messageAdapter.itemCount - 1)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in auto-scroll", e)
                            }
                        }, 100)
                    }
                }

                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                    super.onItemRangeChanged(positionStart, itemCount)
                    // Можно добавить обработку изменений сообщений
                }

                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                    super.onItemRangeRemoved(positionStart, itemCount)
                    // Можно добавить обработку удаления сообщений
                }
            })

            Log.d(TAG, "Basic adapters setup completed")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up basic adapters", e)
            showToast("Ошибка инициализации чата")

            // Fallback: минимальная настройка адаптеров
            try {
                messageAdapter = GigaMessageAdapter(
                    onMessageClickListener = { message ->
                        repeatMessageSpeech(message)
                    },
                    onMessageLongClickListener = { message ->
                        showMessageContextMenu(message)
                    }
                )

                recyclerView.layoutManager = LinearLayoutManager(requireContext()).apply {
                    stackFromEnd = true
                }
                recyclerView.adapter = messageAdapter

                savedDialogsAdapter = SavedDialogsAdapter(
                    onDialogSelected = { loadSavedDialogAsync(it) },
                    onDialogDeleted = { deleteDialogAsync(it.toString()) }
                )

                savedDialogsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
                savedDialogsRecyclerView.adapter = savedDialogsAdapter

            } catch (e2: Exception) {
                Log.e(TAG, "Fallback adapter setup also failed", e2)
            }
        }
    }

    /**
     * Вспомогательный метод для повтора озвучки сообщения
     */
    private fun repeatMessageSpeech(message: GigaMessage) {
        if (!isTTSEnabled) {
            showToast("Озвучка отключена")
            return
        }

        if (!ttsManager.isInitialized) {
            showToast("Озвучка ещё не готова, подождите...")

            // Добавляем в очередь ожидания только если его там еще нет
            val isAlreadyInQueue = pendingTTSQueue.any { it.first == message.text }
            if (!isAlreadyInQueue) {
                pendingTTSQueue.add(Pair(message.text,
                    if (message.isUser) TTSManager.TYPE_CHAT_USER else TTSManager.TYPE_CHAT_BOT))
            }
            return
        }

        // Останавливаем текущую озвучку и говорим новое сообщение
        ttsManager.stop()
        ttsManager.speak(message.text,
            if (message.isUser) TTSManager.TYPE_CHAT_USER else TTSManager.TYPE_CHAT_BOT,
            true
        )

        // Показывает анимацию повторной озвучки
        showSpeechRepeatAnimation()
    }

    /**
     * Вспомогательный метод для показа контекстного меню сообщения
     */
    private fun showMessageContextMenu(message: GigaMessage) {
        try {
            // Проверка перед показом меню
            if (!isAdded || activity == null) return

            val options = arrayOf(
                "🔊 Повторить озвучку",
                "📋 Скопировать текст",
                "📤 Поделиться",
                "❌ Удалить сообщение"
            )

            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Сообщение")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            // Повторить озвучку
                            repeatMessageSpeech(message)
                        }
                        1 -> {
                            // Скопировать текст
                            copyMessageText(message)
                        }
                        2 -> {
                            // Поделиться
                            shareMessageText(message)
                        }
                        3 -> {
                            // Удалить сообщение
                            deleteMessage(message)
                        }
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing message context menu", e)
        }
    }





    /**
     * Показывает анимацию повторной озвучки
     */
    private fun showSpeechRepeatAnimation() {
        try {
            if (!isAdded || activity == null) return
            Toast.makeText(requireContext(), "🔊 Повторяю...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing speech repeat animation", e)
        }
    }

    /**
     * Копирует текст сообщения в буфер обмена
     */
    private fun copyMessageText(message: GigaMessage) {
        try {
            if (!isAdded || activity == null) return

            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Сообщение из чата", message.text)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(requireContext(), "Текст скопирован", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error copying message text", e)
            Toast.makeText(requireContext(), "Ошибка копирования", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Делится текстом сообщения
     */
    private fun shareMessageText(message: GigaMessage) {
        try {
            if (!isAdded || activity == null) return

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, message.text)
                type = "text/plain"
            }

            startActivity(Intent.createChooser(shareIntent, "Поделиться сообщением"))
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing message text", e)
            Toast.makeText(requireContext(), "Ошибка при попытке поделиться", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Удаляет сообщение
     */
    private fun deleteMessage(message: GigaMessage) {
        try {
            if (!isAdded || activity == null) return

            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Удалить сообщение")
                .setMessage("Вы уверены, что хотите удалить это сообщение?")
                .setPositiveButton("Удалить") { _, _ ->
                    // Удаляем сообщение из ViewModel и адаптера
                    viewModel.removeMessage(message)
                    messageAdapter.updateMessages(viewModel.messages.toList())

                    // Показываем уведомление об удалении
                    Toast.makeText(requireContext(), "Сообщение удалено", Toast.LENGTH_SHORT).show()

                    // Если удалили последнее сообщение, показываем приветствие
                    if (viewModel.messages.isEmpty()) {
                        isFirstLaunch = true
                        handler.postDelayed({
                            showSmartChatGreeting()
                        }, 500)
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting message", e)
            Toast.makeText(requireContext(), "Ошибка удаления сообщения", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Показ умного приветствия в чате
     */
    private fun showSmartChatGreeting() {
        if (!shouldShowGreeting()) return
        uiScope.launch {
            try {
                // Загружаем фразу в фоновом потоке
                val continuationPhrase = withContext(Dispatchers.IO) {
                    try {
                        loadContinuationPhraseForChat()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading continuation phrase", e)
                        "Рад нашей беседе! Чем могу помочь?"
                    }
                }

                // Синхронно добавляем сообщение
                withContext(Dispatchers.Main) {
                    val message = GigaMessage(continuationPhrase, false, System.currentTimeMillis())

                    // Добавляем в ViewModel (синхронно)
                    viewModel.addMessage(continuationPhrase, false)

                    // Добавляем в адаптер
                    messageAdapter.addMessage(message)
                    scrollToLastMessage()

                    // ОЗВУЧКА приветствия
                    speakText(continuationPhrase, TTSManager.TYPE_GREETING)

                    Log.d(TAG, "Contextual greeting displayed: $continuationPhrase")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error showing smart chat greeting", e)
                showFallbackGreeting()
            }
        }
    }

    /**
     * Базовая настройка слушателей
     */
    private fun setupBasicListeners() {
        try {
            // === КНОПКА ВЫХОДА (возврат в MainActivity) ===
            btnBackToMain.setOnClickListener {
                navigateToMainScreen()
            }

            // === КНОПКА МЕНЮ ===
            btnMenu.setOnClickListener {
                openSidePanel()
                showMenuPanel()
            }

            // === Закрыть меню ===
            btnCloseMenu.setOnClickListener {
                closeSidePanel()
            }

            // === Назад из диалогов в меню ===
            btnBackToMenu.setOnClickListener {
                showMenuPanel()
            }

            // === Закрыть диалоги ===
            btnCloseDialogs.setOnClickListener {
                closeSidePanel()
            }

            // === Пункты меню ===
            menuSavedDialogs.setOnClickListener {
                showDialogsPanel()
                loadSavedDialogsAsync()
            }

            menuSaveDialog.setOnClickListener {
                closeSidePanel()
                showSaveDialogPromptAsync()
            }

            menuClearDialog.setOnClickListener {
                closeSidePanel()
                showClearDialogConfirmationAsync()
            }

            menuSettings.setOnClickListener {
                closeSidePanel()
                openSettings()
            }

            // === Управление TTS ===
            menuTTSControl.setOnClickListener {
                // При клике на весь элемент - переключаем switch
                switchTTS.isChecked = !switchTTS.isChecked
                onTTSSwitchChanged(switchTTS.isChecked)
            }

            switchTTS.setOnCheckedChangeListener { _, isChecked ->
                onTTSSwitchChanged(isChecked)
            }

            // Отправка сообщения
            btnSendMessage.setOnClickListener {
                sendUserMessageAsync()
            }

            editTextMessage.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_DOWN) {
                    sendUserMessageAsync()
                    true
                } else {
                    false
                }
            }

            // Скрытие клавиатуры при касании списка сообщений
            recyclerView.setOnTouchListener { _, _ ->
                hideKeyboard()
                hideSystemUI()
                false
            }

            // Отслеживание текста
            setupTextWatcherAsync()

            Log.d(TAG, "Basic listeners setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up basic listeners", e)
        }
    }

    /**
     * Загрузка настроек TTS
     */
    private fun loadTTSSettings() {
        try {
            if (!isAdded || activity == null) return

            val sharedPref = requireContext().getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
            isTTSEnabled = sharedPref.getBoolean("tts_enabled", true)

            // Обновляем UI если view уже создан
            if (::switchTTS.isInitialized) {
                requireActivity().runOnUiThread {
                    switchTTS.isChecked = isTTSEnabled
                    val statusText = when {
                        !isTTSEnabled -> "Озвучка: ВЫКЛ"
                        !ttsManager.isInitialized -> "Озвучка: инициализация..."
                        else -> "Озвучка: ВКЛ"
                    }
                    tvTTSStatus.text = statusText
                    switchTTS.isEnabled = ttsManager.isInitialized || !isTTSEnabled
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TTS settings", e)
        }
    }

    /**
     * Сохранение настроек TTS
     */
    private fun saveTTSSettings() {
        try {
            if (!isAdded || activity == null) return

            val sharedPref = requireContext().getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
            sharedPref.edit().putBoolean("tts_enabled", isTTSEnabled).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving TTS settings", e)
        }
    }

    /**
     * Обработка изменения состояния TTS
     */
    private fun onTTSSwitchChanged(isEnabled: Boolean) {
        isTTSEnabled = isEnabled

        // Обновляем текст статуса
        val statusText = when {
            !isEnabled -> "Озвучка: ВЫКЛ"
            !ttsManager.isInitialized -> "Озвучка: инициализация..."
            else -> "Озвучка: ВКЛ"
        }

        tvTTSStatus.text = statusText

        if (!isEnabled) {
            // Останавливаем текущую озвучку и очищаем очередь
            ttsManager.stop()
            ttsManager.clearQueue()
            pendingTTSQueue.clear()
        } else {
            // При включении проверяем инициализацию
            if (!ttsManager.isInitialized) {
                showToast("Озвучка инициализируется...")
            } else {
                // Озвучиваем последнее сообщение бота если есть
                val lastMessage = viewModel.messages.lastOrNull { !it.isUser }
                lastMessage?.let { message ->
                    speakText(message.text, TTSManager.TYPE_CHAT_BOT)
                }
            }
        }

        // Сохраняем настройку
        saveTTSSettings()
    }

    private fun navigateToMainScreen() {
        try {
            saveChatSessionDuration()
            closeSidePanel()

            // Останавливаем TTS перед уходом
            ttsManager.stop()

            // Восстанавливаем UI через метод MainActivity
            val mainActivity = requireActivity() as? MainActivity
            mainActivity?.restoreUIAfterChat()

            // Убираем текущий фрагмент
            requireActivity().supportFragmentManager.beginTransaction()
                .remove(this@ChatWithGigaFragment)
                .commit()

        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to main screen", e)
        }
    }

    /**
     * Открытие боковой панели
     */
    private fun openSidePanel() {
        try {
            drawerLayout.openDrawer(GravityCompat.END)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening side panel", e)
        }
    }

    /**
     * Закрытие боковой панели
     */
    private fun closeSidePanel() {
        try {
            drawerLayout.closeDrawer(GravityCompat.END)
        } catch (e: Exception) {
            Log.e(TAG, "Error closing side panel", e)
        }
    }

    /**
     * Показать панель меню
     */
    private fun showMenuPanel() {
        try {
            menuContainer.visibility = View.VISIBLE
            dialogsContainer.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Error showing menu panel", e)
        }
    }

    /**
     * Показать панель диалогов
     */
    private fun showDialogsPanel() {
        try {
            menuContainer.visibility = View.GONE
            dialogsContainer.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e(TAG, "Error showing dialogs panel", e)
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
     * Асинхронная загрузка сохраненных диалогов
     */
    private fun loadSavedDialogsAsync() {
        uiScope.launch {
            try {
                dialogsViewModel.savedDialogs.observe(viewLifecycleOwner) { dialogs ->
                    try {
                        savedDialogsAdapter.updateDialogs(dialogs)
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
     * Озвучивает текст через TTS Manager или Yandex SpeechKit (fallback)
     */
    private fun speakText(text: String, type: String = TTSManager.TYPE_CHAT_BOT, interrupt: Boolean = true) {
        if (!isTTSEnabled || text.isBlank()) return

        try {
            // Очищаем текст для TTS
            val cleanText = prepareTextForTTS(text)

            if (cleanText.isBlank()) {
                Log.w(TAG, "Text is empty after cleaning")
                return
            }

            if (!ttsManager.isInitialized) {
                Log.d(TAG, "TTS not initialized yet, adding to pending queue: ${cleanText.take(30)}...")
                pendingTTSQueue.add(Pair(cleanText, type))
                return
            }

            // Проверяем длину текста
            if (cleanText.length > MAX_TTS_TEXT_LENGTH) {
                Log.w(TAG, "Text too long for TTS (${cleanText.length} chars)")

                // Разделяем текст на части
                val textParts = splitTextForTTS(cleanText)
                textParts.forEachIndexed { index, part ->
                    if (part.isNotBlank()) {
                        // Добавляем небольшую задержку между частями
                        val delay = if (index > 0) 500L else 0L
                        handler.postDelayed({
                            ttsManager.speak(part, type, interrupt) {
                                Log.d(TAG, "TTS part $index completed")
                            }
                        }, delay)
                    }
                }
            } else {
                // Используем TTS Manager
                ttsManager.speak(cleanText, type, interrupt) {
                    Log.d(TAG, "TTS completed for: ${cleanText.take(30)}...")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error speaking text", e)
        }
    }

    /**
     * Подготовка текста для TTS
     */
    private fun prepareTextForTTS(text: String): String {
        return try {
            var cleaned = text.trim()

            // Удаляем URL
            cleaned = cleaned.replace(Regex("https?://\\S+"), " [ссылка] ")

            // Удаляем специальные символы которые могут мешать TTS
            cleaned = cleaned.replace(Regex("[*_~`>|<\\[\\]{}]"), "")

            // Заменяем переносы строк
            cleaned = cleaned.replace(Regex("\\n+"), ". ")

            // Удаляем множественные пробелы
            cleaned = cleaned.replace(Regex("\\s+"), " ")

            // Добавляем точку в конце если нет знаков препинания
            if (cleaned.isNotEmpty() && !cleaned.last().isWhitespace()) {
                val lastChar = cleaned.last()
                if (!lastChar.isLetterOrDigit() && lastChar !in setOf('.', '!', '?', ',', ';', ':')) {
                    cleaned += "."
                }
            }

            cleaned.trim()
        } catch (e: Exception) {
            text
        }
    }

    /**
     * Разделение длинного текста для TTS
     */
    private fun splitTextForTTS(text: String): List<String> {
        val parts = mutableListOf<String>()
        var current = StringBuilder()

        // Разделяем по предложениям
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))

        for (sentence in sentences) {
            if (current.length + sentence.length + 1 > MAX_TTS_TEXT_LENGTH) {
                if (current.isNotEmpty()) {
                    parts.add(current.toString())
                    current.clear()
                }

                // Если одно предложение длиннее лимита, разбиваем по словам
                if (sentence.length > MAX_TTS_TEXT_LENGTH) {
                    val words = sentence.split(" ")
                    for (word in words) {
                        if (current.length + word.length + 1 > MAX_TTS_TEXT_LENGTH) {
                            if (current.isNotEmpty()) {
                                parts.add(current.toString())
                                current.clear()
                            }
                        }
                        if (current.isNotEmpty()) current.append(" ")
                        current.append(word)
                    }
                } else {
                    current.append(sentence)
                }
            } else {
                if (current.isNotEmpty()) current.append(" ")
                current.append(sentence)
            }
        }

        if (current.isNotEmpty()) {
            parts.add(current.toString())
        }

        return parts
    }

    /**
     * Fallback озвучка через Yandex SpeechKit
     */
    private fun speakWithYandex(text: String) {
        if (text.isBlank() || YC_API_KEY.isBlank()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Получаем IAM-токен по API-ключу
                val iamTokenResponse = OkHttpClient().newCall(
                    Request.Builder()
                        .url("https://iam.api.cloud.yandex.net/iam/v1/tokens")
                        .post(RequestBody.create(null, """{"apiKey":"$YC_API_KEY"}"""))
                        .build()
                ).execute()

                if (!iamTokenResponse.isSuccessful) {
                    Log.e(TAG, "Не удалось получить IAM-токен")
                    return@launch
                }

                val iamToken = iamTokenResponse.body?.string()
                    ?.substringAfter("\"iamToken\":\"")
                    ?.substringBefore("\"") ?: return@launch

                // Запрашиваем аудио у SpeechKit
                val encodedText = URLEncoder.encode(text, "UTF-8")
                val body = "text=$encodedText&lang=ru-RU&voice=alena&format=mp3"

                val ttsResponse = OkHttpClient().newCall(
                    Request.Builder()
                        .url("https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize")
                        .post(RequestBody.create(null, body))
                        .addHeader("Authorization", "Bearer $iamToken")
                        .addHeader("Content-Type", "application/x-www-form-urlencoded")
                        .build()
                ).execute()

                if (!ttsResponse.isSuccessful) {
                    Log.e(TAG, "Ошибка синтеза речи: ${ttsResponse.code}")
                    return@launch
                }

                val audioBytes = ttsResponse.body?.bytes() ?: return@launch

                // Воспроизводим аудио в главном потоке
                withContext(Dispatchers.Main) {
                    playAudio(audioBytes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка озвучки через Yandex", e)
            }
        }
    }

    /**
     * Воспроизводит аудио из байтов (для Yandex TTS)
     */
    private fun playAudio(audioBytes: ByteArray) {
        try {
            val tempFile = File(requireContext().cacheDir, "speech_${System.currentTimeMillis()}.mp3")
            tempFile.writeBytes(audioBytes)

            val mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setOnPreparedListener { start() }
                setOnCompletionListener {
                    tempFile.delete()
                    release()
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка воспроизведения аудио", e)
        }
    }

    /**
     * Настройка системных инсетов
     */
    private fun setupSystemInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            try {
                val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                Log.d(TAG, "System insets - navigation: ${navigationBars.bottom}, IME visible: $imeVisible")
                // Прокручиваем при появлении клавиатуры
                if (imeVisible) {
                    handler.postDelayed({
                        scrollToLastMessage()
                    }, 150)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in system insets setup", e)
            }
            return@setOnApplyWindowInsetsListener insets
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

                // Проверяем состояние TTS
                val ttsStatus = when {
                    !::ttsManager.isInitialized -> "TTS не создан"
                    !ttsManager.isInitialized -> "TTS инициализируется"
                    else -> "TTS готов"
                }
                Log.d(TAG, "TTS status: $ttsStatus")

                // Устанавливаем начальные настройки TTS в UI
                requireActivity().runOnUiThread {
                    if (::switchTTS.isInitialized) {
                        switchTTS.isChecked = isTTSEnabled
                        val statusText = when {
                            !isTTSEnabled -> "Озвучка: ВЫКЛ"
                            !ttsManager.isInitialized -> "Озвучка: инициализация..."
                            else -> "Озвучка: ВКЛ"
                        }
                        tvTTSStatus.text = statusText

                        // Блокируем switch пока TTS не инициализирован
                        switchTTS.isEnabled = ttsManager.isInitialized || !isTTSEnabled
                    }
                }

                val initializationJob = ioScope.async {
                    loadAllComponentsInBackground()
                }

                val components = withTimeout(10000) {
                    initializationJob.await()
                }

                withContext(Dispatchers.Main) {
                    userProfile = components.first
                    contextAnalyzer = components.second
                    greetingGenerator = components.third
                    isInitialized = true

                    loadSavedDialogsAsync()
                    scheduleDelayedGreeting()
                    setupScrollBehavior()
                    chatStartTime = System.currentTimeMillis()

                    // Разблокируем TTS switch если нужно
                    if (::switchTTS.isInitialized) {
                        switchTTS.isEnabled = true
                    }

                    Log.d(TAG, "All components initialized successfully")
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Component initialization timeout", e)
                withContext(Dispatchers.Main) {
                    showFallbackGreeting()
                    isInitialized = true

                    // Все равно разблокируем switch
                    if (::switchTTS.isInitialized) {
                        switchTTS.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing components", e)
                withContext(Dispatchers.Main) {
                    showFallbackGreeting()
                    isInitialized = true

                    // Все равно разблокируем switch
                    if (::switchTTS.isInitialized) {
                        switchTTS.isEnabled = true
                    }
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
                val profileDeferred = async {
                    try {
                        loadUserProfile()
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
        greetingJob?.cancel()
        greetingJob = uiScope.launch {
            try {
                Log.d(TAG, "Scheduling delayed greeting...")
                delay(GREETING_DELAY)
                if (isAdded && !isDetached && view != null) {
                    showSmartChatGreeting()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Greeting scheduling cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error in scheduled greeting", e)
            }
        }
    }



    /**
     * Загружает фразу продолжения для чата
     */
    private fun loadContinuationPhraseForChat(): String {
        return try {
            val sharedPref = requireContext().getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
            val phrase = sharedPref.getString("continuation_phrase", null)
            sharedPref.edit().remove("continuation_phrase").apply()
            phrase ?: "Рад нашей беседе! Чем могу помочь?"
        } catch (e: Exception) {
            Log.e(TAG, "Error loading continuation phrase", e)
            "Рад нашей беседе! Чем могу помочь?"
        }
    }

    /**
     * Настройка обработки клавиатуры
     */
    private fun setupKeyboardHandling() {
        try {
            editTextMessage.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    handler.postDelayed({
                        scrollToLastMessage()
                    }, 200)
                }
            }
            editTextMessage.setOnClickListener {
                handler.postDelayed({
                    scrollToLastMessage()
                }, 200)
            }
            Log.d(TAG, "Keyboard handling setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up keyboard handling", e)
        }
    }

    /**
     * Прокручивает к последнему сообщению
     */
    private fun scrollToLastMessage() {
        try {
            if (messageAdapter.itemCount > 0) {
                recyclerView.smoothScrollToPosition(messageAdapter.itemCount - 1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling to last message", e)
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
     * Добавление приветственного сообщения
     */
    private fun addWelcomeMessage(phrase: String) {
        uiScope.launch {
            try {
                viewModel.addMessage(phrase, false)
                messageAdapter.addMessage(GigaMessage(phrase, false))
                scrollToLastMessage()

                // ОЗВУЧКА приветствия
                speakText(phrase, TTSManager.TYPE_GREETING)

                Log.d(TAG, "Welcome message added: $phrase")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding welcome message", e)
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
     * Настройка поведения прокрутки
     */
    private fun setupScrollBehavior() {
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
     * Загрузка профиля пользователя
     */
    private suspend fun loadUserProfile(): UserProfile? = withContext(Dispatchers.IO) {
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
                saveMessageToHistory(userMessage)
                viewModel.addMessage(userMessage, true)
                messageAdapter.addMessage(GigaMessage(userMessage, true))

                // ОЗВУЧКА сообщения пользователя (опционально)
                if (isTTSEnabled) {
                    speakText(userMessage, TTSManager.TYPE_CHAT_USER)
                }

                editTextMessage.text.clear()
                hideKeyboard()
                scrollToLastMessage()
                getBotResponseAsync(userMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending user message", e)
                showError("Ошибка отправки сообщения")
            }
        }
    }

    /**
     * Сохраняет сообщения в историю чата
     */
    private fun saveMessageToHistory(message: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sharedPref = requireContext().getSharedPreferences("chat_history", Context.MODE_PRIVATE)
                val historyJson = sharedPref.getString("recent_messages", "[]")
                val messages = Gson().fromJson(historyJson, Array<String>::class.java).toMutableList()
                messages.add(message)
                if (messages.size > 20) {
                    if (messages.isNotEmpty()) {
                        messages.removeAt(0)
                    }
                }
                val newHistoryJson = Gson().toJson(messages)
                sharedPref.edit().putString("recent_messages", newHistoryJson).apply()
                Log.d(TAG, "Message saved to history: ${message.take(50)}...")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving message to history", e)
            }
        }
    }

    /**
     * Асинхронное получение ответа от бота
     */
    private fun getBotResponseAsync(userMessage: String) {
        if (isGeneratingResponse) return
        isGeneratingResponse = true
        updateSendButtonState()
        ioScope.launch {
            try {
                if (accessToken.isEmpty()) {
                    fetchAuthToken { token ->
                        uiScope.launch {
                            sendMessageWithToken(token, userMessage)
                        }
                    }
                } else {
                    sendMessageWithToken(accessToken, userMessage)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting bot response", e)
                uiScope.launch {
                    showError("Ошибка получения ответа")
                    isGeneratingResponse = false
                    updateSendButtonState()
                }
            }
        }
    }

    /**
     * Обновление состояния кнопки отправки
     */
    private fun updateSendButtonState() {
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
    private fun fetchAuthToken(onTokenReceived: (String) -> Unit) {
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
                            showError("Ошибка авторизации в API")
                            isGeneratingResponse = false
                            updateSendButtonState()
                        }
                    }
                }
                override fun onFailure(call: Call<com.example.chatapp.api.AuthResponse>, t: Throwable) {
                    Log.e("API_ERROR", "Ошибка подключения: ${t.message}")
                    uiScope.launch {
                        showError("Ошибка подключения к серверу")
                        isGeneratingResponse = false
                        updateSendButtonState()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching auth token", e)
            uiScope.launch {
                showError("Ошибка получения токена")
                isGeneratingResponse = false
                updateSendButtonState()
            }
        }
    }

    /**
     * Асинхронная отправка сообщения с токеном
     */
    private suspend fun sendMessageWithToken(token: String, userMessage: String) = withContext(Dispatchers.IO) {
        try {
            val systemMessage = buildPersonalizedSystemMessage()
            val messagesList = mutableListOf<Message>()
            messagesList.add(Message(role = "system", content = systemMessage))
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
                                scrollToLastMessage()

                                // ОЗВУЧКА ответа бота
                                speakText(botMessage, TTSManager.TYPE_CHAT_BOT)

                            } else {
                                val errorMessage = "Ошибка API: ${response.code()}"
                                viewModel.addMessage(errorMessage, false)
                                messageAdapter.addMessage(GigaMessage(errorMessage, false))
                                scrollToLastMessage()

                                // ОЗВУЧКА ошибки
                                speakText("Произошла ошибка при получении ответа", TTSManager.TYPE_ERROR)

                                Log.e("API_ERROR", "Ошибка ответа: ${response.errorBody()?.string()}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing API response", e)
                        } finally {
                            isGeneratingResponse = false
                            updateSendButtonState()
                        }
                    }
                }
                override fun onFailure(call: Call<com.example.chatapp.api.GigaChatResponse>, t: Throwable) {
                    uiScope.launch {
                        try {
                            val errorMessage = "Ошибка подключения: ${t.message}"
                            viewModel.addMessage(errorMessage, false)
                            messageAdapter.addMessage(GigaMessage(errorMessage, false))
                            scrollToLastMessage()

                            // ОЗВУЧКА ошибки сети
                            speakText("Ошибка подключения к серверу", TTSManager.TYPE_ERROR)

                            Log.e("API_ERROR", "Ошибка сети", t)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing network failure", e)
                        } finally {
                            isGeneratingResponse = false
                            updateSendButtonState()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendMessageWithToken", e)
            uiScope.launch {
                isGeneratingResponse = false
                updateSendButtonState()
                showError("Ошибка отправки сообщения")
            }
        }
    }

    private suspend fun buildPersonalizedSystemMessage(): String = withContext(Dispatchers.Default) {
        try {
            val userName = getCurrentUserName()
            val analyzer = contextAnalyzer
            val deepContext = analyzer?.analyzeDeepContext() ?: DeepConversationContext()
            val profile = userProfile
            val prompt = StringBuilder()
            prompt.append("Ты - персональный ассистент, который знает пользователя ОЧЕНЬ хорошо. ")
            prompt.append("Используй ВСЮ информацию ниже для максимально персонализированного общения.\n")
            prompt.append("КОМАНДА ДЛЯ АССИСТЕНТА:\n")
            prompt.append("1. Учитывай ВСЮ информацию о пользователе в КАЖДОМ ответе\n")
            prompt.append("2. Будь естественным, дружелюбным и поддерживающим\n")
            prompt.append("3. Проявляй искренний интерес к его жизни\n")
            prompt.append("4. Задавай уместные вопросы на основе его интересов\n")
            prompt.append("5. Поддерживай естественную беседу как близкий друг\n")
            prompt.append("6. Используй конкретные детали из его профиля\n")
            prompt.append("ПОЛНАЯ ИНФОРМАЦИЯ О ПОЛЬЗОВАТЕЛЕ:\n")
            // Основная информация
            prompt.append("👤 ОСНОВНАЯ ИНФОРМАЦИЯ:\n")
            prompt.append("- Имя: $userName\n")
            profile?.let { p ->
                if (p.gender.isNotEmpty()) prompt.append("- Пол: ${p.gender}\n")
                if (p.getAge() > 0) prompt.append("- Возраст: ${p.getAge()} лет\n")
                if (p.relationshipStatus.isNotEmpty()) prompt.append("- Семейное положение: ${p.relationshipStatus}\n")
                if (p.city.isNotEmpty()) prompt.append("- Город: ${p.city}\n")
            }
            // Профессия и работа
            prompt.append("\n💼 ПРОФЕССИЯ И РАБОТА:\n")
            profile?.let { p ->
                if (p.occupation.isNotEmpty()) prompt.append("- Сфера деятельности: ${p.occupation}\n")
                if (p.jobTitle.isNotEmpty()) prompt.append("- Должность: ${p.jobTitle}\n")
                if (p.workSchedule.isNotEmpty()) prompt.append("- График работы: ${p.workSchedule}\n")
                if (p.workStartTime.isNotEmpty()) prompt.append("- Начало работы: ${p.workStartTime}\n")
                if (p.workEndTime.isNotEmpty()) prompt.append("- Окончание работы: ${p.workEndTime}\n")
                if (p.dailyCommuteTime > 0) prompt.append("- Время на дорогу: ${p.dailyCommuteTime} мин\n")
            }
            // Семья и домашние условия
            prompt.append("\n🏠 СЕМЬЯ И ДОМ:\n")
            profile?.let { p ->
                if (p.hasChildren) {
                    prompt.append("- Есть дети: да\n")
                    if (p.childrenAges.isNotEmpty()) prompt.append("- Возраст детей: ${p.childrenAges}\n")
                } else {
                    prompt.append("- Есть дети: нет\n")
                }
                if (p.hasPets) {
                    prompt.append("- Есть питомцы: да\n")
                    if (p.petTypes.isNotEmpty()) prompt.append("- Вид питомцев: ${p.petTypes}\n")
                }
            }
            // ХОББИ И ИНТЕРЕСЫ
            prompt.append("\n🎯 ХОББИ И ИНТЕРЕСЫ:\n")
            profile?.let { p ->
                if (p.hobbies.isNotEmpty()) prompt.append("- Хобби: ${p.hobbies}\n")
                if (p.interests.isNotEmpty()) prompt.append("- Интересы: ${p.interests}\n")
                if (p.sports.isNotEmpty()) prompt.append("- Спорт: ${p.sports}\n")
                if (p.workoutTypes.isNotEmpty()) prompt.append("- Виды тренировок: ${p.workoutTypes}\n")
                if (p.fitnessLevel.isNotEmpty()) prompt.append("- Уровень физической подготовки: ${p.fitnessLevel}\n")
                if (p.workoutFrequency.isNotEmpty()) prompt.append("- Частота тренировок: ${p.workoutFrequency}\n")
            }
            // ПРЕДПОЧТЕНИЯ
            prompt.append("\n🎵 ПРЕДПОЧТЕНИЯ:\n")
            profile?.let { p ->
                if (p.musicPreferences.isNotEmpty()) prompt.append("- Музыка: ${p.musicPreferences}\n")
                if (p.movieGenres.isNotEmpty()) prompt.append("- Фильмы: ${p.movieGenres}\n")
                if (p.foodPreferences.isNotEmpty()) prompt.append("- Еда: ${p.foodPreferences}\n")
                if (p.favoriteCuisines.isNotEmpty()) prompt.append("- Любимые кухни: ${p.favoriteCuisines}\n")
                if (p.favoriteSeasons.isNotEmpty()) prompt.append("- Любимые времена года: ${p.favoriteSeasons}\n")
                if (p.cookingHabit.isNotEmpty()) prompt.append("- Привычки в готовке: ${p.cookingHabit}\n")
            }
            // ОБРАЗ ЖИЗНИ И РАСПИСАНИЕ
            prompt.append("\n📅 ОБРАЗ ЖИЗНИ:\n")
            profile?.let { p ->
                if (p.wakeUpTime.isNotEmpty()) prompt.append("- Пробуждение: ${p.wakeUpTime}\n")
                if (p.sleepQuality.isNotEmpty()) prompt.append("- Качество сна: ${p.sleepQuality}\n")
                if (p.readingHabit.isNotEmpty()) prompt.append("- Привычки чтения: ${p.readingHabit}\n")
                if (p.travelFrequency.isNotEmpty()) prompt.append("- Частота путешествий: ${p.travelFrequency}\n")
                if (p.weekendActivities.isNotEmpty()) prompt.append("- Активности на выходных: ${p.weekendActivities}\n")
            }
            // ЦЕЛИ И РАЗВИТИЕ
            prompt.append("\n🎯 ЦЕЛИ И РАЗВИТИЕ:\n")
            profile?.let { p ->
                if (p.currentGoals.isNotEmpty()) prompt.append("- Текущие цели: ${p.currentGoals}\n")
                if (p.learningInterests.isNotEmpty()) prompt.append("- Интересы в обучении: ${p.learningInterests}\n")
                if (p.learningStyle.isNotEmpty()) prompt.append("- Стиль обучения: ${p.learningStyle}\n")
            }
            // ЛИЧНОСТНЫЕ ХАРАКТЕРИСТИКИ
            prompt.append("\n💫 ЛИЧНОСТНЫЕ ХАРАКТЕРИСТИКИ:\n")
            profile?.let { p ->
                if (p.personalityType.isNotEmpty()) prompt.append("- Тип личности: ${p.personalityType}\n")
                if (p.communicationStyle.isNotEmpty()) prompt.append("- Стиль общения: ${p.communicationStyle}\n")
                if (p.stressManagement.isNotEmpty()) prompt.append("- Справление со стрессом: ${p.stressManagement}\n")
                if (p.socialActivity.isNotEmpty()) prompt.append("- Социальная активность: ${p.socialActivity}\n")
            }
            // ТЕКУЩИЙ КОНТЕКСТ
            prompt.append("\n🕒 ТЕКУЩИЙ КОНТЕКСТ:\n")
            prompt.append("- Время суток: ${deepContext.timeContext.timeOfDay}\n")
            prompt.append("- Настроение: ${deepContext.emotionalState.mood}\n")
            prompt.append("- Уровень энергии: ${deepContext.emotionalState.energyLevel}\n")
            // Активные темы из истории
            if (deepContext.activeTopics.isNotEmpty()) {
                prompt.append("- Недавние темы обсуждения: ")
                prompt.append(deepContext.activeTopics.take(3).joinToString { it.name })
                prompt.append("\n")
            }
            prompt.append("\n🎯 КОНКРЕТНЫЕ РЕКОМЕНДАЦИИ ДЛЯ ОБЩЕНИЯ:\n")
            // Рекомендации на основе профессии
            profile?.occupation?.let { occupation ->
                prompt.append("- Учитывай профессиональную сферу '$occupation' в советах\n")
            }
            // Рекомендации на основе хобби
            profile?.hobbies?.takeIf { it.isNotEmpty() }?.let { hobbies ->
                prompt.append("- Проявляй интерес к хобби: $hobbies\n")
            }
            // Рекомендации для родителей
            if (profile?.hasChildren == true) {
                prompt.append("- Интересуйся детьми и семейными делами\n")
                prompt.append("- Учитывай родительские обязанности в советах по времени\n")
            }
            // Рекомендации для спортивных людей
            if (profile?.fitnessLevel?.isNotEmpty() == true && profile.fitnessLevel != "Не занимаюсь спортом") {
                prompt.append("- Поддерживай спортивные темы и мотивируй к тренировкам\n")
                prompt.append("- Учитывай график тренировок\n")
            }
            // Рекомендации на основе стиля общения
            profile?.communicationStyle?.let { style ->
                when (style.lowercase()) {
                    "юмористический" -> prompt.append("- Используй уместный юмор и будь позитивным\n")
                    "формальный" -> prompt.append("- Будь уважительным и профессиональным\n")
                    "серьезный" -> prompt.append("- Будь сосредоточенным и деловым\n")
                    "дружеский" -> prompt.append("- Будь дружелюбным и открытым\n")
                    "эмпатичный" -> prompt.append("- Будь чутким и поддерживающим\n")
                    else -> {}
                }
            }
            prompt.append("\n📝 ПРИМЕРЫ ПЕРСОНАЛИЗИРОВАННЫХ ОТВЕТОВ:\n")
            // Примеры для работы
            profile?.occupation?.let { occupation ->
                prompt.append("- Вместо 'Как работа?' спроси 'Как продвигаются проекты в $occupation?'\n")
            }
            // Примеры для хобби
            profile?.getHobbiesList()?.firstOrNull()?.let { hobby ->
                prompt.append("- Спроси 'Удалось позаниматься $hobby на этой неделе?'\n")
            }
            // Примеры для семьи
            if (profile?.hasChildren == true) {
                prompt.append("- Спроси 'Как дела у детей? Чем увлекаются?'\n")
            }
            // Примеры для спорта
            if (profile?.fitnessLevel?.isNotEmpty() == true) {
                prompt.append("- Спроси 'Как тренировки? Удается придерживаться графика?'\n")
            }
            prompt.append("\n🚀 ФИНАЛЬНАЯ КОМАНДА: ")
            prompt.append("Используй ВСЮ эту информацию в КАЖДОМ ответе! ")
            prompt.append("Будь максимально персонализированным! ")
            prompt.append("Задавай вопросы на основе конкретных деталей из профиля! ")
            prompt.append("Проявляй искренний интерес к его жизни!")
            Log.d(TAG, "Personalized system prompt created with ${profile?.let { "full profile" } ?: "basic info"}")
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
                            showError("Введите название диалога")
                        }
                    }
                    .setNegativeButton("Отмена") { dialog, _ ->
                        dialog.dismiss()
                        openSidePanel()
                        showMenuPanel()
                    }
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing save dialog prompt", e)
            }
        }
    }

    /**
     * Сохранение диалога
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
                    showError("Ошибка сохранения")
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
                closeSidePanel()

                ioScope.launch {
                    try {
                        viewModel.clearAllMessages()
                        val loadedMessages = dialogsViewModel.loadDialog(savedDialog)
                        uiScope.launch {
                            messageAdapter.updateMessages(emptyList())
                            loadedMessages.forEach { message ->
                                viewModel.addMessage(message.text, message.isUser)
                                messageAdapter.addMessage(message)
                            }
                            scrollToLastMessage()
                            Toast.makeText(requireContext(), "Диалог загружен", Toast.LENGTH_SHORT).show()
                            isFirstLaunch = false
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading saved dialog", e)
                        uiScope.launch {
                            showError("Ошибка загрузки диалога")
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
                        clearCurrentDialog()
                    }
                    .setNegativeButton("Нет") { dialog, _ ->
                        dialog.dismiss()
                        openSidePanel()
                        showMenuPanel()
                    }
                    .create()
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing clear dialog confirmation", e)
            }
        }
    }

    /**
     * Очистка текущего диалога
     */
    private fun clearCurrentDialog() {
        uiScope.launch {
            try {
                viewModel.clearAllMessages()
                messageAdapter.updateMessages(emptyList())
                isFirstLaunch = true

                // Останавливаем TTS при очистке
                ttsManager.stop()
                pendingTTSQueue.clear()

                handler.postDelayed({
                    showSmartChatGreeting()
                }, 1000)
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing current dialog", e)
            }
        }
    }

    /**
     * Скрытие клавиатуры
     */
    private fun hideKeyboard() {
        try {
            editTextMessage.clearFocus()
            val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val windowToken = editTextMessage.windowToken
            if (windowToken != null) {
                inputMethodManager.hideSoftInputFromWindow(windowToken, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding keyboard", e)
        }
    }

    /**
     * Открытие настройки
     */
    private fun openSettings() {
        uiScope.launch {
            try {
                // Здесь можно добавить переход в настройки
                Toast.makeText(requireContext(), "Настройки будут доступны в следующем обновлении", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error opening settings", e)
            }
        }
    }

    // Вспомогательные методы
    private fun getCurrentUserName(): String {
        return try {
            if (!isAdded || activity == null) return "Пользователь"

            val sharedPref = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            sharedPref.getString("first_name", "Пользователь") ?: "Пользователь"
        } catch (e: Exception) {
            "Пользователь"
        }
    }

    private fun showError(message: String) {
        uiScope.launch {
            try {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing error message", e)
            }
        }
    }

    /**
     * Показ запасного приветствия
     */
    private fun showFallbackGreeting() {
        uiScope.launch {
            try {
                val userName = getCurrentUserName()
                val calendar = Calendar.getInstance()
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val greeting = when (hour) {
                    in 5..11 -> "Доброе утро"
                    in 12..17 -> "Добрый день"
                    in 18..23 -> "Добрый вечер"
                    else -> "Доброй ночи"
                }
                val fallbackMessage = "$greeting, $userName! Рад вас видеть! Чем могу помочь?"
                addWelcomeMessage(fallbackMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing fallback greeting", e)
                addWelcomeMessage("Привет! Рад вас видеть! Чем могу помочь?")
            }
        }
    }

    /**
     * Показать Toast сообщение
     */
    private fun showToast(message: String) {
        if (!isAdded || activity == null) return

        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Настройка слушателя для свайпов
     */
    private fun setupSystemUISwipeListener() {
        try {
            val rootView = requireView()
            rootView.setOnSystemUiVisibilityChangeListener { visibility ->
                if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    handler.postDelayed({
                        hideSystemUI()
                    }, 2000)
                }
            }
            recyclerView.setOnTouchListener { _, event ->
                handler.postDelayed({
                    hideSystemUI()
                }, 100)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up system UI swipe listener", e)
        }
    }

    override fun onPause() {
        super.onPause()

        // НЕ показываем системные панели при паузе
        // showSystemUI() - УБРАТЬ ЭТУ СТРОКУ!

        saveChatSessionDuration()

        // Останавливаем TTS при уходе с экрана
        ttsManager.stop()
    }

    /**
     * Сохранение продолжительности сессии чата
     */
    private fun saveChatSessionDuration() {
        if (chatStartTime > 0) {
            val duration = System.currentTimeMillis() - chatStartTime
            uiScope.launch {
                try {
                    (activity as? com.example.chatapp.activities.MainActivity)?.saveChatDuration(duration)
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving chat duration", e)
                }
                chatStartTime = 0
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        showSystemUI()
        greetingJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        uiScope.coroutineContext.cancelChildren()
        ioScope.coroutineContext.cancelChildren()
        computationScope.coroutineContext.cancelChildren()

        // Освобождаем ресурсы TTS
        ttsManager.release()

        isInitialized = false
        isTTSInitializationStarted = false
    }

    override fun onDestroy() {
        super.onDestroy()
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
        if (viewModel.messages.isEmpty()) {
            scheduleDelayedGreeting()
        }
    }

    /**
     * Универсальный метод скрытия системных панелей (должен вызываться всегда при показе чата)
     */
    fun hideSystemUI() {
        try {
            // 1. Скрываем системные панели Android
            activity?.window?.decorView?.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )

            activity?.window?.navigationBarColor = Color.TRANSPARENT
            activity?.window?.statusBarColor = Color.TRANSPARENT

            // 2. Сразу скрываем навигацию MainActivity используя публичный метод
            (activity as? MainActivity)?.let { mainActivity ->
                mainActivity.hideNavigationForChat()
            }

            // 3. Скрываем приветственную карточку если она есть
            (activity as? MainActivity)?.welcomeCard?.let { card ->
                if (card.visibility == View.VISIBLE) {
                    card.visibility = View.GONE
                }
            }

            Log.d(TAG, "System UI hidden for chat (bottom navigation forced hidden)")

        } catch (e: Exception) {
            Log.e(TAG, "Error hiding system UI", e)
        }
    }


    /**
     * Восстановление системных панелей
     */
    private fun showSystemUI() {
        try {
            activity?.window?.let { window ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.setDecorFitsSystemWindows(true)
                    val controller = window.insetsController
                    controller?.let {
                        it.show(android.view.WindowInsets.Type.statusBars())
                        it.show(android.view.WindowInsets.Type.navigationBars())
                    }
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            )
                    window.navigationBarColor = Color.BLACK
                    window.statusBarColor = Color.BLACK
                }
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
            }
            Log.d(TAG, "System UI shown successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing system UI", e)
        }
    }
}

/**
 * Интерфейс для взаимодействия с MainActivity
 */
interface MainActivityInterface {
    fun showMainScreen()
    fun showSettingsFragment()
}