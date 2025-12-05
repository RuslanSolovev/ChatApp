package com.example.chatapp.activities

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.chatapp.BuildConfig
import com.example.chatapp.R
import com.example.chatapp.api.AuthRetrofitInstance
import com.example.chatapp.api.GigaChatRequest
import com.example.chatapp.api.Message
import com.example.chatapp.api.RetrofitInstance
import com.example.chatapp.budilnik.AlarmActivity
import com.example.chatapp.databinding.ActivityMainBinding
import com.example.chatapp.fragments.*
import com.example.chatapp.gruha.TimerActivity
import com.example.chatapp.igra_bloki.BlockGameActivity
import com.example.chatapp.location.LocationServiceManager
import com.example.chatapp.location.LocationUpdateService
import com.example.chatapp.location.LocationPagerFragment
import com.example.chatapp.models.User
import com.example.chatapp.mozgi.ui.CategoriesActivity
import com.example.chatapp.muzika.ui.MusicMainActivity
import com.example.chatapp.novosti.CreateNewsFragment
import com.example.chatapp.novosti.FullScreenImageFragment
import com.example.chatapp.novosti.NewsItem
import com.example.chatapp.privetstvie_giga.*
import com.example.chatapp.step.StepCounterApp
import com.example.chatapp.step.StepCounterFragment
import com.example.chatapp.step.StepCounterService
import com.example.chatapp.step.StepCounterServiceWorker
import com.example.chatapp.utils.PhilosophyQuoteWorker
import com.example.chatapp.utils.TTSManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var bottomNav: BottomNavigationView
    private var isFirstLaunch = true
    private var currentPermissionIndex = 0
    private var isAppInitialized = false // КРИТИЧЕСКИ ВАЖНО: флаг инициализации

    // Переменные для приветственного виджета
    lateinit var welcomeCard: CardView
    private lateinit var tvWelcomeTitle: TextView
    private lateinit var tvWelcomeQuestion: TextView
    private lateinit var tvWelcomeContext: TextView
    private lateinit var btnStartChat: Button
    private lateinit var btnMaybeLater: Button
    private lateinit var btnCloseWelcome: ImageButton

    // Новые переменные для анимированного приветствия
    private lateinit var progressWelcome: LinearProgressIndicator
    private lateinit var welcomeContent: LinearLayout
    private lateinit var ivWelcomeAvatar: ImageView

    // Атомарные флаги для предотвращения многократного запуска
    private val isLocationServiceStarting = AtomicBoolean(false)
    private val isStepServiceStarting = AtomicBoolean(false)

    // Обработчик для отложенных задач
    private val handler = Handler(Looper.getMainLooper())

    // Кэш для фрагментов
    private val fragmentCache = mutableMapOf<String, Fragment>()
    private var currentFragmentTag: String? = null

    // Профиль пользователя для персонализации
    private var userProfile: UserProfile? = null

    // Анализаторы контекста
    private var contextAnalyzer: SmartContextAnalyzer? = null
    private var greetingGenerator: SmartQuestionGenerator? = null

    // Флаги для отслеживания состояния приветствия
    private var isWelcomeSequenceRunning = false
    private var welcomeSequenceJob: Job? = null

    // AI анализ переменные
    private var aiAnalysisJob: Job? = null
    private var cachedAIContinuation: String? = null
    private var lastAIAnalysisTime: Long = 0

    // Диспетчеры с ограниченным параллелизмом
    private val initDispatcher = Dispatchers.Default.limitedParallelism(2)
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val uiDispatcher = Dispatchers.Main.immediate

    // TTS (Text-to-Speech) переменные
    private lateinit var ttsManager: TTSManager
    private var isTTSInitialized = false
    private var hasGreetingBeenSpoken = false
    private var hasAIContinuationBeenSpoken = false
    private var ttsInitializationAttempts = 0
    private val MAX_TTS_INIT_ATTEMPTS = 3

    // Переменные для отслеживания состояния приложения
    private var isActivityResumed = false
    private var wasActivityPaused = false
    private var welcomeRetryCount = 0
    private val MAX_WELCOME_RETRIES = 3

    // --- БЕЗОПАСНЫЙ СПИСОК РАЗРЕШЕНИЙ ---
    private val basicPermissions = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    private val additionalPermissions = mutableListOf<String>().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // --- ActivityResultLauncher для ОСНОВНЫХ разрешений ---
    private val basicPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        lifecycleScope.launch(uiDispatcher) {
            try {
                Log.d(TAG, "Basic permissions result: $permissions")
                currentPermissionIndex = 0
                handler.postDelayed({
                    requestNextAdditionalPermission()
                }, PERMISSION_REQUEST_DELAY)
            } catch (e: Exception) {
                Log.e(TAG, "Error in basic permission callback", e)
                proceedWithMainInitialization()
            }
        }
    }

    // --- ActivityResultLauncher для ДОПОЛНИТЕЛЬНЫХ разрешений ---
    private val additionalPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        lifecycleScope.launch(uiDispatcher) {
            try {
                val currentPermission = additionalPermissions.getOrNull(currentPermissionIndex)
                Log.d(TAG, "Additional permission $currentPermission granted: $isGranted")
                currentPermissionIndex++
                handler.postDelayed({
                    requestNextAdditionalPermission()
                }, PERMISSION_REQUEST_DELAY)
            } catch (e: Exception) {
                Log.e(TAG, "Error in additional permission callback", e)
                proceedWithMainInitialization()
            }
        }
    }

    companion object {

        private const val AI_ANALYSIS_CACHE_TIME = 300000L // 5 минут

        private const val TAG = "MainActivity"
        private const val HOME_FRAGMENT_TAG = "home_fragment"
        private const val CHAT_FRAGMENT_TAG = "chat_fragment"
        private const val STEPS_FRAGMENT_TAG = "steps_fragment"
        private const val MAPS_FRAGMENT_TAG = "maps_fragment"
        private const val GAMES_FRAGMENT_TAG = "games_fragment"

        private const val LOTTERY_FRAGMENT_TAG = "lottery_fragment"

        // Имена для WorkManager
        private const val STEP_SERVICE_WORK_NAME = "StepCounterServicePeriodicWork"
        private const val PHILOSOPHY_QUOTES_WORK_NAME = "hourly_philosophy_quotes"

        // Интервалы для WorkManager
        private const val STEP_SERVICE_INTERVAL_MINUTES = 30L

        // Таймауты
        private const val TRACKING_CHECK_TIMEOUT = 5000L
        private const val SERVICE_INIT_TIMEOUT = 10000L
        private const val PERMISSION_REQUEST_DELAY = 300L

        // Задержки для приветственной последовательности
        private const val WELCOME_STAGE_1_DELAY = 0L
        private const val WELCOME_STAGE_2_DELAY = 1500L // Увеличено для надежности
        private const val WELCOME_STAGE_3_DELAY = 3000L // Увеличено для надежности

        // TTS задержки
        private const val TTS_INIT_DELAY = 1500L  // Увеличено для стабильности
        private const val TTS_GREETING_DELAY = 500L
        private const val TTS_CONTINUATION_DELAY = 2500L // Увеличено для правильного порядка
        private const val TTS_PAUSE_BETWEEN_PHRASES = 800L

        // Повторные попытки
        private const val WELCOME_RETRY_DELAY = 2000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            // КРИТИЧЕСКИ ВАЖНО: сначала показываем UI, потом всё остальное
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // 1. Инициализируем TTS Manager ВМЕСТО TextToSpeech (синхронно для надежности)
            initTTSManagerSync()

            // 2. Включаем кнопки и навигацию СРАЗУ
            enableAllButtonsImmediately()

            // 3. Показываем базовый фрагмент
            loadInitialFragmentFast()

            // 4. Быстрая инициализация UI
            setupCriticalUI()

            // 5. Асинхронная проверка авторизации (в фоне)
            lifecycleScope.launch(uiDispatcher) {
                checkAuthAsync()
            }

            // 6. Настройка прозрачных панелей (в фоне)
            lifecycleScope.launch(uiDispatcher) {
                makeSystemBarsTransparent()
                handleSystemBarsInsets()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate", e)
            // Даже при ошибке показываем базовый UI
            showEmergencyUI()
        }
    }

    /**
     * Синхронная инициализация TTS Manager для надежности
     */
    private fun initTTSManagerSync() {
        if (ttsInitializationAttempts >= MAX_TTS_INIT_ATTEMPTS) {
            Log.w(TAG, "Max TTS initialization attempts reached, skipping")
            isTTSInitialized = false
            return
        }

        ttsInitializationAttempts++

        try {
            Log.d(TAG, "Initializing TTS Manager (attempt $ttsInitializationAttempts)")

            ttsManager = TTSManager(this) { initialized ->
                isTTSInitialized = initialized

                if (initialized) {
                    Log.d(TAG, "TTS Manager initialized successfully in MainActivity")

                    // Сбрасываем счетчик попыток при успехе
                    ttsInitializationAttempts = 0

                    // Если активность активна - запускаем приветствие
                    if (isActivityResumed && ::tvWelcomeTitle.isInitialized) {
                        handler.postDelayed({
                            speakInitialGreeting()
                        }, TTS_INIT_DELAY)
                    }
                } else {
                    Log.e(TAG, "TTS Manager initialization failed in MainActivity")
                    isTTSInitialized = false

                    // Пробуем еще раз если не превышен лимит
                    if (ttsInitializationAttempts < MAX_TTS_INIT_ATTEMPTS) {
                        handler.postDelayed({
                            initTTSManagerSync()
                        }, 2000L)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TTS Manager", e)
            isTTSInitialized = false

            if (ttsInitializationAttempts < MAX_TTS_INIT_ATTEMPTS) {
                handler.postDelayed({
                    initTTSManagerSync()
                }, 2000L)
            }
        }
    }

    /**
     * Озвучивает начальное приветствие с улучшенной логикой
     */
    private fun speakInitialGreeting() {
        if (!isTTSInitialized || hasGreetingBeenSpoken || !isActivityResumed) {
            Log.w(TAG, "Skipping initial greeting - TTS: $isTTSInitialized, Spoken: $hasGreetingBeenSpoken, Resumed: $isActivityResumed")
            return
        }

        try {
            val userName = getUserName()
            val greeting = getTimeBasedGreeting()
            val greetingText = "$greeting, $userName!"

            Log.d(TAG, "Speaking initial greeting: $greetingText")

            // Сразу помечаем как сказанное, чтобы не повторять
            hasGreetingBeenSpoken = true

            // Используем TTS Manager для озвучки
            ttsManager.speak(greetingText, TTSManager.TYPE_GREETING, true)

        } catch (e: Exception) {
            Log.e(TAG, "Error speaking initial greeting", e)
            // Сбрасываем флаг при ошибке, чтобы можно было повторить
            hasGreetingBeenSpoken = false
        }
    }

    /**
     * Озвучивает базовое приветствие из приветственной карточки
     */
    private fun speakGreeting(text: String) {
        if (!isTTSInitialized || !isActivityResumed) {
            Log.w(TAG, "TTS not initialized or activity not resumed, skipping speech: $text")
            return
        }

        try {
            // Используем TYPE_GREETING для специальной обработки приветствий
            ttsManager.speak(text, TTSManager.TYPE_GREETING, true)

            Log.d(TAG, "Greeting spoken: ${text.take(50)}...")

        } catch (e: Exception) {
            Log.e(TAG, "Error speaking greeting", e)
        }
    }

    /**
     * Озвучивает AI-продолжение диалога
     */
    private fun speakAIContinuation(continuation: String) {
        if (!isTTSInitialized || hasAIContinuationBeenSpoken || !isActivityResumed) {
            Log.w(TAG, "Skipping continuation - TTS: $isTTSInitialized, Spoken: $hasAIContinuationBeenSpoken, Resumed: $isActivityResumed")
            return
        }

        try {
            // Используем TYPE_CHAT_BOT для естественного звучания
            ttsManager.speak(continuation, TTSManager.TYPE_CHAT_BOT, true)
            hasAIContinuationBeenSpoken = true

            Log.d(TAG, "Continuation spoken: ${continuation.take(50)}...")

        } catch (e: Exception) {
            Log.e(TAG, "Error speaking continuation", e)
        }
    }

    /**
     * Останавливает TTS
     */
    private fun stopTTS() {
        try {
            ttsManager.stop()
            Log.d(TAG, "TTS stopped in MainActivity")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
    }

    /**
     * Освобождает ресурсы TTS
     */
    private fun releaseTTS() {
        try {
            ttsManager.release()
            Log.d(TAG, "TTS released in MainActivity")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing TTS", e)
        }
    }

    /**
     * Включает все кнопки и навигацию МГНОВЕННО
     */
    private fun enableAllButtonsImmediately() {
        try {
            // Включаем все UI элементы сразу
            binding.bottomNavigation.isEnabled = true
            binding.btnMusic.isEnabled = true
            binding.btnQuestionnaire.isEnabled = true
            binding.btnMenu.isEnabled = true
            binding.ivUserAvatar.isEnabled = true
            binding.tvUserName.isEnabled = true

            // Настройка экстренной навигации (работает без сети)
            setupEmergencyNavigation()

            // Инициализация меню
            binding.btnMenu.setOnClickListener { view ->
                showPopupMenu(view)
            }

            Log.d(TAG, "All buttons enabled immediately")

        } catch (e: Exception) {
            Log.e(TAG, "Error enabling buttons immediately", e)
        }
    }

    /**
     * Экстренная навигация (работает без сети и авторизации)
     */
    private fun setupEmergencyNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    safeSwitchToFragment(HOME_FRAGMENT_TAG) { HomeFragment() }
                    true
                }
                R.id.nav_gigachat -> {
                    // Для чата нужен интернет
                    if (!isNetworkAvailable()) {
                        Toast.makeText(this, "Для чата требуется интернет", Toast.LENGTH_SHORT).show()
                        return@setOnItemSelectedListener false
                    }
                    safeSwitchToFragment(CHAT_FRAGMENT_TAG) { ChatWithGigaFragment() }
                    true
                }
                R.id.nav_steps -> {
                    safeSwitchToFragment(STEPS_FRAGMENT_TAG) { StepCounterFragment() }
                    true
                }
                R.id.nav_maps -> {
                    safeSwitchToFragment(MAPS_FRAGMENT_TAG) { LocationPagerFragment() }
                    true
                }
                R.id.nav_games -> {
                    safeSwitchToFragment(GAMES_FRAGMENT_TAG) { GamesFragment() }
                    true
                }
                else -> false
            }
        }

        // Настройка кнопок toolbar
        binding.btnMusic.setOnClickListener {
            try {
                startActivity(Intent(this, MusicMainActivity::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Error starting MusicActivity", e)
                Toast.makeText(this, "Ошибка открытия музыки", Toast.LENGTH_SHORT).show()
            }
        }

        binding.ivUserAvatar.setOnClickListener {
            try {
                startActivity(Intent(this, ProfileActivity::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Error starting ProfileActivity", e)
                Toast.makeText(this, "Ошибка открытия профиля", Toast.LENGTH_SHORT).show()
            }
        }

        binding.tvUserName.setOnClickListener {
            try {
                startActivity(Intent(this, ProfileActivity::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Error starting ProfileActivity", e)
                Toast.makeText(this, "Ошибка открытия профиля", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Быстрая загрузка начального фрагмента
     */
    private fun loadInitialFragmentFast() {
        try {
            supportFragmentManager.commitNow {
                add(R.id.fragment_container, HomeFragment(), HOME_FRAGMENT_TAG)
            }
            binding.bottomNavigation.selectedItemId = R.id.nav_home
            currentFragmentTag = HOME_FRAGMENT_TAG
            Log.d(TAG, "Initial fragment loaded fast")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading initial fragment fast", e)
        }
    }

    /**
     * Асинхронная проверка авторизации
     */
    private suspend fun checkAuthAsync() = withContext(ioDispatcher) {
        try {
            // Быстрая проверка с таймаутом
            val currentUser = withTimeoutOrNull(3000L) {
                try {
                    Firebase.auth.currentUser
                } catch (e: Exception) {
                    Log.w(TAG, "Firebase auth check failed, using cache", e)
                    null
                }
            }

            if (currentUser == null) {
                // Проверяем кэшированную авторизацию
                val cachedAuth = getSharedPreferences("auth_cache", MODE_PRIVATE)
                    .getBoolean("is_authenticated", false)

                if (!cachedAuth) {
                    withContext(uiDispatcher) {
                        redirectToAuth()
                    }
                } else {
                    // Работаем в оффлайн режиме
                    withContext(uiDispatcher) {
                        startOfflineMode()
                    }
                }
            } else {
                // Пользователь авторизован, продолжаем инициализацию
                withContext(uiDispatcher) {
                    auth = Firebase.auth
                    initializeAppAsync()
                }
            }

        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Auth check timeout - возможно нет интернета")
            withContext(uiDispatcher) {
                startOfflineMode()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking auth", e)
            withContext(uiDispatcher) {
                startOfflineMode()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Если активный фрагмент - чат, скрываем системные панели
            if (currentFragmentTag == CHAT_FRAGMENT_TAG) {
                hideSystemUIForChat()
            }
        }
    }

    /**
     * Запуск оффлайн режима
     */
    private fun startOfflineMode() {
        try {
            // Показываем предупреждение
            Toast.makeText(this, "Работаем в оффлайн режиме", Toast.LENGTH_SHORT).show()

            // Загружаем кэшированные данные
            loadCachedUserData()

            // Показываем базовое приветствие
            showBasicWelcomeMessage()

            // Помечаем приложение как инициализированное
            isAppInitialized = true

            Log.d(TAG, "Offline mode started")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting offline mode", e)
        }
    }

    /**
     * Загружает кэшированные данные пользователя
     */
    private fun loadCachedUserData() {
        lifecycleScope.launch(ioDispatcher) {
            try {
                val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
                val firstName = sharedPref.getString("first_name", null)
                val userName = sharedPref.getString("user_name", null)

                withContext(uiDispatcher) {
                    if (!firstName.isNullOrEmpty()) {
                        binding.tvUserName.text = firstName
                    } else if (!userName.isNullOrEmpty()) {
                        binding.tvUserName.text = userName
                    } else {
                        binding.tvUserName.text = "Пользователь"
                    }

                    // Загружаем кэшированный аватар
                    loadCachedAvatar()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading cached user data", e)
            }
        }
    }

    /**
     * Загружает кэшированный аватар
     */
    private fun loadCachedAvatar() {
        try {
            // Пытаемся загрузить из кэша Glide
            binding.ivUserAvatar.setImageResource(R.drawable.ic_default_profile)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cached avatar", e)
        }
    }

    /**
     * Показывает экстренный UI при критической ошибке
     */
    private fun showEmergencyUI() {
        try {
            // Минимальный рабочий UI
            binding.toolbar.visibility = View.VISIBLE
            binding.bottomNavigation.visibility = View.VISIBLE
            binding.fragmentContainer.visibility = View.VISIBLE

            // Простое сообщение
            Toast.makeText(this, "Приложение запущено в базовом режиме", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Log.e(TAG, "Error showing emergency UI", e)
        }
    }

    /**
     * Быстрая инициализация только критически важных элементов
     */
    private fun setupCriticalUI() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)

            // Инициализация welcome widget
            initWelcomeWidget()

            // Настройка базовых слушателей
            setupBasicClickListeners()

            Log.d(TAG, "Critical UI setup completed")

        } catch (e: Exception) {
            Log.e(TAG, "Error in critical UI setup", e)
        }
    }

    /**
     * Инициализация welcome widget
     */
    private fun initWelcomeWidget() {
        try {
            welcomeCard = binding.welcomeCard
            tvWelcomeTitle = binding.tvWelcomeTitle
            tvWelcomeQuestion = binding.tvWelcomeQuestion
            tvWelcomeContext = binding.tvWelcomeContext
            btnStartChat = binding.btnStartChat
            btnMaybeLater = binding.btnMaybeLater
            btnCloseWelcome = binding.btnCloseWelcome

            // Новые элементы для анимированного приветствия
            progressWelcome = binding.progressWelcome
            welcomeContent = binding.welcomeContent
            ivWelcomeAvatar = binding.ivWelcomeAvatar

            // Настройка внешнего вида
            setupWelcomeCardAppearance()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing welcome widget", e)
        }
    }

    /**
     * Настройка внешнего вида приветственной карточки
     */
    private fun setupWelcomeCardAppearance() {
        try {
            // Используем стандартный аватар для скорости
            ivWelcomeAvatar.setImageResource(R.drawable.ic_default_profile)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up welcome card appearance", e)
        }
    }

    /**
     * Быстрая настройка слушателей
     */
    private fun setupBasicClickListeners() {
        btnStartChat.setOnClickListener {
            lifecycleScope.launch(uiDispatcher) {
                animateButtonClick(btnStartChat)
                switchToChatAsync()
            }
        }

        btnMaybeLater.setOnClickListener {
            lifecycleScope.launch(uiDispatcher) {
                animateButtonClick(btnMaybeLater)
                hideWelcomeMessage()
            }
        }

        btnCloseWelcome.setOnClickListener {
            lifecycleScope.launch(uiDispatcher) {
                animateButtonClick(btnCloseWelcome)
                hideWelcomeMessage()
            }
        }
    }

    /**
     * Анимация нажатия кнопки
     */
    private suspend fun animateButtonClick(view: View) = withContext(uiDispatcher) {
        try {
            val scaleDown = ObjectAnimator.ofPropertyValuesHolder(
                view,
                PropertyValuesHolder.ofFloat("scaleX", 0.95f),
                PropertyValuesHolder.ofFloat("scaleY", 0.95f)
            ).apply {
                duration = 100
            }

            val scaleUp = ObjectAnimator.ofPropertyValuesHolder(
                view,
                PropertyValuesHolder.ofFloat("scaleX", 1f),
                PropertyValuesHolder.ofFloat("scaleY", 1f)
            ).apply {
                duration = 100
            }

            scaleDown.start()
            scaleDown.doOnEnd {
                scaleUp.start()
            }

            // Ждем завершения анимации
            delay(200)
        } catch (e: Exception) {
            Log.e(TAG, "Error animating button click", e)
        }
    }

    /**
     * Анимация нажатия кнопки (синхронная версия)
     */
    private fun animateButtonClick(view: View, action: () -> Unit) {
        val scaleDown = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat("scaleX", 0.95f),
            PropertyValuesHolder.ofFloat("scaleY", 0.95f)
        ).apply {
            duration = 100
        }

        val scaleUp = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat("scaleX", 1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f)
        ).apply {
            duration = 100
        }

        scaleDown.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                scaleUp.start()
                action()
            }
        })

        scaleDown.start()
    }

    /**
     * Проверка доступности сети
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

                return when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                    else -> false
                }
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo?.isConnected == true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * ОПТИМИЗИРОВАННАЯ асинхронная инициализация приложения
     */
    private suspend fun initializeAppAsync() = withContext(initDispatcher) {
        val startTime = System.currentTimeMillis()

        try {
            Log.d(TAG, "=== OPTIMIZED ASYNC APP INITIALIZATION ===")

            // Показываем базовое приветствие мгновенно в главном потоке
            withContext(uiDispatcher) {
                showLoadingProgress()
                showInstantBasicGreeting() // 1-я часть приветствия
            }

            // Проверяем сеть перед сетевыми операциями
            val networkAvailable = isNetworkAvailable()

            // Параллельная загрузка данных (сетевые только если есть интернет)
            val userDataJob = async {
                if (networkAvailable) {
                    withTimeoutOrNull(5000L) { loadCurrentUserDataAsync() } ?: false
                } else {
                    loadCachedUserDataAsync()
                    false
                }
            }

            val profileJob = async {
                if (networkAvailable) {
                    withTimeoutOrNull(5000L) { loadUserProfileAsync() } ?: null
                } else {
                    loadCachedProfile()
                }
            }

            // Инициализируем генераторы в фоне
            val analyzer = withContext(initDispatcher) {
                SmartContextAnalyzer(this@MainActivity.applicationContext)
            }

            val userProfile = profileJob.await()
            val userDataLoaded = userDataJob.await()

            val generator = SmartQuestionGenerator(
                this@MainActivity.applicationContext,
                userProfile
            )

            // Переходим в главный поток для UI обновлений
            withContext(uiDispatcher) {
                this@MainActivity.userProfile = userProfile
                contextAnalyzer = analyzer
                greetingGenerator = generator

                hideLoadingProgress()
                setupToolbar()

                // Запускаем AI-улучшенное поэтапное приветствие
                startStagedWelcomeSequence()

                // Проверка разрешений после загрузки UI
                if (isFirstLaunch) {
                    handler.postDelayed({
                        checkAndRequestMainPermissions()
                    }, 1000)
                } else {
                    proceedWithMainInitialization()
                }
            }

            // Фоновые задачи выполняем параллельно после UI обновления
            if (networkAvailable) {
                try {
                    withTimeout(10000L) {
                        val backgroundTasks = listOf(
                            async { initializeBackgroundServices() },
                            async { loadAdditionalData() }
                        )
                        backgroundTasks.awaitAll()
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "Background tasks timeout")
                }
            }

            // Отладочная информация о пользователе после загрузки
            if (BuildConfig.DEBUG) {
                handler.postDelayed({
                    debugUserDataExtended()
                }, 3000)
            }

            logPerformance("App initialization", startTime)

        } catch (e: Exception) {
            Log.e(TAG, "Error in optimized async initialization", e)
            withContext(uiDispatcher) {
                hideLoadingProgress()
                showBasicWelcomeMessage()
                isAppInitialized = true
            }
        }
    }

    /**
     * Загружает кэшированные данные асинхронно
     */
    private suspend fun loadCachedUserDataAsync() = withContext(ioDispatcher) {
        return@withContext try {
            val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
            val fullName = sharedPref.getString("user_name", null)
            val firstName = sharedPref.getString("first_name", null)

            withContext(uiDispatcher) {
                if (!firstName.isNullOrEmpty()) {
                    binding.tvUserName.text = firstName
                } else if (!fullName.isNullOrEmpty()) {
                    binding.tvUserName.text = fullName
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cached user data async", e)
            false
        }
    }

    /**
     * Загружает кэшированный профиль
     */
    private suspend fun loadCachedProfile(): UserProfile? = withContext(ioDispatcher) {
        return@withContext try {
            val sharedPref = getSharedPreferences("user_cache", MODE_PRIVATE)
            val json = sharedPref.getString("cached_profile", null)
            json?.let {
                Gson().fromJson(it, UserProfile::class.java)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cached profile", e)
            null
        }
    }

    /**
     * Инициализация фоновых сервисов
     */
    private suspend fun initializeBackgroundServices() = withContext(ioDispatcher) {
        try {
            restartServicesAfterCrash()
            cleanupOldChatData()
            checkTrackingStatusAsync()
            checkAndShowQuestionnairePrompt()
        } catch (e: Exception) {
            Log.e(TAG, "Error in background services initialization", e)
        }
    }

    /**
     * Загрузка дополнительных данных
     */
    private suspend fun loadAdditionalData() = withContext(ioDispatcher) {
        try {
            // Дополнительные данные которые не критичны для запуска
        } catch (e: Exception) {
            Log.e(TAG, "Error loading additional data", e)
        }
    }

    /**
     * Показывает мгновенное базовое приветствие (1-я часть) - СИНХРОННАЯ версия
     */
    private fun showInstantBasicGreeting() {
        try {
            val userName = getUserName()
            val greeting = getTimeBasedGreeting()
            val greetingText = "$greeting, $userName!"

            // Сразу устанавливаем текст приветствия
            tvWelcomeTitle.text = greetingText

            // Сбрасываем состояния
            resetWelcomeCardState()

            // Запускаем анимацию появления
            startWelcomeCardEntranceAnimation()

            Log.d(TAG, "Instant basic greeting shown")

            // ОЗВУЧИВАЕМ базовое приветствие СРАЗУ после показа
            handler.postDelayed({
                if (isTTSInitialized && isActivityResumed) {
                    speakGreeting(greetingText)
                }
            }, TTS_GREETING_DELAY)

        } catch (e: Exception) {
            Log.e(TAG, "Error showing instant greeting", e)
        }
    }

    /**
     * Переключается на фрагмент лотереи
     */
    private fun switchToLotteryFragment() {
        lifecycleScope.launch(uiDispatcher) {
            try {
                if (::welcomeCard.isInitialized && welcomeCard.visibility == View.VISIBLE) {
                    hideWelcomeMessage()
                }

                safeSwitchToFragment(LOTTERY_FRAGMENT_TAG) {
                    com.example.chatapp.loterey.SimpleLotteryFragment()
                }

                binding.bottomNavigation.selectedItemId = -1

                Log.d(TAG, "Switched to lottery fragment")

            } catch (e: Exception) {
                Log.e(TAG, "Error switching to lottery fragment", e)
                Toast.makeText(this@MainActivity, "Ошибка открытия лотереи", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Сброс состояния карточки
     */
    private fun resetWelcomeCardState() {
        welcomeCard.visibility = View.VISIBLE
        welcomeCard.alpha = 0f
        welcomeCard.scaleX = 0.9f
        welcomeCard.scaleY = 0.9f
        welcomeCard.translationY = -50f

        progressWelcome.visibility = View.VISIBLE
        progressWelcome.progress = 0

        welcomeContent.visibility = View.GONE
        welcomeContent.alpha = 0f
    }

    /**
     * Анимация появления карточки
     */
    private fun startWelcomeCardEntranceAnimation() {
        val entranceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = OvershootInterpolator(0.8f)

            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                welcomeCard.alpha = progress
                welcomeCard.scaleX = 0.9f + 0.1f * progress
                welcomeCard.scaleY = 0.9f + 0.1f * progress
                welcomeCard.translationY = -50f * (1 - progress)
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    startContentLoadingSequence()
                }
            })
        }

        entranceAnimator.start()
    }

    /**
     * Последовательность загрузки контента
     */
    private fun startContentLoadingSequence() {
        // Анимация прогресс-бара
        val progressAnimator = ValueAnimator.ofInt(0, 100).apply {
            duration = 1500
            interpolator = AccelerateDecelerateInterpolator()

            addUpdateListener { animation ->
                progressWelcome.progress = animation.animatedValue as Int
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    showWelcomeContent()
                }
            })
        }

        progressAnimator.start()
    }

    /**
     * Показ контента с анимацией
     */
    private fun showWelcomeContent() {
        welcomeContent.visibility = View.VISIBLE

        val contentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400
            interpolator = DecelerateInterpolator()

            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                welcomeContent.alpha = progress
                welcomeContent.translationY = 20f * (1 - progress)
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    progressWelcome.visibility = View.GONE
                }
            })
        }

        contentAnimator.start()
    }

    /**
     * Анимация изменения текста
     */
    private fun animateTextChange(textView: TextView, newText: String) {
        val fadeOut = ObjectAnimator.ofFloat(textView, "alpha", 1f, 0f).apply {
            duration = 150
        }

        val fadeIn = ObjectAnimator.ofFloat(textView, "alpha", 0f, 1f).apply {
            duration = 150
        }

        fadeOut.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                textView.text = newText
                fadeIn.start()
            }
        })

        fadeOut.start()
    }

    /**
     * Асинхронное сохранение сгенерированной фразы для чата
     */
    private fun saveCompleteWelcomePhraseForChatAsync(continuationPhrase: String? = null) {
        lifecycleScope.launch(initDispatcher) {
            try {
                // Берем либо переданную фразу, либо текущую из TextView
                val phraseToSave = continuationPhrase ?: tvWelcomeContext.text.toString()

                // Проверяем, что это не начальная заглушка
                if (phraseToSave != "Анализирую наши предыдущие обсуждения..." &&
                    phraseToSave != "Формирую вопрос на основе ваших интересов...") {

                    // Очищаем предыдущие сохраненные фразы
                    val sharedPref = getSharedPreferences("chat_prefs", MODE_PRIVATE)
                    sharedPref.edit()
                        .remove("complete_welcome_phrase")
                        .remove("welcome_phrase")
                        .putString("continuation_phrase", phraseToSave)
                        .apply()

                    Log.d(TAG, "Generated continuation phrase saved for chat: $phraseToSave")
                } else {
                    Log.w(TAG, "Skipping save - placeholder text detected: $phraseToSave")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving continuation phrase", e)
            }
        }
    }

    /**
     * Генерирует fallback-фразу на основе времени суток
     */
    private fun generateTimeBasedGreetingFallback(): String {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        return when (hour) {
            in 5..11 -> "Доброе утро! Чем могу помочь?"
            in 12..17 -> "Добрый день! Как ваши дела?"
            in 18..23 -> "Добрый вечер! Что хотите обсудить?"
            else -> "Привет! Чем могу быть полезен?"
        }
    }

    /**
     * AI анализ истории чата для генерации контекстного продолжения
     */
    private suspend fun analyzeChatHistoryWithAI(): String? = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()

        return@withContext try {
            Log.d(TAG, "Starting AI analysis of chat history...")

            // ПРОВЕРКА 1: есть ли сеть?
            if (!isNetworkAvailable()) {
                Log.d(TAG, "Network unavailable, skipping AI analysis")
                return@withContext null
            }

            // Загружаем последние сообщения
            val recentMessages = loadRecentChatHistoryForAI()
            if (recentMessages.isEmpty()) {
                Log.d(TAG, "No recent messages for AI analysis")
                return@withContext null
            }

            Log.d(TAG, "Found ${recentMessages.size} messages for AI analysis")

            // Получаем токен для API с таймаутом
            val token = withTimeoutOrNull(2000L) {
                getAuthTokenForAnalysis()
            }

            if (token.isNullOrEmpty()) {
                Log.w(TAG, "No auth token for AI analysis")
                return@withContext null
            }

            // Формируем промпт для анализа
            val analysisPrompt = buildAnalysisPrompt(recentMessages)

            // Отправляем запрос к API с таймаутом (3 секунды)
            val continuation = withTimeoutOrNull(3000L) {
                sendAnalysisRequest(token, analysisPrompt)
            }

            logAIAnalysisPerformance(startTime, continuation != null, recentMessages.size)
            Log.d(TAG, "AI analysis completed: ${continuation?.take(50)}...")
            continuation

        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "AI analysis timeout after ${System.currentTimeMillis() - startTime}ms")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error in AI chat history analysis", e)
            null
        }
    }

    /**
     * Загружает историю чата для AI анализа (последние 6 сообщений)
     */
    private suspend fun loadRecentChatHistoryForAI(): List<String> = withContext(ioDispatcher) {
        return@withContext try {
            val sharedPref = getSharedPreferences("chat_history", MODE_PRIVATE)
            val historyJson = sharedPref.getString("recent_messages", "[]")
            val messages = Gson().fromJson(historyJson, Array<String>::class.java).toList()

            // Берем последние 6 сообщений и фильтруем короткие/незначимые
            messages.takeLast(6).filter { message ->
                message.length > 5 &&
                        !message.contains("привет", ignoreCase = true) &&
                        !message.contains("пока", ignoreCase = true) &&
                        !message.contains("спасибо", ignoreCase = true) &&
                        !message.contains("ок", ignoreCase = true) &&
                        !message.contains("хорошо", ignoreCase = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading chat history for AI", e)
            emptyList()
        }
    }

    /**
     * Строит детальный промпт для анализа истории чата
     */
    private fun buildAnalysisPrompt(messages: List<String>): String {
        val conversationHistory = formatConversationHistory(messages)
        val lastUserMessage = findLastUserMessage(messages)
        val mainTopics = extractMainTopics(messages)

        return """
        # ЗАДАЧА: Сгенерируй ЕСТЕСТВЕННОЕ продолжение диалога
        
        ## КОНТЕКСТ:
        Ты - персональный ассистент, который хорошо знает пользователя. 
        Ты анализируешь историю общения, чтобы предложить релевантное продолжение.
        
        ## ИСТОРИЯ ДИАЛОГА (последние сообщения):
        $conversationHistory
        
        ## АНАЛИЗ КОНТЕКСТА:
        - Основные темы: ${mainTopics.take(3).joinToString(", ")}
        - Последнее сообщение пользователя: "${lastUserMessage?.take(100)}"
        - Количество сообщений в истории: ${messages.size}
        
        ## ТРЕБОВАНИЯ К ОТВЕТУ:
        1. **Формат**: Только ОДНА законченная фраза
        2. **Стиль**: Дружелюбный, естественный, вовлеченный
        3. **Фокус**: Продолжай существующие темы, не вводи новые
        4. **Длина**: 10-25 слов, читабельно и естественно
        5. **Тон**: Поддерживающий, проявляющий интерес
        
        ## СТРАТЕГИИ ПРОДОЛЖЕНИЯ:
        ${getContinuationStrategies(mainTopics, lastUserMessage)}
        
        ## ПРИМЕРЫ ХОРОШИХ ПРОДОЛЖЕНИЙ:
        - "Как продвигается тот проект, о котором ты рассказывал? Есть новости?"
        - "Ты упоминал, что хотел попробовать новое хобби - удалось начать?"
        - "Насчет твоей идеи по работе - получилось ее обсудить с коллегами?"
        - "Как самочувствие после тех занятий спортом? Удалось восстановиться?"
        - "Ты рассказывал о планах на выходные - как они прошли?"
        
        ## ЧТО НЕ ДЕЛАТЬ:
        ❌ Не предлагай новые случайные темы
        ❌ Не задавай общих вопросов ("Как дела?")
        ❌ Не используй маркеры списков или нумерацию
        ❌ Не пиши слишком длинные или сложные фразы
        ❌ Не повторяй дословно предыдущие сообщения
        
        ## ФИНАЛЬНАЯ ФРАЗА ПРОДОЛЖЕНИЯ:
    """.trimIndent()
    }

    /**
     * Форматирует историю диалога для лучшей читаемости
     */
    private fun formatConversationHistory(messages: List<String>): String {
        return messages.mapIndexed { index, message ->
            val speaker = if (index % 2 == 0) "👤 ПОЛЬЗОВАТЕЛЬ" else "🤖 АССИСТЕНТ"
            val shortenedMessage = if (message.length > 150) {
                message.take(147) + "..."
            } else {
                message
            }
            "$speaker: $shortenedMessage"
        }.joinToString("\n")
    }

    /**
     * Находит последнее сообщение пользователя
     */
    private fun findLastUserMessage(messages: List<String>): String? {
        return messages.withIndex()
            .filter { it.index % 2 == 0 } // Сообщения пользователя (четные индексы)
            .lastOrNull()
            ?.value
    }

    /**
     * Извлекает основные темы из истории
     */
    private fun extractMainTopics(messages: List<String>): List<String> {
        val topicKeywords = mapOf(
            "работа" to listOf("работа", "проект", "задача", "коллеги", "начальник", "офис", "встреча"),
            "семья" to listOf("семья", "дети", "муж", "жена", "родители", "ребенок"),
            "хобби" to listOf("хобби", "увлечение", "творчество", "рисование", "музыка", "спорт"),
            "здоровье" to listOf("здоровье", "самочувствие", "врач", "болезнь", "тренировка"),
            "путешествия" to listOf("путешествие", "отпуск", "поездка", "отель", "билеты"),
            "планы" to listOf("план", "цель", "мечта", "будущее", "намерение"),
            "проблемы" to listOf("проблема", "сложность", "трудность", "переживание", "стресс")
        )

        val topicScores = mutableMapOf<String, Int>()

        messages.forEach { message ->
            val lowerMessage = message.lowercase()
            topicKeywords.forEach { (topic, keywords) ->
                keywords.forEach { keyword ->
                    if (lowerMessage.contains(keyword)) {
                        topicScores[topic] = topicScores.getOrDefault(topic, 0) + 1
                    }
                }
            }
        }

        return topicScores.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
    }

    /**
     * Генерирует умные стратегии продолжения на основе анализа тем и контекста
     */
    private fun getContinuationStrategies(mainTopics: List<String>, lastUserMessage: String?): String {
        val strategies = mutableListOf<String>()

        // Анализ тем с приоритетами
        analyzeTopicsForStrategies(mainTopics, strategies)

        // Глубокий анализ последнего сообщения пользователя
        analyzeLastMessageForStrategies(lastUserMessage, strategies)

        // Добавление контекстных стратегий
        addContextualStrategies(strategies, mainTopics, lastUserMessage)

        // Fallback стратегии если не найдено конкретных
        if (strategies.isEmpty()) {
            addFallbackStrategies(strategies, mainTopics)
        }

        // Ограничиваем количество стратегий для фокуса
        return strategies.take(5).joinToString("\n")
    }

    fun showSystemUI() {
        try {
            // 1. Полностью прозрачные цвета
            window?.apply {
                navigationBarColor = Color.TRANSPARENT
                statusBarColor = Color.TRANSPARENT

                // Убираем ВСЕ флаги которые могут мешать
                clearFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                )

                // ОЧЕНЬ ВАЖНО: Добавляем этот флаг для управления цветом панелей
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            }

            // 2. Устанавливаем systemUiVisibility - КРИТИЧЕСКИ ВАЖНО
            window?.decorView?.apply {
                // Полностью сбрасываем
                systemUiVisibility = 0

                // Устанавливаем ТОЛЬКО эти флаги
                systemUiVisibility = (
                        // ОСНОВНЫЕ флаги для прозрачности
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        )
            }

            // 3. Для Android 10+ - особый подход
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window?.isNavigationBarContrastEnforced = false
                window?.navigationBarDividerColor = Color.TRANSPARENT
            }

            // 4. Показываем UI элементы
            binding.bottomNavigation.visibility = View.VISIBLE
            binding.toolbar.visibility = View.VISIBLE

            // 5. Важно: WindowCompat
            WindowCompat.setDecorFitsSystemWindows(window!!, false)

            Log.d(TAG, "System UI shown with FULLY TRANSPARENT bars")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing system UI", e)
        }
    }

    /**
     * Восстанавливает видимость UI элементов без изменения системных панелей
     */
    fun restoreAppUI() {
        try {
            // Просто показываем скрытые элементы
            binding.bottomNavigation.visibility = View.VISIBLE
            binding.toolbar.visibility = View.VISIBLE

            Log.d(TAG, "App UI restored")
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring app UI", e)
        }
    }


    /**
     * Анализирует основные темы для генерации стратегий
     */
    private fun analyzeTopicsForStrategies(mainTopics: List<String>, strategies: MutableList<String>) {
        if (mainTopics.isEmpty()) return

        val primaryTopic = mainTopics.first()
        val secondaryTopics = mainTopics.drop(1)

        // Стратегии для основной темы
        val primaryStrategies = listOf(
            "• Сфокусируйся на теме '$primaryTopic' - это доминирующая тема в обсуждении",
            "• Развивай тему '$primaryTopic' с новыми аспектами или вопросами",
            "• Свяжи текущий разговор с предыдущими обсуждениями '$primaryTopic'",
            "• Предложи практические советы или идеи по теме '$primaryTopic'"
        )
        strategies.add(primaryStrategies.random())

        // Стратегии для вторичных тем если есть
        if (secondaryTopics.isNotEmpty()) {
            val secondaryTopic = secondaryTopics.first()
            strategies.add("• Упомяни смежную тему '$secondaryTopic' для расширения диалога")
        }

        // Стратегия для множественных тем
        if (mainTopics.size >= 3) {
            strategies.add("• Найди связи между различными темами обсуждения")
        }
    }

    /**
     * Глубокий анализ последнего сообщения пользователя
     */
    private fun analyzeLastMessageForStrategies(lastUserMessage: String?, strategies: MutableList<String>) {
        lastUserMessage?.let { message ->
            val cleanMessage = message.trim().lowercase()

            // Анализ типа сообщения
            when {
                // Вопросы
                cleanMessage.contains("?") -> {
                    strategies.addAll(listOf(
                        "• Дай развернутый ответ на вопрос пользователя",
                        "• Задай встречный вопрос для уточнения деталей",
                        "• Предложи несколько вариантов ответа",
                        "• Свяжи ответ с предыдущим контекстом обсуждения"
                    ))
                }

                // Эмоционально окрашенные сообщения
                cleanMessage.contains(Regex("""!( |$)|💪|🔥|🎉|👍|😊|😍|рад|рада|счастлив|круто|супер|здорово""")) -> {
                    strategies.addAll(listOf(
                        "• Поддержи позитивный настрой и раздели энтузиазм",
                        "• Спроси о деталях, которые вызывают такие эмоции",
                        "• Предложи развить успех или поделиться опытом",
                        "• Вспомни похожие позитивные моменты из прошлых обсуждений"
                    ))
                }

                // Проблемы и сложности
                cleanMessage.contains(Regex("""проблем|сложн|трудн|переживаю|беспоко|волнуюсь|не могу|не получается|застрял""")) -> {
                    strategies.addAll(listOf(
                        "• Прояви эмпатию и предложи эмоциональную поддержку",
                        "• Задай уточняющие вопросы для понимания корня проблемы",
                        "• Предложи практические шаги или решения",
                        "• Напомни о прошлых успехах в преодолении трудностей",
                        "• Спроси, нужна ли помощь или дополнительные ресурсы"
                    ))
                }

                // Планы и намерения
                cleanMessage.contains(Regex("""планирую|хочу|собираюсь|мечтаю|цель|намерен|буду""")) -> {
                    strategies.addAll(listOf(
                        "• Прояви интерес к планам и предложи поддержку",
                        "• Задай вопросы о деталях реализации",
                        "• Предложи ресурсы или идеи для помощи",
                        "• Спроси о возможных препятствиях и как их преодолеть"
                    ))
                }

                // Достижения и успехи
                cleanMessage.contains(Regex("""сделал|достиг|получилось|успех|справился|закончил""")) -> {
                    strategies.addAll(listOf(
                        "• Поздравь с достижением и признай усилия",
                        "• Спроси о процессе и полученном опыте",
                        "• Предложи поделиться insights с другими",
                        "• Спроси о следующих целях или вызовах"
                    ))
                }

                // Короткие сообщения (менее 30 символов)
                message.length < 30 -> {
                    strategies.addAll(listOf(
                        "• Задай открытый вопрос для развития диалога",
                        "• Предложи конкретные темы для обсуждения",
                        "• Вспомни предыдущие темы, которые интересовали пользователя"
                    ))
                }

                // Длинные, подробные сообщения
                message.length > 100 -> {
                    strategies.addAll(listOf(
                        "• Выдели ключевые моменты из сообщения",
                        "• Задай уточняющие вопросы по наиболее важным аспектам",
                        "• Предложи углубиться в конкретные детали",
                        "• Свяжи с предыдущими обсуждениями для контекста"
                    ))
                }
            }

            // Анализ конкретных тем в сообщении
            analyzeSpecificTopicsInMessage(cleanMessage, strategies)
        }
    }

    /**
     * Анализирует конкретные темы в сообщении для более точных стратегий
     */
    private fun analyzeSpecificTopicsInMessage(message: String, strategies: MutableList<String>) {
        val topicPatterns = mapOf(
            "работа" to listOf(
                "• Спроси о текущих проектах или задачах",
                "• Предложи обсудить профессиональное развитие",
                "• Задай вопрос о рабочей атмосфере или коллегах"
            ),
            "семья" to listOf(
                "• Прояви интерес к благополучию близких",
                "• Спроси о семейных планах или событиях",
                "• Предложи поделиться семейными новостями"
            ),
            "здоровье" to listOf(
                "• Прояви заботу о самочувствии",
                "• Спроси о прогрессе в wellness-целях",
                "• Предложи обсудить привычки или рутины"
            ),
            "хобби" to listOf(
                "• Прояви интерес к увлечениям",
                "• Спроси о последних достижениях в хобби",
                "• Предложи поделиться творческими результатами"
            ),
            "путешествия" to listOf(
                "• Спроси о планах или мечтах о поездках",
                "• Предложи обсудить предыдущие путешествия",
                "• Задай вопрос о любимых местах или культурах"
            ),
            "обучение" to listOf(
                "• Прояви интерес к образовательному процессу",
                "• Спроси о последних инсайтах или открытиях",
                "• Предложи обсудить применение новых знаний"
            )
        )

        topicPatterns.forEach { (topic, topicStrategies) ->
            if (message.contains(topic)) {
                strategies.add(topicStrategies.random())
            }
        }
    }

    /**
     * Добавляет контекстные стратегии на основе комбинации тем и сообщения
     */
    private fun addContextualStrategies(strategies: MutableList<String>, mainTopics: List<String>, lastUserMessage: String?) {
        // Стратегии для нового диалога
        if (lastUserMessage == null || lastUserMessage.length < 10) {
            strategies.addAll(listOf(
                "• Начни с открытого вопроса о текущем состоянии или настроении",
                "• Предложи несколько тем для обсуждения на выбор",
                "• Вспомни предыдущие интересные темы из истории общения"
            ))
        }

        // Стратегии для продолжения активного диалога
        if (mainTopics.isNotEmpty() && lastUserMessage != null && lastUserMessage.length > 20) {
            strategies.addAll(listOf(
                "• Развивай текущую тему с новой перспективой",
                "• Спроси о прогрессе или изменениям с прошлого обсуждения",
                "• Предложи практическое применение обсуждаемых идей"
            ))
        }

        // Стратегии для углубления эмоциональной связи
        strategies.addAll(listOf(
            "• Прояви искренний интерес к переживаниям пользователя",
            "• Используй активное слушание в формулировках",
            "• Предложи поддержку и понимание в сложных темах"
        ))
    }

    /**
     * Добавляет fallback стратегии когда недостаточно контекста
     */
    private fun addFallbackStrategies(strategies: MutableList<String>, mainTopics: List<String>) {
        val fallbackStrategies = mutableListOf(
            "• Задай открытый вопрос о текущих мыслях или переживаниях",
            "• Предложи поделиться чем-то новым или интересным",
            "• Спроси о планах на ближайшее время",
            "• Прояви интерес к общему самочувствию и настроению",
            "• Предложи обсудить тему, которая ранее интересовала пользователя"
        )

        // Добавляем тематические fallback'и если есть темы
        if (mainTopics.isNotEmpty()) {
            fallbackStrategies.add("• Вернись к теме '${mainTopics.first()}' и спроси о развитии ситуации")
        }

        strategies.addAll(fallbackStrategies.shuffled().take(3))
    }

    private suspend fun sendAnalysisRequest(token: String, prompt: String): String? = withContext(ioDispatcher) {
        return@withContext try {
            // Создаем запрос БЕЗ temperature, так как его нет в модели
            val request = GigaChatRequest(
                model = "GigaChat",
                messages = listOf(Message(role = "user", content = prompt)),
                max_tokens = 100
                // temperature параметр удален, так как его нет в вашей модели
            )

            val call = RetrofitInstance.api.sendMessage("Bearer $token", request)
            val response = call.execute()

            if (response.isSuccessful) {
                val content = response.body()?.choices?.firstOrNull()?.message?.content
                content?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                Log.w(TAG, "AI analysis API error: ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending analysis request", e)
            null
        }
    }



    fun restoreUIAfterChat() {
        try {
            // 1. Показываем системные панели
            showSystemUI()

            // 2. Показываем элементы приложения
            binding.bottomNavigation.visibility = View.VISIBLE
            binding.toolbar.visibility = View.VISIBLE

            // 3. Обновляем навигацию
            binding.bottomNavigation.selectedItemId = R.id.nav_home

            // 4. ВАЖНО: Скрываем приветственную карточку после возвращения из чата
            if (::welcomeCard.isInitialized && welcomeCard.visibility == View.VISIBLE) {
                welcomeCard.visibility = View.GONE
                Log.d(TAG, "Welcome card hidden after returning from chat")
            }

            // 5. Переключаемся на HomeFragment
            safeSwitchToFragment(HOME_FRAGMENT_TAG) { HomeFragment() }


            Log.d(TAG, "UI restored after chat exit, welcome card hidden")

        } catch (e: Exception) {
            Log.e(TAG, "Error restoring UI after chat", e)
        }
    }





    // В классе MainActivity добавьте или обновите метод:
    private suspend fun switchToChatAsync() = withContext(uiDispatcher) {
        Log.d(TAG, "Start chat clicked")

        // Проверяем сеть перед открытием чата
        if (!isNetworkAvailable()) {
            Toast.makeText(this@MainActivity, "Для чата требуется интернет", Toast.LENGTH_SHORT).show()
            return@withContext
        }

        hideWelcomeMessage()
        saveLastChatTime()

        // Берем сохраненную сгенерированную фразу
        val continuationPhrase = withContext(ioDispatcher) {
            val sharedPref = getSharedPreferences("chat_prefs", MODE_PRIVATE)
            sharedPref.getString("continuation_phrase", null)
        }

        // Если есть сгенерированная фраза - используем ее, иначе fallback
        val finalPhrase = if (!continuationPhrase.isNullOrEmpty() &&
            continuationPhrase != "Анализирую наши предыдущие обсуждения...") {
            continuationPhrase
        } else {
            // Fallback: генерируем простую фразу на основе времени
            generateTimeBasedGreetingFallback()
        }

        // Сохраняем фразу для чата
        saveWelcomePhraseForChat(finalPhrase)

        // ВАЖНО: Используем safeSwitchToFragment вместо прямого replace
        safeSwitchToFragment(CHAT_FRAGMENT_TAG) { ChatWithGigaFragment() }

        // Обновляем навигацию
        binding.bottomNavigation.selectedItemId = -1 // Снимаем выделение

        Log.d(TAG, "Switching to chat with phrase: $finalPhrase")
    }

    /**
     * Обновленный метод переключения фрагментов с учетом чата
     */
    private fun switchToFragment(tag: String, fragmentFactory: () -> Fragment) {
        if (currentFragmentTag == tag) return

        lifecycleScope.launch(uiDispatcher) {
            try {
                // Если переходим в чат - скрываем панели
                if (tag == CHAT_FRAGMENT_TAG) {
                    hideSystemUIForChat()
                } else {
                    // Если выходим из чата - показываем панели
                    if (currentFragmentTag == CHAT_FRAGMENT_TAG) {
                        showSystemUI()
                        restoreAppUI()
                    }
                }

                // Далее ваш существующий код переключения фрагментов...
                val fragment = supportFragmentManager.findFragmentByTag(tag)

                if (fragment != null && fragment.isAdded) {
                    showFragment(tag)
                } else {
                    val newFragment = fragmentFactory()
                    loadFragment(newFragment, tag)
                }

                currentFragmentTag = tag

            } catch (e: Exception) {
                Log.e(TAG, "Error switching to fragment: $tag", e)
            }
        }
    }

    /**
     * Скрывает системные панели для чата (полноэкранный режим)
     */
    fun hideSystemUIForChat() {
        try {
            // 1. Скрываем системные панели
            window?.decorView?.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )

            // 2. Делаем панели прозрачными
            window?.navigationBarColor = Color.TRANSPARENT
            window?.statusBarColor = Color.TRANSPARENT

            // 3. Убираем любые ограничения
            window?.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            window?.addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )

            // 4. Скрываем нижнюю навигацию приложения ВСЕГДА
            binding.bottomNavigation.visibility = View.GONE
            binding.toolbar.visibility = View.GONE

            // 5. Скрываем приветственную карточку если она видна
            if (::welcomeCard.isInitialized && welcomeCard.visibility == View.VISIBLE) {
                welcomeCard.visibility = View.GONE
            }

            Log.d(TAG, "System UI hidden for chat (FULLSCREEN MODE)")

        } catch (e: Exception) {
            Log.e(TAG, "Error hiding system UI for chat", e)
        }
    }

    // Метод для открытия настроек голоса
    fun openVoiceSettings() {
        val fragment = VoiceSettingsFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack("voice_settings")
            .commitAllowingStateLoss()
    }




    /**
     * Генерирует конкретное продолжение на основе реального сообщения
     */
    private fun generateSpecificContinuation(lastMessage: String): String? {
        val cleanMessage = lastMessage.lowercase().trim()

        return when {
            cleanMessage.contains("работ") && cleanMessage.length > 15 ->
                "Как продвигаются рабочие задачи? Удалось разобраться с теми вопросами?"

            cleanMessage.contains("проект") && cleanMessage.length > 20 ->
                "Как развивается проект? Есть прогресс с момента нашего последнего обсуждения?"

            cleanMessage.contains("семь") || cleanMessage.contains("дет") ->
                "Как дела у семьи? Все ли хорошо?"

            cleanMessage.contains("план") || cleanMessage.contains("цел") ->
                "Как продвигается достижение ваших целей? Удалось сделать следующие шаги?"

            cleanMessage.contains("проблем") || cleanMessage.contains("сложн") ->
                "Как обстоят дела с той ситуацией? Удалось найти решение?"

            cleanMessage.contains("иде") && cleanMessage.length > 10 ->
                "Как продвигается работа над вашей идеей? Появились новые мысли?"

            cleanMessage.contains("путешеств") ->
                "Как ваши планы на поездку? Удалось что-то организовать?"

            // НОВОЕ: Проверяем, достаточно ли контекста для персонализированного продолжения
            cleanMessage.length > 25 && hasSubstantialContent(cleanMessage) ->
                generateEngagingContinuation(lastMessage)

            else -> null
        }
    }


    // В MainActivity.kt добавьте:
    fun getTTSManager(): TTSManager {
        return ttsManager
    }

    /**
     * Анализирует предыдущие диалоги для продолжения
     */
    private suspend fun analyzePreviousDialogsForContinuation(): String? = withContext(initDispatcher) {
        return@withContext try {
            // ПЕРВЫЙ ПРИОРИТЕТ: AI анализ истории чата (только если есть сеть)
            if (isNetworkAvailable()) {
                val aiContinuation = analyzeChatHistoryWithAI()
                if (!aiContinuation.isNullOrEmpty() && !isGenericContinuation(aiContinuation)) {
                    Log.d(TAG, "Using AI-generated continuation: ${aiContinuation.take(50)}")
                    return@withContext aiContinuation
                }
            }

            // ВТОРОЙ ПРИОРИТЕТ: Локальный анализ значимых сообщений (не требует сети)
            val chatHistory = loadRecentChatHistory()
            if (chatHistory.isNotEmpty()) {
                val lastMeaningfulMessage = findLastMeaningfulMessage(chatHistory)
                lastMeaningfulMessage?.let { message ->
                    val specificContinuation = generateSpecificContinuation(message)

                    // ВАЖНО: Если generateSpecificContinuation вернул null,
                    // значит сообщение не подходит для персонализации
                    if (specificContinuation != null && !isGenericContinuation(specificContinuation)) {
                        Log.d(TAG, "Using specific continuation: ${specificContinuation.take(50)}")
                        return@withContext specificContinuation
                    } else {
                        // Используем улучшенную генерацию для вовлечения
                        val engagingContinuation = generateEngagingContinuation(message)
                        if (!isGenericContinuation(engagingContinuation)) {
                            Log.d(TAG, "Using engaging continuation: ${engagingContinuation.take(50)}")
                            return@withContext engagingContinuation
                        }
                    }
                }
            }

            // ТРЕТИЙ ПРИОРИТЕТ: Анализ контекста как fallback
            val analyzer = contextAnalyzer ?: return@withContext null
            val deepContext = analyzer.analyzeDeepContext()
            val contextContinuation = generateContextBasedContinuation(deepContext)

            if (!isGenericContinuation(contextContinuation)) {
                Log.d(TAG, "Using context-based continuation: ${contextContinuation.take(50)}")
                return@withContext contextContinuation
            }

            // ЕСЛИ ВСЕ ПРОВАЛИЛОСЬ: возвращаем null, чтобы использовать естественную фразу
            null

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing dialog for continuation", e)
            null
        }
    }

    /**
     * Проверяет, является ли продолжение шаблонным/общим
     */
    private fun isGenericContinuation(continuation: String): Boolean {
        val genericPatterns = listOf(
            "последнему разговору",
            "эту тему",
            "об этом",
            "про это",
            "что вы про это думаете",
            "хотите продолжить эту тему",
            "возвращаясь к",
            "помню, мы обсуждали"
        )

        val lowerContinuation = continuation.lowercase()
        return genericPatterns.any { lowerContinuation.contains(it) }
    }

    /**
     * Проверяет, содержит ли сообщение достаточно содержания для персонализации
     */
    private fun hasSubstantialContent(message: String): Boolean {
        val trivialPhrases = listOf(
            "привет", "пока", "спасибо", "хорошо", "ок", "понятно", "да", "нет",
            "как дела", "что нового", "чем занят"
        )

        return !trivialPhrases.any { message.contains(it) } &&
                message.split(" ").size > 3 // Более 3 слов
    }

    /**
     * Получает токен для анализа
     */
    private suspend fun getAuthTokenForAnalysis(): String = withContext(ioDispatcher) {
        return@withContext try {
            suspendCoroutine { continuation ->
                val rqUid = UUID.randomUUID().toString()
                val authHeader = "Basic M2JhZGQ0NzktNGVjNy00ZmYyLWE4ZGQtNTMyOTViZDgzYzlkOjU4OGRkZDg1LTMzZmMtNDNkYi04MmJmLWFmZDM5Nzk5NmM2MQ=="

                val call = AuthRetrofitInstance.authApi.getAuthToken(
                    rqUid = rqUid,
                    authHeader = authHeader,
                    scope = "GIGACHAT_API_PERS"
                )

                call.enqueue(object : Callback<com.example.chatapp.api.AuthResponse> {
                    override fun onResponse(
                        call: Call<com.example.chatapp.api.AuthResponse>,
                        response: Response<com.example.chatapp.api.AuthResponse>
                    ) {
                        if (response.isSuccessful) {
                            continuation.resume(response.body()?.access_token ?: "")
                        } else {
                            Log.e(TAG, "Auth failed for analysis: ${response.code()}")
                            continuation.resume("")
                        }
                    }

                    override fun onFailure(call: Call<com.example.chatapp.api.AuthResponse>, t: Throwable) {
                        Log.e(TAG, "Auth network error for analysis", t)
                        continuation.resume("")
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting auth token for analysis", e)
            ""
        }
    }

    /**
     * Запускает AI анализ в фоне при инициализации
     */
    private fun startAIAnalysisBackground() {
        aiAnalysisJob?.cancel()

        aiAnalysisJob = lifecycleScope.launch(initDispatcher) {
            try {
                // Проверяем кэш
                if (System.currentTimeMillis() - lastAIAnalysisTime < AI_ANALYSIS_CACHE_TIME && cachedAIContinuation != null) {
                    Log.d(TAG, "Using cached AI analysis")
                    return@launch
                }

                // Проверяем сеть
                if (!isNetworkAvailable()) {
                    Log.d(TAG, "Network unavailable for background AI analysis")
                    return@launch
                }

                // Запускаем анализ только если есть история
                val hasRecentMessages = withContext(ioDispatcher) {
                    loadRecentChatHistoryForAI().isNotEmpty()
                }

                if (hasRecentMessages) {
                    Log.d(TAG, "Starting background AI analysis...")
                    cachedAIContinuation = analyzeChatHistoryWithAI()
                    lastAIAnalysisTime = System.currentTimeMillis()
                    Log.d(TAG, "Background AI analysis completed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background AI analysis failed", e)
            }
        }
    }

    /**
     * Быстрый метод получения AI продолжения (из кэша или синхронно)
     */
    private suspend fun getQuickAIContinuation(): String? = withContext(initDispatcher) {
        // Пробуем взять из кэша
        cachedAIContinuation?.let {
            Log.d(TAG, "Using cached AI continuation")
            return@withContext it
        }

        // Если кэша нет, проверяем сеть
        if (!isNetworkAvailable()) {
            Log.d(TAG, "Network unavailable for quick AI continuation")
            return@withContext null
        }

        // Если есть сообщения - быстрый синхронный запрос с таймаутом
        val hasMessages = withContext(ioDispatcher) {
            loadRecentChatHistoryForAI().isNotEmpty()
        }

        if (hasMessages) {
            try {
                Log.d(TAG, "Making quick AI analysis request")
                withTimeoutOrNull(3000L) { // Таймаут 3 секунды
                    analyzeChatHistoryWithAI()
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "AI analysis timeout, using fallback")
                null
            }
        } else {
            null
        }
    }

    /**
     * Логирование AI анализа
     */
    private fun logAIAnalysisPerformance(startTime: Long, success: Boolean, messageCount: Int) {
        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "AI Analysis - Duration: ${duration}ms, Success: $success, Messages: $messageCount")

        if (duration > 2000) {
            Log.w(TAG, "Slow AI analysis: ${duration}ms")
        }
    }

    /**
     * Запускает AI-улучшенное поэтапное отображение приветствия с УЛУЧШЕННОЙ последовательностью
     */
    private fun startStagedWelcomeSequence() {
        try {
            if (isWelcomeSequenceRunning) {
                Log.d(TAG, "Welcome sequence already running, skipping")
                return
            }

            isWelcomeSequenceRunning = true
            welcomeRetryCount = 0

            Log.d(TAG, "Starting IMPROVED AI-enhanced staged welcome sequence with TTS")

            welcomeSequenceJob?.cancel()
            welcomeSequenceJob = lifecycleScope.launch(uiDispatcher) {
                try {
                    // 1. Мгновенно - базовое приветствие (уже показано, озвучка идет)
                    Log.d(TAG, "Stage 1: Basic greeting already shown and being spoken")

                    // Даем время на озвучку приветствия
                    delay(WELCOME_STAGE_2_DELAY - TTS_GREETING_DELAY)

                    // 2. Показываем контекстный вопрос (ТОЛЬКО ТЕКСТ, БЕЗ ОЗВУЧКИ)
                    val contextQuestion = withContext(initDispatcher) {
                        try {
                            withTimeoutOrNull(1500L) {
                                generateContextualQuestionFromProfile()
                            } ?: "Как ваши дела?"
                        } catch (e: Exception) {
                            "Как ваши дела?"
                        }
                    }

                    withContext(uiDispatcher) {
                        animateTextChange(tvWelcomeQuestion, contextQuestion)
                        Log.d(TAG, "Stage 2: Context question shown (no TTS): $contextQuestion")
                    }

                    // Даем время пользователю увидеть вопрос
                    delay(1000L)

                    // 3. Параллельно запускаем AI анализ для продолжения
                    val aiAnalysisDeferred = if (isNetworkAvailable()) {
                        async(initDispatcher) {
                            try {
                                withTimeoutOrNull(2000L) {
                                    getQuickAIContinuation()
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }
                    } else {
                        CompletableDeferred<String?>(null)
                    }

                    val dialogAnalysisDeferred = async(initDispatcher) {
                        try {
                            withTimeoutOrNull(1500L) {
                                analyzePreviousDialogsForContinuation()
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }

                    // Ждем завершения анализа
                    delay(WELCOME_STAGE_3_DELAY - WELCOME_STAGE_2_DELAY)

                    val aiContinuation = aiAnalysisDeferred.await()
                    val dialogAnalysis = dialogAnalysisDeferred.await()

                    withContext(uiDispatcher) {
                        // УЛУЧШЕННАЯ ЛОГИКА ПРИОРИТЕТОВ:
                        val finalContinuation = when {
                            // Приоритет 1: AI анализ (если не шаблонный)
                            !aiContinuation.isNullOrEmpty() && !isGenericContinuation(aiContinuation) -> {
                                Log.d(TAG, "Using AI continuation (priority 1)")
                                aiContinuation
                            }
                            // Приоритет 2: Локальный анализ (если не шаблонный)
                            !dialogAnalysis.isNullOrEmpty() && !isGenericContinuation(dialogAnalysis) -> {
                                Log.d(TAG, "Using dialog analysis (priority 2)")
                                dialogAnalysis
                            }
                            // Приоритет 3: Естественная фраза на основе времени
                            else -> {
                                Log.d(TAG, "Using time-based natural continuation (priority 3)")
                                generateNaturalContinuationPhrase()
                            }
                        }

                        // ВАЖНО: Проверяем финальную фразу на шаблонность
                        val safeContinuation = if (isGenericContinuation(finalContinuation)) {
                            Log.w(TAG, "Final continuation was generic, using fallback")
                            generateNaturalContinuationPhrase()
                        } else {
                            finalContinuation
                        }

                        animateTextChange(tvWelcomeContext, safeContinuation)

                        // ОЗВУЧИВАЕМ AI-продолжение (через паузу после приветствия)
                        handler.postDelayed({
                            speakAIContinuation(safeContinuation)
                        }, TTS_CONTINUATION_DELAY)

                        // Сохраняем ТОЛЬКО если это не шаблонная фраза
                        if (!isGenericContinuation(safeContinuation)) {
                            saveCompleteWelcomePhraseForChatAsync(safeContinuation)
                        } else {
                            Log.w(TAG, "Skipping save - generic continuation detected")
                        }

                        Log.d(TAG, "Stage 3: Final continuation - " +
                                "AI: ${aiContinuation?.take(30) ?: "none"}, " +
                                "Local: ${dialogAnalysis?.take(30) ?: "none"}, " +
                                "Final: ${safeContinuation.take(30)}")
                    }

                } catch (e: CancellationException) {
                    Log.d(TAG, "Welcome sequence cancelled")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in improved welcome sequence", e)
                    // Повторная попытка
                    if (welcomeRetryCount < MAX_WELCOME_RETRIES) {
                        welcomeRetryCount++
                        Log.d(TAG, "Retrying welcome sequence (attempt $welcomeRetryCount)")
                        delay(WELCOME_RETRY_DELAY)
                        startStagedWelcomeSequence()
                    } else {
                        withContext(uiDispatcher) {
                            // Используем естественное fallback-приветствие
                            showNaturalFallbackGreeting()
                        }
                    }
                } finally {
                    isWelcomeSequenceRunning = false
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting improved welcome sequence", e)
            showNaturalFallbackGreeting()
        }
    }

    /**
     * Показывает естественное fallback-приветствие без шаблонов с ПРАВИЛЬНЫМ порядком TTS
     */
    private fun showNaturalFallbackGreeting() {
        try {
            val naturalGreeting = generateNaturalContinuationPhrase()
            tvWelcomeContext.text = naturalGreeting

            // 1. Сначала говорим основное приветствие (если еще не сказали)
            if (!hasGreetingBeenSpoken && isTTSInitialized) {
                val userName = getUserName()
                val timeGreeting = getTimeBasedGreeting()
                val fullGreeting = "$timeGreeting, $userName!"

                speakGreeting(fullGreeting)
            }

            // 2. Через паузу говорим продолжение
            handler.postDelayed({
                speakAIContinuation(naturalGreeting)
            }, TTS_CONTINUATION_DELAY)

            // 3. Сохраняем для чата
            saveCompleteWelcomePhraseForChatAsync(naturalGreeting)

            Log.d(TAG, "Natural fallback greeting shown with proper TTS order")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing natural fallback greeting", e)
        }
    }

    /**
     * Генерирует контекстный вопрос на основе анкеты пользователя
     */
    private suspend fun generateContextualQuestionFromProfile(): String = withContext(initDispatcher) {
        return@withContext try {
            userProfile?.let { profile ->
                // Используем случайный выбор приоритета для разнообразия
                when ((0..100).random()) {
                    // 40% вероятность - работа
                    in 0..40 -> if (profile.occupation.isNotEmpty()) {
                        val workQuestions = listOf(
                            "Как продвигается работа в сфере ${profile.occupation}?",
                            "Какие интересные задачи в ${profile.occupation} сейчас?",
                            "Что нового в профессиональной деятельности?",
                            "Над какими проектами работаете в ${profile.occupation}?",
                            "Как развиваетесь в ${profile.occupation}?",
                            "Что вдохновляет в вашей профессии?",
                            "Какие вызовы в ${profile.occupation} преодолеваете?",
                            "Чем гордитесь в профессиональной сфере?"
                        )
                        workQuestions.random()
                    } else null

                    // 25% вероятность - хобби
                    in 41..65 -> profile.hobbies.takeIf { it.isNotEmpty() }?.let {
                        val mainHobby = profile.hobbies.split(",").firstOrNull()?.trim()
                        mainHobby?.let {
                            val hobbyQuestions = listOf(
                                "Удалось позаниматься $mainHobby?",
                                "Что нового в увлечении $mainHobby?",
                                "Как прогресс в $mainHobby?",
                                "Что вдохновляет в $mainHobby?",
                                "Какие цели ставите в $mainHobby?",
                                "Что сложного в $mainHobby преодолеваете?",
                                "Как $mainHobby развивает вас?"
                            )
                            hobbyQuestions.random()
                        }
                    }

                    // 15% вероятность - семья
                    in 66..80 -> if (profile.hasChildren) {
                        val familyQuestions = listOf(
                            "Как дела у детей?",
                            "Чем увлекаются дети?",
                            "Какие семейные моменты радуют?",
                            "Как проводите время с семьей?",
                            "Что нового у близких?",
                            "Какие семейные традиции создаете?"
                        )
                        familyQuestions.random()
                    } else null

                    // 10% вероятность - спорт
                    in 81..90 -> if (profile.fitnessLevel.isNotEmpty() && profile.fitnessLevel != "Не занимаюсь спортом") {
                        val fitnessQuestions = listOf(
                            "Как ваши тренировки?",
                            "Удалось позаниматься сегодня?",
                            "Как самочувствие после занятий спортом?",
                            "Какие цели в фитнесе ставите?",
                            "Что мотивирует заниматься спортом?"
                        )
                        fitnessQuestions.random()
                    } else null

                    // 10% вероятность - цели
                    else -> profile.currentGoals.takeIf { it.isNotEmpty() }?.let {
                        val mainGoal = profile.currentGoals.split(",").firstOrNull()?.trim()
                        mainGoal?.let {
                            val goalQuestions = listOf(
                                "Как продвигается цель '$mainGoal'?",
                                "Что делаете для достижения $mainGoal?",
                                "Какие шаги к $mainGoal предпринимаете?",
                                "Что вдохновляет в достижении $mainGoal?"
                            )
                            goalQuestions.random()
                        }
                    }
                } ?: "Чем увлекаетесь в последнее время?"
            } ?: "Как ваши дела?"
        } catch (e: Exception) {
            Log.e(TAG, "Error generating contextual question", e)
            "Как ваши дела?"
        }
    }

    /**
     * Загружает историю чата из SharedPreferences или БД
     */
    private suspend fun loadRecentChatHistory(): List<String> = withContext(ioDispatcher) {
        return@withContext try {
            val sharedPref = getSharedPreferences("chat_history", MODE_PRIVATE)
            val historyJson = sharedPref.getString("recent_messages", "[]")
            val messages = Gson().fromJson(historyJson, Array<String>::class.java).toList()
            messages.takeLast(10) // Берем последние 10 сообщений
        } catch (e: Exception) {
            Log.e(TAG, "Error loading chat history", e)
            emptyList()
        }
    }

    /**
     * Находит последнее значимое сообщение пользователя
     */
    private fun findLastMeaningfulMessage(messages: List<String>): String? {
        return messages.reversed().firstOrNull { message ->
            message.length > 10 &&
                    !message.contains("привет", ignoreCase = true) &&
                    !message.contains("пока", ignoreCase = true) &&
                    !message.contains("спасибо", ignoreCase = true)
        }
    }

    /**
     * Генерирует вовлекающую фразу продолжения для любых тем
     */
    private fun generateEngagingContinuation(topic: String): String {
        // Очищаем и форматируем тему
        val cleanTopic = cleanTopicForDisplay(topic)

        // Разные категории фраз для разнообразия
        val questionContinuations = listOf(
            "Возвращаясь к нашему разговору о $cleanTopic... Что думаете сейчас по этому поводу?",
            "Помню, мы обсуждали $cleanTopic... Появились новые мысли или идеи?",
            "Насчет $cleanTopic... Хотите продолжить эту интересную тему?",
            "Вы упоминали $cleanTopic... Как развивается эта ситуация?",
            "Мы говорили о $cleanTopic... Есть что-то новое, чем хотите поделиться?",
            "Возвращаясь к теме $cleanTopic... Что изменилось с тех пор?",
            "Помню ваши мысли о $cleanTopic... Хотите обсудить это подробнее?",
            "Насчет нашего разговора о $cleanTopic... Появились новые вопросы?",
            "Мы затрагивали $cleanTopic... Хотите углубиться в эту тему?",
            "Вы делились мнением о $cleanTopic... Изменилось ли что-то в вашем взгляде?"
        )

        val reflectiveContinuations = listOf(
            "Размышляя о нашем разговоре про $cleanTopic... Какие выводы вы сделали?",
            "После нашего обсуждения $cleanTopic... Что показалось вам самым ценным?",
            "Вспоминая тему $cleanTopic... Что запомнилось больше всего?",
            "Возвращаясь к $cleanTopic... Какие инсайты появились?",
            "Размышляя над $cleanTopic... Что открыли для себя нового?",
            "После беседы о $cleanTopic... Что стало для вас откровением?",
            "Вспоминая наш разговор о $cleanTopic... Какие мысли остались с вами?"
        )

        val actionOrientedContinuations = listOf(
            "Насчет $cleanTopic... Удалось что-то предпринять после нашего разговора?",
            "Помню, мы обсуждали $cleanTopic... Какие шаги удалось сделать?",
            "Возвращаясь к $cleanTopic... Есть прогресс в этом направлении?",
            "После нашего разговора о $cleanTopic... Что удалось реализовать?",
            "Насчет планов по $cleanTopic... Удалось продвинуться?",
            "После обсуждения $cleanTopic... Какие действия предприняли?"
        )

        val curiousContinuations = listOf(
            "Мне было очень интересно обсуждать с вами $cleanTopic... Хотите продолжить?",
            "Тема $cleanTopic действительно увлекла меня... А вас?",
            "Наше обсуждение $cleanTopic было таким живым... Давайте вернемся к нему!",
            "Мне понравилось, как мы говорили о $cleanTopic... Хотите развить эту тему?",
            "$cleanTopic - такая многогранная тема... Что еще хотели бы обсудить?",
            "Наша беседа о $cleanTopic была такой продуктивной... Продолжим?"
        )

        val personalContinuations = listOf(
            "Помню, вы с таким интересом рассказывали о $cleanTopic... Хотите поделиться новостями?",
            "Вы так увлеченно говорили о $cleanTopic... Что нового открыли для себя?",
            "Мне запомнилось, как вы рассказывали о $cleanTopic... Есть чем дополнить?",
            "Вы делились таким интересным взглядом на $cleanTopic... Изменилось ли что-то?",
            "Помню вашу позицию по $cleanTopic... Хотите ее развить?"
        )

        // Комбинируем все категории
        val allContinuations = questionContinuations + reflectiveContinuations +
                actionOrientedContinuations + curiousContinuations + personalContinuations

        return allContinuations.random()
    }

    /**
     * Очищает и форматирует тему для красивого отображения
     */
    private fun cleanTopicForDisplay(topic: String): String {
        return try {
            var cleaned = topic.trim()

            // Убираем лишние пробелы
            cleaned = cleaned.replace(Regex("\\s+"), " ")

            // Обрезаем слишком длинные темы
            if (cleaned.length > 50) {
                cleaned = cleaned.take(47) + "..."
            }

            // Убираем знаки препинания в конце если они есть
            cleaned = cleaned.trimEnd('!', '?', '.', ',', ';', ':')

            // Добавляем кавычки для красоты
            "\"$cleaned\""
        } catch (e: Exception) {
            "\"эту тему\""
        }
    }

    /**
     * Генерирует продолжение на основе контекста
     */
    private fun generateContextBasedContinuation(deepContext: DeepConversationContext): String {
        return try {
            // ПРИОРИТЕТ 1: Активные темы с высоким весом
            deepContext.activeTopics
                .filter { it.weight > 1.8 && it.name.length > 4 && !isGenericTopic(it.name) }
                .maxByOrNull { it.weight }?.let { topic ->
                    return when {
                        topic.name.contains("работ", true) || topic.name.contains("проект", true) ->
                            "Как продвигаются рабочие задачи? Есть новости по проектам?"

                        topic.name.contains("семь", true) || topic.name.contains("дет", true) ->
                            "Как дела у семьи? Все ли хорошо?"

                        topic.name.contains("хобби", true) || topic.name.contains("увлечен", true) ->
                            "Удалось позаниматься хобби? Что нового в увлечениях?"

                        topic.name.contains("спорт", true) || topic.name.contains("трениров", true) ->
                            "Как ваши тренировки? Удается придерживаться графика?"

                        topic.name.contains("здоровь", true) || topic.name.contains("самочувств", true) ->
                            "Как самочувствие? Есть улучшения?"

                        topic.name.contains("план", true) || topic.name.contains("цел", true) ->
                            "Как продвигается достижение ваших целей?"

                        else -> "Давайте продолжим наш разговор о ${topic.name}... Есть что рассказать?"
                    }
                }

            // ПРИОРИТЕТ 2: Незавершенные обсуждения
            deepContext.pendingDiscussions.firstOrNull()?.let { discussion ->
                return when (discussion.type) {
                    "natural_continuation" ->
                        "Возвращаясь к теме ${discussion.topic}... Как развивается ситуация?"
                    "unanswered_question" ->
                        "Хотите вернуться к вашему вопросу про ${discussion.topic}?"
                    else -> "Давайте продолжим наши обсуждения..."
                }
            }

            // ПРИОРИТЕТ 3: Эмоциональный контекст
            when {
                deepContext.emotionalState.emotionalScore > 0.7 ->
                    return "Рад видеть ваше отличное настроение! О чем хотите поговорить сегодня?"
                deepContext.emotionalState.emotionalScore < -0.7 ->
                    return "Надеюсь, наша беседа поможет улучшить настроение! Что вас беспокоит?"
                else -> {
                    // Переходим к следующему приоритету
                }
            }

            // ПРИОРИТЕТ 4: Временной контекст (fallback)
            generateTimeBasedContinuation()

        } catch (e: Exception) {
            Log.e(TAG, "Error generating context based continuation", e)
            "Рад нашей беседе! Чем могу помочь?"
        }
    }

    /**
     * Проверяет, является ли тема общей/неконкретной
     */
    private fun isGenericTopic(topicName: String): Boolean {
        val genericTopics = listOf(
            "привет", "пока", "спасибо", "да", "нет", "ок", "хорошо", "понятно",
            "здравствуйте", "добрый", "как дела", "что нового", "чем занят"
        )
        return genericTopics.any { topicName.contains(it, true) }
    }

    /**
     * Генерирует естественную фразу продолжения на основе времени
     */
    private fun generateTimeBasedContinuation(): String {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY

        return when {
            hour in 5..11 -> when {
                isWeekend -> "Прекрасное утро выходного дня! Какие планы на сегодня?"
                dayOfWeek == Calendar.MONDAY -> "Начало новой недели! Какие цели ставите перед собой?"
                else -> "Доброе утро! Что интересного планируете на сегодня?"
            }
            hour in 12..17 -> when {
                isWeekend -> "Как проходит ваш выходной день? Удалось отдохнуть?"
                else -> "Как продвигается день? Есть что-то, что особенно радует?"
            }
            hour in 18..23 -> when {
                isWeekend -> "Вечер выходных... Подводите итоги выходного? Какие моменты запомнились?"
                else -> "Как прошел рабочий день? Хотите поделиться впечатлениями?"
            }
            else -> "Не спится? Хотите поболтать о чем-то интересном?"
        }
    }

    /**
     * Генерирует естественную фразу продолжения если анализ не дал результатов
     */
    private fun generateNaturalContinuationPhrase(): String {
        val phrases = listOf(
            "Что бы вы хотели обсудить сегодня?",
            "Есть что-то, что вас особенно интересует в последнее время?",
            "Чем увлекаетесь в эти дни?",
            "Что нового и интересного произошло?",
            "О чем думаете в последнее время?",
            "Какие темы вас сейчас волнуют?",
            "Чем могу помочь или что обсудить?"
        )
        return phrases.random()
    }

    /**
     * Асинхронное сохранение ТОЛЬКО третьей фразы для чата
     */
    private fun saveCompleteWelcomePhraseForChatAsync() {
        lifecycleScope.launch(initDispatcher) {
            try {
                // Берем ТОЛЬКО третью фразу (контекст продолжения)
                val contextPhrase = tvWelcomeContext.text.toString()

                // Очищаем предыдущие сохраненные фразы
                val sharedPref = getSharedPreferences("chat_prefs", MODE_PRIVATE)
                sharedPref.edit()
                    .remove("complete_welcome_phrase") // старый формат
                    .remove("welcome_phrase") // старый формат
                    .putString("continuation_phrase", contextPhrase) // новый формат
                    .apply()

                Log.d(TAG, "Continuation phrase saved for chat: $contextPhrase")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving continuation phrase", e)
            }
        }
    }

    private fun showLoadingProgress() {
        try {
            binding.welcomeCard.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Error showing loading progress", e)
        }
    }

    /**
     * Скрывает прогресс-индикатор загрузки
     */
    private fun hideLoadingProgress() {
        try {
            // Можно добавить анимацию скрытия если нужно
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding loading progress", e)
        }
    }

    /**
     * Показывает базовое приветствие (fallback) с ПРАВИЛЬНЫМ порядком TTS
     */
    private fun showBasicWelcomeMessage() {
        try {
            val userName = getUserName()
            val greeting = getTimeBasedGreeting()
            val greetingText = "$greeting, $userName!"

            // Обновляем UI
            tvWelcomeTitle.text = greetingText
            tvWelcomeQuestion.text = "Чем увлекаетесь в последнее время?"

            // Генерируем осмысленную фразу вместо заглушки
            val meaningfulContinuation = generateNaturalContinuationPhrase()
            tvWelcomeContext.text = meaningfulContinuation

            // 1. Сначала озвучиваем основное приветствие
            speakGreeting(greetingText)

            // 2. Через большую паузу озвучиваем продолжение
            handler.postDelayed({
                speakAIContinuation(meaningfulContinuation)
            }, TTS_CONTINUATION_DELAY)

            // Сохраняем эту фразу для чата
            saveCompleteWelcomePhraseForChatAsync(meaningfulContinuation)

            // Сбрасываем состояния и показываем без анимации
            resetWelcomeCardState()
            welcomeCard.visibility = View.VISIBLE
            welcomeCard.alpha = 1f
            welcomeCard.scaleX = 1f
            welcomeCard.scaleY = 1f
            welcomeCard.translationY = 0f

            welcomeContent.visibility = View.VISIBLE
            welcomeContent.alpha = 1f
            progressWelcome.visibility = View.GONE

            Log.d(TAG, "Basic welcome message shown with proper TTS order")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing basic welcome message", e)
        }
    }

    /**
     * Асинхронная загрузка профиля пользователя
     */
    private suspend fun loadUserProfileAsync(): UserProfile? = withContext(ioDispatcher) {
        val currentUser = Firebase.auth.currentUser ?: return@withContext null

        return@withContext try {
            // ПРОВЕРКА СЕТИ
            if (!isNetworkAvailable()) {
                Log.d(TAG, "Network unavailable, loading cached profile")
                return@withContext loadCachedProfile()
            }

            // ТАЙМАУТ для Firebase
            val snapshot = withTimeoutOrNull(3000L) {
                Firebase.database.reference
                    .child("user_profiles")
                    .child(currentUser.uid)
                    .get()
                    .await()
            }

            if (snapshot != null && snapshot.exists()) {
                val profile = snapshot.getValue(UserProfile::class.java)
                Log.d(TAG, "User profile loaded from network: ${profile != null}")

                // Кэшируем профиль
                profile?.let { cacheProfile(it) }

                profile
            } else {
                Log.d(TAG, "No user profile found in database")
                createBasicUserProfile(currentUser.uid)
                UserProfile(userId = currentUser.uid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user profile", e)
            loadCachedProfile()
        }
    }

    /**
     * Кэширует профиль пользователя
     */
    private fun cacheProfile(profile: UserProfile) {
        try {
            val json = Gson().toJson(profile)
            getSharedPreferences("user_cache", MODE_PRIVATE)
                .edit()
                .putString("cached_profile", json)
                .apply()
            Log.d(TAG, "User profile cached")
        } catch (e: Exception) {
            Log.e(TAG, "Error caching profile", e)
        }
    }

    /**
     * Создает базовый профиль пользователя если его нет
     */
    private suspend fun createBasicUserProfile(userId: String) = withContext(ioDispatcher) {
        try {
            val basicProfile = UserProfile(
                userId = userId,
                createdAt = System.currentTimeMillis(),
                lastUpdated = System.currentTimeMillis()
            )

            Firebase.database.reference.child("user_profiles").child(userId).setValue(basicProfile).await()
            Log.d(TAG, "Basic user profile created")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating basic user profile", e)
        }
    }

    /**
     * Асинхронная загрузка данных пользователя
     */
    private suspend fun loadCurrentUserDataAsync(): Boolean = withContext(ioDispatcher) {
        val currentUserId = auth.currentUser?.uid ?: return@withContext false

        return@withContext try {
            // ПРОВЕРКА СЕТИ
            if (!isNetworkAvailable()) {
                Log.d(TAG, "Network unavailable for user data")
                return@withContext false
            }

            val snapshot = withTimeoutOrNull(3000L) {
                Firebase.database.reference
                    .child("users")
                    .child(currentUserId)
                    .get()
                    .await()
            }

            if (snapshot != null && snapshot.exists()) {
                val user = snapshot.getValue(User::class.java)
                user?.let {
                    withContext(uiDispatcher) {
                        updateToolbarUserInfo(it)

                        val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
                        val fullName = it.getFullName()
                        sharedPref.edit().putString("user_name", fullName).apply()

                        val firstName = extractFirstName(fullName)
                        sharedPref.edit().putString("first_name", firstName).apply()

                        Log.d(TAG, "User data loaded and saved: $fullName")
                    }
                }
                true
            } else {
                loadUserNameFromAuthAsync()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user data", e)
            loadUserNameFromAuthAsync()
            false
        }
    }

    /**
     * Асинхронная загрузка имени из Auth
     */
    private suspend fun loadUserNameFromAuthAsync() = withContext(uiDispatcher) {
        val currentUser = auth.currentUser ?: return@withContext

        try {
            val userName = when {
                currentUser.displayName?.isNotEmpty() == true -> {
                    extractFirstName(currentUser.displayName!!)
                }
                currentUser.email?.isNotEmpty() == true -> {
                    currentUser.email!!.split("@").first()
                }
                else -> "Пользователь"
            }

            val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
            sharedPref.edit().putString("first_name", userName).apply()

            Log.d(TAG, "User name loaded from Auth: $userName")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user name from auth", e)
        }
    }

    /**
     * Запускает активность анкеты
     */
    fun startUserQuestionnaireActivity() {
        try {
            startActivity(Intent(this, UserQuestionnaireActivity::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Error starting UserQuestionnaireActivity", e)
            Toast.makeText(this, "Ошибка открытия анкеты", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Показывает диалог с предложение заполнить анкету
     */
    private fun showQuestionnairePrompt() {
        lifecycleScope.launch(uiDispatcher) {
            try {
                val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
                val questionnaireCompleted = sharedPref.getBoolean("questionnaire_completed", false)

                if (questionnaireCompleted) {
                    startUserQuestionnaireActivity()
                } else {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("🎯 Персонализация")
                        .setMessage("Заполните анкету для умного и персонализированного общения!\n\n" +
                                "• Контекстные приветствия\n" +
                                "• Учет ваших интересов\n" +
                                "• Персональные вопросы\n" +
                                "• Более осмысленные беседы")
                        .setPositiveButton("Заполнить анкету") { _, _ ->
                            startUserQuestionnaireActivity()
                        }
                        .setNegativeButton("Позже") { dialog, _ ->
                            dialog.dismiss()
                            updateWelcomeMessageWithProfile()
                        }
                        .setNeutralButton("Не предлагать снова") { _, _ ->
                            sharedPref.edit().putBoolean("questionnaire_prompt_disabled", true).apply()
                            Toast.makeText(this@MainActivity, "Вы можете всегда заполнить анкету в меню", Toast.LENGTH_SHORT).show()
                        }
                        .setCancelable(false)
                        .show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error showing questionnaire prompt", e)
            }
        }
    }

    /**
     * Проверяет нужно ли показать приглашение к анкете
     */
    private fun checkAndShowQuestionnairePrompt() {
        lifecycleScope.launch(uiDispatcher) {
            delay(5000) // Через 5 секунд после запуска

            val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
            val questionnaireCompleted = sharedPref.getBoolean("questionnaire_completed", false)
            val promptDisabled = sharedPref.getBoolean("questionnaire_prompt_disabled", false)

            if (!questionnaireCompleted && !promptDisabled) {
                showQuestionnairePrompt()
            }
        }
    }

    /**
     * Обновляет приветственное сообщение с учетом профиля пользователя
     */
    private fun updateWelcomeMessageWithProfile() {
        try {
            if (greetingGenerator == null) {
                Log.w(TAG, "Greeting generator not initialized, using basic welcome")
                showBasicWelcomeMessage()
                return
            }

            // Перезапускаем последовательность приветствия
            startStagedWelcomeSequence()
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateWelcomeMessageWithProfile", e)
            showBasicWelcomeMessage()
        }
    }

    /**
     * Получение приветствия по времени суток
     */
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

    /**
     * Сохраняет время последнего чата для контекста
     */
    fun saveLastChatTime() {
        lifecycleScope.launch(ioDispatcher) {
            val sharedPref = getSharedPreferences("chat_prefs", MODE_PRIVATE)
            sharedPref.edit().putLong("last_chat_time", System.currentTimeMillis()).apply()
        }
    }

    /**
     * Сохраняет продолжительность чата и обновляет аналитику
     */
    fun saveChatDuration(duration: Long) {
        lifecycleScope.launch(ioDispatcher) {
            val sharedPref = getSharedPreferences("chat_prefs", MODE_PRIVATE)
            sharedPref.edit().putLong("last_chat_duration", duration).apply()

            val currentFrequency = sharedPref.getInt("chat_frequency", 0)
            sharedPref.edit().putInt("chat_frequency", currentFrequency + 1).apply()
            sharedPref.edit().putLong("last_chat_end_time", System.currentTimeMillis()).apply()

            Log.d(TAG, "Chat duration saved: ${duration}ms, frequency: ${currentFrequency + 1}")
        }
    }

    /**
     * Очищает устаревшие данные чата для улучшения производительности анализа
     */
    private fun cleanupOldChatData() {
        lifecycleScope.launch(ioDispatcher) {
            val sharedPref = getSharedPreferences("chat_prefs", MODE_PRIVATE)
            val lastCleanup = sharedPref.getLong("last_cleanup_time", 0)
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastCleanup > 7 * 24 * 60 * 60 * 1000) {
                sharedPref.edit().putLong("last_cleanup_time", currentTime).apply()
                Log.d(TAG, "Chat data cleanup completed")
            }
        }
    }

    /**
     * Скрытие приветственного сообщения с анимацией и остановкой TTS
     */
    private fun hideWelcomeMessage() {
        try {
            // Отменяем последовательность приветствия
            welcomeSequenceJob?.cancel()
            isWelcomeSequenceRunning = false

            // Останавливаем ВСЕ TTS
            ttsManager.stop()
            ttsManager.clearQueue()  // Очищаем очередь

            val exitAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 300
                interpolator = AccelerateInterpolator()

                addUpdateListener { animation ->
                    val progress = animation.animatedValue as Float
                    welcomeCard.alpha = progress
                    welcomeCard.scaleX = 1f - 0.1f * (1 - progress)
                    welcomeCard.scaleY = 1f - 0.1f * (1 - progress)
                    welcomeCard.translationY = -30f * (1 - progress)
                }

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        welcomeCard.visibility = View.GONE
                        resetWelcomeCardState() // Сбрасываем для следующего показа

                        // Сбрасываем флаги TTS
                        hasGreetingBeenSpoken = false
                        hasAIContinuationBeenSpoken = false
                    }
                })
            }

            exitAnimator.start()

            Log.d(TAG, "Welcome message hidden with animation, TTS stopped")

        } catch (e: Exception) {
            Log.e(TAG, "Error hiding welcome message", e)
            welcomeCard.visibility = View.GONE
            ttsManager.stop()
        }
    }

    /**
     * Сохраняет приветственную фразу для использования в чате
     */
    fun saveWelcomePhraseForChat(phrase: String) {
        lifecycleScope.launch(ioDispatcher) {
            try {
                val sharedPref = getSharedPreferences("chat_prefs", MODE_PRIVATE)
                sharedPref.edit().putString("welcome_phrase", phrase).apply()
                Log.d(TAG, "Welcome phrase saved for chat: $phrase")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving welcome phrase", e)
            }
        }
    }

    /**
     * Получает сохраненную приветственную фразу
     */
    fun getSavedWelcomePhrase(): String? {
        val sharedPref = getSharedPreferences("chat_prefs", MODE_PRIVATE)
        return sharedPref.getString("welcome_phrase", null)
    }

    /**
     * Очищает сохраненную приветственную фразу
     */
    fun clearSavedWelcomePhrase() {
        lifecycleScope.launch(ioDispatcher) {
            val sharedPref = getSharedPreferences("chat_prefs", MODE_PRIVATE)
            sharedPref.edit().remove("welcome_phrase").apply()
            Log.d(TAG, "Welcome phrase cleared")
        }
    }

    /**
     * Обновляет профиль пользователя при изменении анкеты
     */
    fun updateUserProfile(newProfile: UserProfile) {
        userProfile = newProfile
        greetingGenerator = SmartQuestionGenerator(this, userProfile)

        // Обновляем приветствие если оно видимо
        if (welcomeCard.visibility == View.VISIBLE) {
            updateWelcomeMessageWithProfile()
        }

        Log.d(TAG, "User profile updated in MainActivity")
    }

    /**
     * Расширенная диагностика данных пользователя
     */
    private fun debugUserDataExtended() {
        // Только для отладки
        if (!BuildConfig.DEBUG) return

        lifecycleScope.launch(ioDispatcher) {
            try {
                val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
                val userName = sharedPref.getString("user_name", "NOT_SET")
                val firstName = sharedPref.getString("first_name", "NOT_SET")

                val currentUser = auth.currentUser
                Log.d(TAG, "=== EXTENDED USER DATA DEBUG ===")
                Log.d(TAG, "SharedPref - user_name: $userName")
                Log.d(TAG, "SharedPref - first_name: $firstName")
                Log.d(TAG, "Firebase Auth - displayName: ${currentUser?.displayName ?: "NO_DISPLAY_NAME"}")
                Log.d(TAG, "Firebase Auth - email: ${currentUser?.email ?: "NO_EMAIL"}")
                Log.d(TAG, "Firebase Auth - UID: ${currentUser?.uid ?: "NO_UID"}")
                Log.d(TAG, "Firebase Auth - provider: ${currentUser?.providerId ?: "NO_PROVIDER"}")
                Log.d(TAG, "Firebase Auth - isAnonymous: ${currentUser?.isAnonymous ?: "UNKNOWN"}")
                Log.d(TAG, "UserProfile - loaded: ${userProfile != null}")
                userProfile?.let { profile ->
                    Log.d(TAG, "UserProfile - occupation: ${profile.occupation}")
                    Log.d(TAG, "UserProfile - hobbies: ${profile.hobbies}")
                    Log.d(TAG, "UserProfile - hasChildren: ${profile.hasChildren}")
                }
                Log.d(TAG, "GreetingGenerator - initialized: ${greetingGenerator != null}")
                Log.d(TAG, "ContextAnalyzer - initialized: ${contextAnalyzer != null}")
                Log.d(TAG, "TTS Manager - initialized: $isTTSInitialized")

                checkFirebaseUserData()

            } catch (e: Exception) {
                Log.e(TAG, "Error in extended user data debug", e)
            }
        }
    }

    /**
     * Проверяет данные пользователя в Firebase Database
     */
    private fun checkFirebaseUserData() {
        lifecycleScope.launch(ioDispatcher) {
            try {
                val currentUserId = auth.currentUser?.uid ?: return@launch

                val database = Firebase.database.reference
                val snapshot = database.child("users").child(currentUserId).get().await()

                if (snapshot.exists()) {
                    val user = snapshot.getValue(User::class.java)
                    Log.d(TAG, "Firebase DB - user exists: true")
                    Log.d(TAG, "Firebase DB - fullName: ${user?.getFullName() ?: "NO_FULL_NAME"}")
                    Log.d(TAG, "Firebase DB - name: ${user?.name ?: "NO_NAME"}")
                    Log.d(TAG, "Firebase DB - lastName: ${user?.lastName ?: "NO_LAST_NAME"}")
                    Log.d(TAG, "Firebase DB - email: ${user?.email ?: "NO_EMAIL"}")
                } else {
                    Log.d(TAG, "Firebase DB - user exists: false")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Firebase DB - error reading data", e)
            }
        }
    }

    private fun setupToolbar() {
        lifecycleScope.launch(uiDispatcher) {
            try {
                Log.d(TAG, "setupToolbar: Начало")
                val toolbar = binding.toolbar
                setSupportActionBar(toolbar)

                binding.btnMusic.setOnClickListener {
                    try {
                        startActivity(Intent(this@MainActivity, MusicMainActivity::class.java))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting MusicActivity", e)
                        Toast.makeText(this@MainActivity, "Ошибка открытия музыки", Toast.LENGTH_SHORT).show()
                    }
                }

                binding.btnQuestionnaire.setOnClickListener {
                    showQuestionnairePrompt()
                }

                binding.btnMenu.setOnClickListener { view ->
                    showPopupMenu(view)
                }

                binding.ivUserAvatar.setOnClickListener {
                    try {
                        startActivity(Intent(this@MainActivity, ProfileActivity::class.java))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting ProfileActivity", e)
                        Toast.makeText(this@MainActivity, "Ошибка открытия профиля", Toast.LENGTH_SHORT).show()
                    }
                }

                binding.tvUserName.setOnClickListener {
                    try {
                        startActivity(Intent(this@MainActivity, ProfileActivity::class.java))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting ProfileActivity", e)
                        Toast.makeText(this@MainActivity, "Ошибка открытия профиля", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up toolbar", e)
            }
        }
    }

    private fun updateToolbarUserInfo(user: User) {
        try {
            val fullName = user.getFullName()
            binding.tvUserName.text = fullName

            lifecycleScope.launch(ioDispatcher) {
                val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
                sharedPref.edit().putString("user_name", fullName).apply()

                val firstName = extractFirstName(fullName)
                sharedPref.edit().putString("first_name", firstName).apply()
            }

            user.profileImageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                Glide.with(this)
                    .load(url)
                    .circleCrop()
                    .placeholder(R.drawable.fill)
                    .error(R.drawable.fill)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(binding.ivUserAvatar)
            } ?: run {
                binding.ivUserAvatar.setImageResource(R.drawable.fill)
            }

            Log.d(TAG, "User info updated: $fullName")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating toolbar user info", e)
            binding.ivUserAvatar.setImageResource(R.drawable.fill)
        }
    }

    /**
     * Извлекает только имя (первое слово) из полного имени
     */
    private fun extractFirstName(fullName: String): String {
        return try {
            if (fullName.isBlank()) {
                return "Пользователь"
            }

            val cleanedName = fullName.trim().replace(Regex("\\s+"), " ")
            val nameParts = cleanedName.split(" ")

            when {
                nameParts.isNotEmpty() && nameParts[0].isNotBlank() -> nameParts[0]
                else -> {
                    nameParts.firstOrNull { it.isNotBlank() } ?: "Пользователь"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting first name from: $fullName", e)
            "Пользователь"
        }
    }

    /**
     * Получение имени пользователя для приветствия
     */
    private fun getUserName(): String {
        return try {
            val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)

            var firstName = sharedPref.getString("first_name", null)
            if (!firstName.isNullOrEmpty() && firstName != "Пользователь" && firstName != "NOT_SET") {
                Log.d(TAG, "Using first_name from SharedPreferences: $firstName")
                return firstName
            }

            val fullName = sharedPref.getString("user_name", null)
            if (!fullName.isNullOrEmpty() && fullName != "NOT_SET") {
                val extractedFirstName = extractFirstName(fullName)
                if (extractedFirstName != "Пользователь") {
                    sharedPref.edit().putString("first_name", extractedFirstName).apply()
                    Log.d(TAG, "Extracted first name from user_name: $extractedFirstName")
                    return extractedFirstName
                }
            }

            val currentUser = auth.currentUser
            when {
                currentUser?.displayName?.isNotEmpty() == true -> {
                    val name = extractFirstName(currentUser.displayName!!)
                    sharedPref.edit().putString("first_name", name).apply()
                    Log.d(TAG, "Using name from Auth displayName: $name")
                    name
                }
                currentUser?.email?.isNotEmpty() == true -> {
                    val name = currentUser.email!!.split("@").first()
                    sharedPref.edit().putString("first_name", name).apply()
                    Log.d(TAG, "Using name from Auth email: $name")
                    name
                }
                else -> {
                    Log.w(TAG, "No user name found, using fallback")
                    "Пользователь"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting user name", e)
            "Пользователь"
        }
    }

    /**
     * Показ приветствия с запасным вариантом
     */
    private fun showWelcomeMessageWithFallback() {
        try {
            val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
            val fallbackName = sharedPref.getString("first_name", "Пользователь")

            Log.w(TAG, "Using fallback name: $fallbackName")
            showBasicWelcomeMessage()
        } catch (e: Exception) {
            Log.e(TAG, "Error in showWelcomeMessageWithFallback", e)
            tvWelcomeTitle.text = "Добро пожаловать!"
            tvWelcomeQuestion.text = "Чем увлекаетесь в последнее время?"
            tvWelcomeContext.text = "Давайте продолжим наш разговор!"
            welcomeCard.visibility = View.VISIBLE
        }
    }

    /**
     * Принудительное обновление данных пользователя (для диагностики)
     */
    fun forceRefreshUserData() {
        Log.d(TAG, "Forcing user data refresh")
        lifecycleScope.launch(initDispatcher) {
            loadCurrentUserDataAsync()
        }
    }

    private fun makeSystemBarsTransparent() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.apply {
                    navigationBarColor = Color.parseColor("#10FFFFFF")
                    statusBarColor = Color.parseColor("#10FFFFFF")

                    decorView.systemUiVisibility = (
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        decorView.systemUiVisibility = decorView.systemUiVisibility or
                                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        decorView.systemUiVisibility = decorView.systemUiVisibility or
                                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error making system bars transparent", e)
        }
    }

    private fun handleSystemBarsInsets() {
        try {
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
                val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                val navigationBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

                Log.d(TAG, "Insets: statusBar=$statusBarHeight, navigationBar=$navigationBarHeight")

                binding.toolbar.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    topMargin = statusBarHeight
                }

                insets
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling system bars insets", e)
        }
    }

    private fun checkAndRequestMainPermissions() {
        lifecycleScope.launch(uiDispatcher) {
            try {
                Log.d(TAG, "checkAndRequestMainPermissions: Начало")
                val missingBasicPermissions = basicPermissions.filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missingBasicPermissions.isNotEmpty()) {
                    Log.d(TAG, "Requesting basic permissions: $missingBasicPermissions")
                    basicPermissionLauncher.launch(missingBasicPermissions.toTypedArray())
                } else {
                    Log.d(TAG, "Основные разрешения есть, запрос дополнительных")
                    currentPermissionIndex = 0
                    requestNextAdditionalPermission()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting permissions", e)
                proceedWithMainInitialization()
            }
        }
    }

    private fun requestNextAdditionalPermission() {
        lifecycleScope.launch(uiDispatcher) {
            try {
                if (currentPermissionIndex < additionalPermissions.size) {
                    val nextPermission = additionalPermissions[currentPermissionIndex]
                    if (ContextCompat.checkSelfPermission(this@MainActivity, nextPermission) != PackageManager.PERMISSION_GRANTED &&
                        isPermissionSupported(nextPermission)) {
                        Log.d(TAG, "Requesting additional permission: $nextPermission")
                        additionalPermissionLauncher.launch(nextPermission)
                    } else {
                        Log.d(TAG, "Разрешение $nextPermission уже предоставлено или не поддерживается")
                        currentPermissionIndex++
                        handler.postDelayed({
                            requestNextAdditionalPermission()
                        }, PERMISSION_REQUEST_DELAY)
                    }
                } else {
                    Log.d(TAG, "All permissions processed")
                    proceedWithMainInitialization()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in requestNextAdditionalPermission", e)
                proceedWithMainInitialization()
            }
        }
    }

    private fun isPermissionSupported(permission: String): Boolean {
        return try {
            when (permission) {
                Manifest.permission.ACTIVITY_RECOGNITION ->
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                Manifest.permission.POST_NOTIFICATIONS ->
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                else -> true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permission support", e)
            true
        }
    }

    private fun proceedWithMainInitialization() {
        Log.d(TAG, "Proceeding with main initialization. isFirstLaunch=$isFirstLaunch")
        if (isFirstLaunch) {
            lifecycleScope.launch(initDispatcher) {
                try {
                    withTimeout(SERVICE_INIT_TIMEOUT) {
                        initializeServicesSafely()
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "Инициализация сервисов заняла слишком долго")
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка инициализации сервисов", e)
                }
            }
            isFirstLaunch = false
        }
    }

    fun openChatWithGigaFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, ChatWithGigaFragment())
            .addToBackStack("chat")  // Добавляем в стек для возможности возврата
            .commit()
    }

    /**
     * Обработка кнопки назад
     */
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            super.onBackPressed()
        }
    }

    private suspend fun initializeServicesSafely() {
        Log.d(TAG, "initializeServicesSafely: Начало")

        val locationJob = lifecycleScope.async(ioDispatcher) {
            try {
                withTimeout(SERVICE_INIT_TIMEOUT) {
                    if (hasLocationPermissions()) {
                        startLocationUpdateService()
                        Log.d(TAG, "Сервис локации инициализирован")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Таймаут инициализации сервиса локации", e)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка инициализации сервиса локации", e)
            }
        }

        val stepJob = lifecycleScope.async(ioDispatcher) {
            try {
                withTimeout(SERVICE_INIT_TIMEOUT) {
                    if (hasStepPermissions()) {
                        startStepCounterService()
                        Log.d(TAG, "Сервис шагомера инициализирован")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Таймаут инициализации сервиса шагомера", e)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка инициализации сервиса шагомера", e)
            }
        }

        val quotesJob = lifecycleScope.async(ioDispatcher) {
            try {
                withTimeout(5000L) {
                    startPhilosophyQuotes()
                    Log.d(TAG, "Философские цитаты инициализированы")
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Таймаут инициализации философских цитат", e)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка инициализации философских цитат", e)
            }
        }

        try {
            withTimeout(15000L) {
                locationJob.await()
                stepJob.await()
                quotesJob.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Общий таймаут инициализации сервисов", e)
        }

        lifecycleScope.launch(ioDispatcher) {
            delay(3000)
            try {
                schedulePeriodicStepWork()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка настройки WorkManager", e)
            }
        }

        Log.d(TAG, "initializeServicesSafely: Завершено")
    }

    private fun startPhilosophyQuotes() {
        Log.d(TAG, "startPhilosophyQuotes: Запуск ежечасных философских цитат")

        val request = PeriodicWorkRequestBuilder<PhilosophyQuoteWorker>(
            1, TimeUnit.HOURS,
            15, TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "hourly_philosophy_quote",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )

        Log.d(TAG, "Философские цитаты инициализированы")
    }

    private fun hasLocationPermissions(): Boolean {
        return try {
            val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            hasFine || hasCoarse
        } catch (e: Exception) {
            Log.e(TAG, "Error checking location permissions", e)
            false
        }
    }

    private fun hasStepPermissions(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking step permissions", e)
            false
        }
    }

    private fun startLocationUpdateService() {
        if (isLocationServiceStarting.getAndSet(true)) {
            Log.d(TAG, "Location service start already in progress, skipping")
            return
        }

        lifecycleScope.launch(ioDispatcher) {
            try {
                val serviceIntent = Intent(this@MainActivity, LocationUpdateService::class.java).apply {
                    action = LocationUpdateService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Log.d(TAG, "Location service started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start location service", e)
            } finally {
                handler.postDelayed({
                    isLocationServiceStarting.set(false)
                }, 2000)
            }
        }
    }

    private fun startStepCounterService() {
        if (isStepServiceStarting.getAndSet(true)) {
            Log.d(TAG, "Step service start already in progress, skipping")
            return
        }

        lifecycleScope.launch(ioDispatcher) {
            try {
                StepCounterService.startService(this@MainActivity)
                Log.d(TAG, "Step service started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start step service", e)
            } finally {
                handler.postDelayed({
                    isStepServiceStarting.set(false)
                }, 1000)
            }
        }
    }

    private fun schedulePeriodicStepWork() {
        try {
            Log.d(TAG, "schedulePeriodicStepWork: Начало")
            val workManager = WorkManager.getInstance(this)
            val periodicWorkRequest = PeriodicWorkRequestBuilder<StepCounterServiceWorker>(
                STEP_SERVICE_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()
            workManager.enqueueUniquePeriodicWork(
                STEP_SERVICE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWorkRequest
            )
            Log.d(TAG, "Step WorkManager scheduled successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule step work", e)
        }
    }

    /**
     * Скроллит новости к началу
     */
    private fun scrollNewsToTop() {
        try {
            val homeFragment = supportFragmentManager.findFragmentByTag(HOME_FRAGMENT_TAG) as? HomeFragment
            if (homeFragment != null && homeFragment.isVisible) {
                homeFragment.scrollNewsToTop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling news to top", e)
        }
    }

    private fun logoutUser() {
        lifecycleScope.launch(ioDispatcher) {
            try {
                stopService(Intent(this@MainActivity, LocationUpdateService::class.java))
                stopService(Intent(this@MainActivity, StepCounterService::class.java))

                WorkManager.getInstance(this@MainActivity).apply {
                    cancelUniqueWork(STEP_SERVICE_WORK_NAME)
                    cancelUniqueWork(PHILOSOPHY_QUOTES_WORK_NAME)
                }

                getSharedPreferences("app_prefs", MODE_PRIVATE).edit().clear().apply()
                fragmentCache.clear()
                auth.signOut()

                // Освобождаем TTS при выходе
                ttsManager.release()

                startActivity(Intent(this@MainActivity, AuthActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()

            } catch (e: Exception) {
                Log.e(TAG, "Error during logout", e)
                startActivity(Intent(this@MainActivity, AuthActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
        }
    }

    /**
     * УНИВЕРСАЛЬНЫЙ перезапуск сервисов после краша
     */
    private fun restartServicesAfterCrash() {
        lifecycleScope.launch(ioDispatcher) {
            try {
                val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
                val wasCrash = prefs.getBoolean("was_crash", false)

                if (wasCrash) {
                    Log.w(TAG, "Обнаружен краш приложения, перезапускаем сервисы...")
                    (application as? StepCounterApp)?.restartServicesAfterCrash()
                    prefs.edit().putBoolean("was_crash", false).apply()
                    Log.d(TAG, "Перезапуск сервисов после краша инициирован")
                } else {
                    Log.d(TAG, "Краш не обнаружен, стандартный запуск")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при перезапуске сервисов после краша", e)
            }
        }
    }

    /**
     * Асинхронная проверка статуса трекинга с таймаутом
     */
    private fun checkTrackingStatusAsync() {
        if (isLocationServiceStarting.get()) {
            Log.d(TAG, "checkTrackingStatusAsync: Сервис уже запускается, пропускаем проверку")
            return
        }

        lifecycleScope.launch(ioDispatcher) {
            try {
                withTimeout(TRACKING_CHECK_TIMEOUT) {
                    val isTracking = withContext(ioDispatcher) {
                        try {
                            suspendCoroutine<Boolean> { continuation ->
                                LocationServiceManager.isTrackingActive(this@MainActivity) { isTracking ->
                                    continuation.resume(isTracking)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка проверки трекинга", e)
                            false
                        }
                    }

                    if (isTracking && !isLocationServiceStarting.get()) {
                        Log.d(TAG, "Трекинг активен, запускаем сервис")
                        startLocationUpdateService()
                    } else {
                        Log.d(TAG, "Трекинг не активен или сервис уже запускается")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Проверка трекинга заняла слишком долго, пропускаем")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка проверки трекинга", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        isActivityResumed = true
        wasActivityPaused = false

        Log.d(TAG, "onResume: Activity становится активной")

        // Переинициализируем TTS если было паузирование
        if (wasActivityPaused && !isTTSInitialized) {
            Log.d(TAG, "Reinitializing TTS after pause")
            ttsInitializationAttempts = 0
            initTTSManagerSync()
        }

        // Сбрасываем флаги TTS для повторного озвучивания
        hasGreetingBeenSpoken = false
        hasAIContinuationBeenSpoken = false

        // Если приветствие видно - обновляем и повторно озвучиваем
        if (::welcomeCard.isInitialized && welcomeCard.visibility == View.VISIBLE) {
            handler.postDelayed({
                val currentText = tvWelcomeTitle.text.toString()
                if (currentText.isNotEmpty() && isTTSInitialized) {
                    speakGreeting(currentText)
                }

                // Повторно показываем контекстный вопрос и продолжение
                if (greetingGenerator != null) {
                    startStagedWelcomeSequence()
                }
            }, 1000)
        }

        // ОТЛОЖИТЬ тяжелые операции
        lifecycleScope.launch(uiDispatcher) {
            resumeAppAsync()
        }
    }

    /**
     * Асинхронное возобновление приложения
     */
    private suspend fun resumeAppAsync() = withContext(initDispatcher) {
        try {
            // Обновляем приветствие если нужно (только если виджет видим)
            if (::welcomeCard.isInitialized && welcomeCard.visibility == View.VISIBLE) {
                withContext(uiDispatcher) {
                    handler.postDelayed({
                        updateWelcomeMessageWithProfile()
                    }, 500)
                }
            }

            // Обновляем генератор если профиль изменился
            if (greetingGenerator != null) {
                greetingGenerator = SmartQuestionGenerator(this@MainActivity, userProfile)
            }

            // Запускаем сервисы
            startServicesDirectly()

            // Проверяем статус трекинга
            if (auth.currentUser != null) {
                checkTrackingStatusAsync()
                checkServicesState()
            } else {
                // Пользователь не авторизован
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in async resume", e)
        }
    }

    /**
     * Прямой запуск сервисов (fallback)
     */
    private fun startServicesDirectly() {
        lifecycleScope.launch(ioDispatcher) {
            try {
                Log.d(TAG, "startServicesDirectly: Прямой запуск сервисов...")

                if (hasLocationPermissions()) {
                    Log.d(TAG, "startServicesDirectly: Разрешения есть, запускаем location service")
                    startLocationUpdateService()
                } else {
                    Log.w(TAG, "startServicesDirectly: Нет разрешений на локацию")
                }

                if (hasStepPermissions()) {
                    Log.d(TAG, "startServicesDirectly: Разрешения есть, запускаем step service")
                    startStepCounterService()
                } else {
                    Log.w(TAG, "startServicesDirectly: Нет разрешений на шагомер")
                }

            } catch (e: Exception) {
                Log.e(TAG, "startServicesDirectly: Ошибка прямого запуска сервисов", e)
            }
        }
    }

    /**
     * Проверяет состояние сервисов
     */
    private fun checkServicesState() {
        lifecycleScope.launch(ioDispatcher) {
            try {
                val stepCounterApp = application as? StepCounterApp
                val isStepActive = stepCounterApp?.getServiceState("step") ?: false
                val isLocationActive = stepCounterApp?.getServiceState("location") ?: false

                Log.d(TAG, "checkServicesState: Step service active: $isStepActive, Location service active: $isLocationActive")

                if (!isStepActive && shouldStepServiceBeActive()) {
                    Log.w(TAG, "checkServicesState: Step service должен быть активен но не запущен")
                    startStepCounterService()
                }

                if (!isLocationActive && shouldLocationServiceBeActive()) {
                    Log.w(TAG, "checkServicesState: Location service должен быть активен но не запущен")
                    startLocationUpdateService()
                }

            } catch (e: Exception) {
                Log.e(TAG, "checkServicesState: Ошибка проверки состояния сервисов", e)
            }
        }
    }

    /**
     * Проверяет должен ли быть активен сервис шагомера
     */
    private fun shouldStepServiceBeActive(): Boolean {
        val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("step_service_should_be_active", true)
    }

    /**
     * Проверяет должен ли быть активен сервис локации
     */
    private fun shouldLocationServiceBeActive(): Boolean {
        val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("location_service_should_be_active", false)
    }

    /**
     * Показывает уведомление об ошибке запуска сервисов
     */
    private fun showServiceStartErrorNotification() {
        try {
            Toast.makeText(this, "Ошибка запуска фоновых сервисов", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "showServiceStartErrorNotification: Ошибка показа уведомления", e)
        }
    }

    private fun showErrorAndFinish(message: String) {
        try {
            Log.e(TAG, "showErrorAndFinish: $message")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            lifecycleScope.launch(uiDispatcher) {
                delay(2000)
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "showErrorAndFinish: Критическая ошибка", e)
            finish()
        }
    }

    private fun redirectToAuth() {
        startActivity(Intent(this, AuthActivity::class.java))
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentFragmentTag?.let { tag ->
            outState.putString("current_fragment_tag", tag)
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        savedInstanceState.getString("current_fragment_tag")?.let { tag ->
            currentFragmentTag = tag
        }
    }

    override fun onPause() {
        super.onPause()

        isActivityResumed = false
        wasActivityPaused = true

        // Останавливаем TTS при уходе с экрана
        ttsManager.stop()
        Log.d(TAG, "TTS stopped on pause")
    }

    override fun onDestroy() {
        Log.d(TAG, "MainActivity destroyed")
        // Отменяем все корутины и задачи
        welcomeSequenceJob?.cancel()
        aiAnalysisJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        fragmentCache.clear()
        isLocationServiceStarting.set(false)
        isStepServiceStarting.set(false)

        // Освобождаем ресурсы TTS Manager
        ttsManager.release()

        super.onDestroy()
    }

    // Методы для открытия фрагментов новостей
    fun openCreateNewsFragment() {
        try {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, CreateNewsFragment())
                .addToBackStack("create_news")
                .commitAllowingStateLoss()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening create news fragment", e)
        }
    }

    fun openEditNewsFragment(newsItem: NewsItem) {
        try {
            val fragment = CreateNewsFragment.newInstance(newsItem)
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack("edit_news")
                .commitAllowingStateLoss()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening edit news fragment", e)
        }
    }

    fun openFullScreenImage(imageUrl: String) {
        try {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, FullScreenImageFragment.newInstance(imageUrl))
                .addToBackStack("fullscreen_image")
                .commitAllowingStateLoss()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening full screen image", e)
        }
    }

    fun onNewsCreated() {
        try {
            Toast.makeText(this, "Новость опубликована!", Toast.LENGTH_SHORT).show()
            supportFragmentManager.popBackStack("create_news", 1)
            switchToNewsTab()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling news creation", e)
        }
    }

    private fun switchToNewsTab() {
        try {
            switchToFragment(HOME_FRAGMENT_TAG) { HomeFragment() }
            binding.bottomNavigation.selectedItemId = R.id.nav_home
        } catch (e: Exception) {
            Log.e(TAG, "Error switching to news tab", e)
        }
    }

    /**
     * Показывает существующий фрагмент
     */
    private fun showFragment(tag: String) {
        try {
            supportFragmentManager.fragments.forEach { fragment ->
                if (fragment.tag == tag) {
                    supportFragmentManager.beginTransaction()
                        .show(fragment)
                        .commitAllowingStateLoss()
                } else if (fragment.isVisible) {
                    supportFragmentManager.beginTransaction()
                        .hide(fragment)
                        .commitAllowingStateLoss()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing fragment: $tag", e)
        }
    }

    /**
     * Безопасная загрузка фрагмента
     */
    private fun loadFragment(fragment: Fragment, tag: String) {
        lifecycleScope.launch(uiDispatcher) {
            try {
                // Сначала скрываем все фрагменты
                supportFragmentManager.fragments.forEach { existingFragment ->
                    if (existingFragment.isVisible) {
                        supportFragmentManager.beginTransaction()
                            .hide(existingFragment)
                            .commitAllowingStateLoss()
                    }
                }

                // Проверяем, не добавлен ли уже фрагмент
                val existingFragment = supportFragmentManager.findFragmentByTag(tag)
                if (existingFragment != null) {
                    // Фрагмент уже существует, показываем его
                    supportFragmentManager.beginTransaction()
                        .show(existingFragment)
                        .commitAllowingStateLoss()
                } else {
                    // Добавляем новый фрагмент
                    supportFragmentManager.beginTransaction()
                        .add(R.id.fragment_container, fragment, tag)
                        .commitAllowingStateLoss()
                }

                currentFragmentTag = tag

            } catch (e: Exception) {
                Log.e(TAG, "Error loading fragment: $tag", e)

                // Ultimate fallback - очищаем все и добавляем заново
                try {
                    clearAllFragments()
                    supportFragmentManager.beginTransaction()
                        .add(R.id.fragment_container, fragment, tag)
                        .commitAllowingStateLoss()
                    currentFragmentTag = tag
                } catch (e2: Exception) {
                    Log.e(TAG, "Critical error in fragment loading", e2)
                }
            }
        }
    }

    /**
     * Очищает все фрагменты (аварийный метод)
     */
    private fun clearAllFragments() {
        try {
            supportFragmentManager.fragments.forEach { fragment ->
                supportFragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitAllowingStateLoss()
            }
            fragmentCache.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing fragments", e)
        }
    }

    private fun showPopupMenu(view: View) {
        try {
            // Используем кастомное меню
            showCustomPopupMenu(view)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing popup menu", e)
            // Fallback на стандартное меню
            showStandardPopupMenu(view)
        }
    }

    /**
     * Показывает кастомное всплывающее меню
     */
    private fun showCustomPopupMenu(anchorView: View) {
        try {
            val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val popupView = inflater.inflate(R.layout.popup_menu_layout, null)

            // Загружаем данные пользователя в меню
            loadUserDataToMenu(popupView)

            val popupWindow = PopupWindow(
                popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )

            // Настройка внешнего вида
            setupPopupWindowAppearance(popupWindow)

            // Настройка обработчиков кликов
            setupMenuClickListeners(popupView, popupWindow)

            // Показать меню с анимацией
            showPopupWithAnimation(popupWindow, anchorView)

        } catch (e: Exception) {
            Log.e(TAG, "Error showing custom popup menu", e)
            throw e
        }
    }

    /**
     * Загружает данные пользователя в меню
     */
    private fun loadUserDataToMenu(popupView: View) {
        try {
            val tvProfileName = popupView.findViewById<TextView>(R.id.tv_profile_name)
            val tvProfileEmail = popupView.findViewById<TextView>(R.id.tv_profile_email)
            val ivProfileAvatar = popupView.findViewById<ImageView>(R.id.iv_profile_avatar)

            val currentUser = auth.currentUser
            currentUser?.let { user ->
                // Имя
                tvProfileName.text = user.displayName ?: extractFirstName(getUserName())
                // Email
                tvProfileEmail.text = user.email ?: "Пользователь"

                // Аватар
                loadUserAvatarToMenu(ivProfileAvatar, user.uid)
            } ?: run {
                tvProfileName.text = "Гость"
                tvProfileEmail.text = "Войдите в аккаунт"
                ivProfileAvatar.setImageResource(R.drawable.fill)
            }

            // Бейдж для анкеты (если не заполнена)
            checkAndShowQuestionnaireBadge(popupView)

        } catch (e: Exception) {
            Log.e(TAG, "Error loading user data to menu", e)
        }
    }

    /**
     * Загружает аватар пользователя в меню
     */
    private fun loadUserAvatarToMenu(imageView: ImageView, userId: String) {
        lifecycleScope.launch(ioDispatcher) {
            try {
                val snapshot = Firebase.database.reference
                    .child("users")
                    .child(userId)
                    .get()
                    .await()

                if (snapshot.exists()) {
                    val user = snapshot.getValue(User::class.java)
                    user?.profileImageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        withContext(uiDispatcher) {
                            Glide.with(this@MainActivity)
                                .load(url)
                                .circleCrop()
                                .placeholder(R.drawable.fill)
                                .error(R.drawable.fill)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .into(imageView)
                        }
                    } ?: withContext(uiDispatcher) {
                        imageView.setImageResource(R.drawable.fill)
                    }
                }
            } catch (e: Exception) {
                withContext(uiDispatcher) {
                    imageView.setImageResource(R.drawable.fill)
                }
            }
        }
    }

    /**
     * Проверяет и показывает бейдж анкеты
     */
    private fun checkAndShowQuestionnaireBadge(popupView: View) {
        val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val questionnaireCompleted = sharedPref.getBoolean("questionnaire_completed", false)

        val badge = popupView.findViewById<TextView>(R.id.badge_questionnaire)
        badge.visibility = if (questionnaireCompleted) View.GONE else View.VISIBLE
    }

    /**
     * Настраивает обработчики кликов меню
     */
    private fun setupMenuClickListeners(popupView: View, popupWindow: PopupWindow) {
        // Профиль
        popupView.findViewById<View>(R.id.profile_section).setOnClickListener {
            animateMenuItemClick(it)
            startActivity(Intent(this, ProfileActivity::class.java))
            popupWindow.dismiss()
        }

        // Анкета
        popupView.findViewById<View>(R.id.questionnaire_item).setOnClickListener {
            animateMenuItemClick(it)
            startUserQuestionnaireActivity()
            popupWindow.dismiss()
        }

        // Лотерея
        popupView.findViewById<View>(R.id.games_item).setOnClickListener {
            animateMenuItemClick(it)
            switchToLotteryFragment()
            popupWindow.dismiss()
        }

        // Мозг тест
        popupView.findViewById<View>(R.id.mozgi_item).setOnClickListener {
            animateMenuItemClick(it)
            startActivity(Intent(this, CategoriesActivity::class.java))
            popupWindow.dismiss()
        }

        // Игра Блоки
        popupView.findViewById<View>(R.id.blocks_item).setOnClickListener {
            animateMenuItemClick(it)
            startActivity(Intent(this, BlockGameActivity::class.java))
            popupWindow.dismiss()
        }

        // Будильник
        popupView.findViewById<View>(R.id.alarm_item).setOnClickListener {
            animateMenuItemClick(it)
            startActivity(Intent(this, AlarmActivity::class.java))
            popupWindow.dismiss()
        }

        // Таймер
        popupView.findViewById<View>(R.id.timer_item).setOnClickListener {
            animateMenuItemClick(it)
            startActivity(Intent(this, TimerActivity::class.java))
            popupWindow.dismiss()
        }

        // Выход
        popupView.findViewById<View>(R.id.logout_item).setOnClickListener {
            animateMenuItemClick(it)
            popupWindow.dismiss()
            Handler(Looper.getMainLooper()).postDelayed({
                showLogoutConfirmationDialog()
            }, 200)
        }
    }

    /**
     * Анимация нажатия пункта меню
     */
    private fun animateMenuItemClick(view: View) {
        view.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }

    /**
     * Показывает меню с анимацией
     */
    private fun showPopupWithAnimation(popupWindow: PopupWindow, anchorView: View) {
        // Вычисляем позицию
        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)

        val screenWidth = resources.displayMetrics.widthPixels
        val popupWidth = popupWindow.width

        // Центрируем относительно кнопки меню
        val anchorCenterX = location[0] + anchorView.width / 2
        val x = anchorCenterX - popupWidth / 2

        // Сдвигаем если выходит за экран
        val adjustedX = when {
            x < 16.dpToPx() -> 16.dpToPx()
            x + popupWidth > screenWidth - 16.dpToPx() ->
                screenWidth - popupWidth - 16.dpToPx()
            else -> x
        }

        // Показываем с анимацией
        popupWindow.showAtLocation(
            anchorView,
            Gravity.NO_GRAVITY,
            adjustedX,
            location[1] + anchorView.height + 8.dpToPx()
        )

        // Анимация появления
        popupView?.apply {
            scaleX = 0.85f
            scaleY = 0.85f
            alpha = 0f
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(250)
                .setInterpolator(OvershootInterpolator(0.9f))
                .start()
        }
    }

    /**
     * Fallback на стандартное меню
     */
    private fun showStandardPopupMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

        // Применяем стиль
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            popup.setForceShowIcon(true)
        }

        popup.setOnMenuItemClickListener { item ->
            handleMenuItemClick(item)
            true
        }

        popup.show()
    }

    /**
     * Показывает диалог подтверждения выхода
     */
    private fun showLogoutConfirmationDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Выход")
            .setMessage("Вы уверены, что хотите выйти из аккаунта?")
            .setPositiveButton("Выйти") { _, _ ->
                logoutUser()
            }
            .setNegativeButton("Отмена", null)
            .setCancelable(true)
            .show()
    }



    /**
     * Настраивает внешний вид PopupWindow
     */
    private fun setupPopupWindowAppearance(popupWindow: PopupWindow) {
        // Фон
        popupWindow.setBackgroundDrawable(
            ContextCompat.getDrawable(this, R.drawable.popup_menu_background)
        )

        // Анимация
        popupWindow.animationStyle = R.style.PopupAnimation

        // Элевация и тень
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popupWindow.elevation = 32f
            popupWindow.isClippingEnabled = true
        }

        // Закрытие при клике вне меню
        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true

        // Ширина - адаптивная, но не слишком широкая
        val screenWidth = resources.displayMetrics.widthPixels
        val maxWidth = (screenWidth * 0.9).toInt()
        val minWidth = 320.dpToPx()
        val preferredWidth = (screenWidth - 32.dpToPx()).coerceAtMost(400.dpToPx())

        popupWindow.width = preferredWidth.coerceIn(minWidth, maxWidth)
    }

    /**
     * Конвертация dp в px
     */
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    /**
     * Обрабатывает клики по пунктам стандартного меню
     */
    private fun handleMenuItemClick(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_profile -> {
                try {
                    startActivity(Intent(this, ProfileActivity::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting ProfileActivity", e)
                }
            }
            R.id.menu_questionnaire -> {
                startUserQuestionnaireActivity()
            }
            R.id.menu_mozgi -> {
                try {
                    startActivity(Intent(this, CategoriesActivity::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting CategoriesActivity", e)
                }
            }
            R.id.menu_alarm -> {
                try {
                    startActivity(Intent(this, AlarmActivity::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting AlarmActivity", e)
                }
            }
            R.id.menu_blocks -> {
                try {
                    startActivity(Intent(this@MainActivity, BlockGameActivity::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting BlockGameActivity", e)
                    Toast.makeText(this@MainActivity, "Ошибка открытия игры Блоки", Toast.LENGTH_SHORT).show()
                }
            }
            R.id.menu_lottery -> {
                try {
                    switchToLotteryFragment()
                } catch (e: Exception) {
                    Log.e(TAG, "Error switching to lottery fragment", e)
                    Toast.makeText(this, "Ошибка открытия лотереи", Toast.LENGTH_SHORT).show()
                }
            }
            R.id.menu_timer -> {
                try {
                    startActivity(Intent(this@MainActivity, TimerActivity::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting TimerActivity", e)
                    Toast.makeText(this@MainActivity, "Ошибка открытия таймера", Toast.LENGTH_SHORT).show()
                }
            }
            R.id.menu_logout -> {
                showLogoutConfirmationDialog()
            }
        }
    }

    // Сохраняем ссылку на popupView для анимации
    private var popupView: View? = null

    /**
     * Обновленный setupBottomNavigation с безопасной навигацией
     */
    private fun setupBottomNavigation() {
        lifecycleScope.launch(uiDispatcher) {
            try {
                bottomNav = binding.bottomNavigation

                bottomNav.background = ContextCompat.getDrawable(this@MainActivity, R.drawable.transparent_nav_background)
                bottomNav.elevation = 8f
                bottomNav.itemIconTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.bg_message_right)
                bottomNav.itemTextColor = ContextCompat.getColorStateList(this@MainActivity, R.color.bg_message_right)

                bottomNav.setOnItemSelectedListener { item ->
                    when (item.itemId) {
                        R.id.nav_home -> {
                            safeSwitchToFragment(HOME_FRAGMENT_TAG) { HomeFragment() }
                            true
                        }
                        R.id.nav_gigachat -> {
                            safeSwitchToFragment(CHAT_FRAGMENT_TAG) { ChatWithGigaFragment() }
                            true
                        }
                        R.id.nav_steps -> {
                            safeSwitchToFragment(STEPS_FRAGMENT_TAG) { StepCounterFragment() }
                            true
                        }
                        R.id.nav_maps -> {
                            safeSwitchToFragment(MAPS_FRAGMENT_TAG) { LocationPagerFragment() }
                            true
                        }
                        R.id.nav_games -> {
                            safeSwitchToFragment(GAMES_FRAGMENT_TAG) { GamesFragment() }
                            true
                        }
                        else -> false
                    }
                }

                bottomNav.setOnItemReselectedListener { item ->
                    when (item.itemId) {
                        R.id.nav_home -> scrollNewsToTop()
                    }
                }

                Log.d(TAG, "Bottom navigation setup completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up bottom navigation", e)
            }
        }
    }

    /**
     * Сверхбезопасное переключение фрагментов с задержкой
     */
    private fun safeSwitchToFragment(tag: String, fragmentFactory: () -> Fragment) {
        handler.postDelayed({
            lifecycleScope.launch(uiDispatcher) {
                try {
                    switchToFragment(tag, fragmentFactory)
                } catch (e: Exception) {
                    Log.e(TAG, "Safe fragment switch failed for: $tag", e)
                    // Последняя попытка
                    try {
                        clearAllFragments()
                        loadFragment(fragmentFactory(), tag)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Critical fragment error", e2)
                    }
                }
            }
        }, 50) // Небольшая задержка для стабильности
    }

    /**
     * Логирование производительности операций
     */
    private fun logPerformance(operation: String, startTime: Long) {
        val duration = System.currentTimeMillis() - startTime
        if (duration > 16) { // Больше одного кадра
            Log.w(TAG, "Slow operation: $operation took ${duration}ms")
        } else {
            Log.d(TAG, "Operation: $operation took ${duration}ms")
        }
    }
}