package com.example.chatapp.activities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
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
import com.example.chatapp.budilnik.AlarmActivity
import com.example.chatapp.databinding.ActivityMainBinding
import com.example.chatapp.fragments.*
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
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var bottomNav: BottomNavigationView
    private var isFirstLaunch = true
    private var currentPermissionIndex = 0

    // Переменные для приветственного виджета
    private lateinit var welcomeCard: CardView
    private lateinit var tvWelcomeTitle: TextView
    private lateinit var tvWelcomeQuestion: TextView
    private lateinit var tvWelcomeContext: TextView
    private lateinit var btnStartChat: Button
    private lateinit var btnMaybeLater: Button
    private lateinit var btnCloseWelcome: ImageButton

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

    // Диспетчеры с ограниченным параллелизмом
    private val initDispatcher = Dispatchers.Default.limitedParallelism(2)
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val uiDispatcher = Dispatchers.Main.immediate

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
        private const val TAG = "MainActivity"
        private const val HOME_FRAGMENT_TAG = "home_fragment"
        private const val CHAT_FRAGMENT_TAG = "chat_fragment"
        private const val STEPS_FRAGMENT_TAG = "steps_fragment"
        private const val MAPS_FRAGMENT_TAG = "maps_fragment"
        private const val GAMES_FRAGMENT_TAG = "games_fragment"

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
        private const val WELCOME_STAGE_2_DELAY = 1000L
        private const val WELCOME_STAGE_3_DELAY = 2000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            val startTime = System.currentTimeMillis()
            setupExceptionHandler()
            makeSystemBarsTransparent()

            auth = Firebase.auth
            if (auth.currentUser == null) {
                Log.d(TAG, "onCreate: Пользователь не авторизован, переход к AuthActivity")
                redirectToAuth()
                return
            }

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            WindowCompat.setDecorFitsSystemWindows(window, false)

            // БЫСТРАЯ инициализация только критически важных элементов
            setupCriticalUI()

            // Отложить тяжелые операции
            lifecycleScope.launch(uiDispatcher) {
                initializeAppAsync()
                logPerformance("onCreate completion", startTime)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate", e)
            showErrorAndFinish("Ошибка запуска приложения")
        }
    }

    /**
     * Быстрая инициализация только критически важных UI элементов
     */
    private fun setupCriticalUI() {
        try {
            handleSystemBarsInsets()
            initWelcomeWidget()
            setupBasicClickListeners()

            // Загрузить начальный фрагмент асинхронно
            lifecycleScope.launch(uiDispatcher) {
                loadInitialFragment()
            }

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
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing welcome widget", e)
        }
    }

    /**
     * Быстрая настройка слушателей
     */
    private fun setupBasicClickListeners() {
        btnStartChat.setOnClickListener {
            lifecycleScope.launch(uiDispatcher) {
                switchToChatAsync()
            }
        }

        btnMaybeLater.setOnClickListener { hideWelcomeMessage() }
        btnCloseWelcome.setOnClickListener { hideWelcomeMessage() }
    }

    /**
     * Загрузка начального фрагмента
     */
    private suspend fun loadInitialFragment() = withContext(uiDispatcher) {
        try {
            if (supportFragmentManager.findFragmentByTag(HOME_FRAGMENT_TAG) == null) {
                // Использовать commitNow для немедленного добавления
                supportFragmentManager.commitNow {
                    add(R.id.fragment_container, HomeFragment(), HOME_FRAGMENT_TAG)
                }
                binding.bottomNavigation.selectedItemId = R.id.nav_home
                currentFragmentTag = HOME_FRAGMENT_TAG
            } else {

            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading initial fragment", e)
        }
    }

    private fun setupExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
                val editor = prefs.edit()

                // Сохраняем текущий фрагмент для восстановления
                editor.putString("last_fragment_tag", currentFragmentTag)
                editor.putBoolean("was_crash", true)
                editor.putLong("last_crash_time", System.currentTimeMillis())
                editor.apply()

                Log.e(TAG, "Uncaught exception, saving crash state. Current fragment: $currentFragmentTag", throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Error in exception handler", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * Восстанавливает состояние фрагментов после краша
     */
    private fun restoreFragmentsAfterCrash() {
        lifecycleScope.launch(ioDispatcher) {
            try {
                delay(1000) // Даем время на восстановление

                val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
                val wasCrash = prefs.getBoolean("was_crash", false)

                if (wasCrash) {
                    Log.w(TAG, "Восстанавливаем фрагменты после краша...")
                    withContext(uiDispatcher) {
                        clearAllFragments()

                        // Восстанавливаем домашний фрагмент
                        safeSwitchToFragment(HOME_FRAGMENT_TAG) { HomeFragment() }
                        binding.bottomNavigation.selectedItemId = R.id.nav_home

                        prefs.edit().putBoolean("was_crash", false).apply()
                        Log.d(TAG, "Фрагменты восстановлены после краша")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка восстановления фрагментов", e)
            }
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

            // Параллельная загрузка критически важных данных
            val criticalTasks = listOf(
                async { loadUserProfileAsync() },
                async { loadCurrentUserDataAsync() }
            )

            // Фоновые задачи (менее важные)
            val backgroundTasks = listOf(
                async { initializeBackgroundServices() },
                async { loadAdditionalData() }
            )

            // Сначала ждем критические задачи
            val criticalResults = criticalTasks.awaitAll()
            val userProfile = criticalResults[0] as? UserProfile
            val userDataLoaded = criticalResults[1] as? Boolean ?: false

            // Инициализируем генераторы в фоне
            val analyzer = withContext(initDispatcher) {
                SmartContextAnalyzer(this@MainActivity.applicationContext)
            }
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
                setupBottomNavigation()

                // Запускаем поэтапное приветствие с задержками
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
            backgroundTasks.awaitAll()

            // Отладочная информация о пользователе после загрузки
            handler.postDelayed({
                debugUserDataExtended()
            }, 3000)

            logPerformance("App initialization", startTime)

        } catch (e: Exception) {
            Log.e(TAG, "Error in optimized async initialization", e)
            withContext(uiDispatcher) {
                hideLoadingProgress()
                showBasicWelcomeMessage()
            }
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
            // Можно добавить здесь загрузку кэша, аналитику и т.д.
        } catch (e: Exception) {
            Log.e(TAG, "Error loading additional data", e)
        }
    }

    /**
     * Показывает мгновенное базовое приветствие (1-я часть)
     */
    private fun showInstantBasicGreeting() {
        try {
            val userName = getUserName()
            val greeting = getTimeBasedGreeting()
            tvWelcomeTitle.text = "$greeting, $userName!"
            tvWelcomeQuestion.text = "Формирую вопрос на основе ваших интересов..."
            tvWelcomeContext.text = "Анализирую наши предыдущие обсуждения..."
            welcomeCard.visibility = View.VISIBLE
            Log.d(TAG, "Instant basic greeting shown")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing instant greeting", e)
        }
    }

    /**
     * Запускает поэтапное отображение приветствия с задержками
     */
    private fun startStagedWelcomeSequence() {
        try {
            if (isWelcomeSequenceRunning) {
                Log.d(TAG, "Welcome sequence already running, skipping")
                return
            }

            isWelcomeSequenceRunning = true
            Log.d(TAG, "Starting staged welcome sequence")

            // Отменяем предыдущую последовательность если есть
            welcomeSequenceJob?.cancel()

            welcomeSequenceJob = lifecycleScope.launch(uiDispatcher) {
                try {
                    // 1. Мгновенно - базовое приветствие (уже показано)

                    // 2. Через 1 секунду - контекстный вопрос из анкеты
                    delay(WELCOME_STAGE_2_DELAY)
                    showSecondStageQuestion()

                    // 3. Через 2 секунды - анализ диалога
                    delay(WELCOME_STAGE_3_DELAY - WELCOME_STAGE_2_DELAY)
                    showThirdStageDialogAnalysis()

                } catch (e: CancellationException) {
                    Log.d(TAG, "Welcome sequence cancelled")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in staged welcome sequence", e)
                    showBasicWelcomeMessage()
                } finally {
                    isWelcomeSequenceRunning = false
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting staged welcome sequence", e)
            showBasicWelcomeMessage()
        }
    }

    /**
     * Вторая стадия приветствия - контекстный вопрос из анкеты
     */
    private fun showSecondStageQuestion() {
        lifecycleScope.launch(initDispatcher) {
            try {
                // Генерируем вопрос на основе анкеты
                val contextQuestion = generateContextualQuestionFromProfile()

                withContext(uiDispatcher) {
                    tvWelcomeQuestion.text = contextQuestion
                    Log.d(TAG, "Second stage question shown: $contextQuestion")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating contextual question", e)
                withContext(uiDispatcher) {
                    tvWelcomeQuestion.text = "Чем увлекаетесь в последнее время?"
                }
            }
        }
    }

    /**
     * Третья стадия приветствия - анализ диалога (продолжение темы)
     */
    private fun showThirdStageDialogAnalysis() {
        lifecycleScope.launch(initDispatcher) {
            try {
                Log.d(TAG, "Analyzing previous dialogs for context")

                // Анализируем историю диалогов
                val dialogAnalysis = analyzePreviousDialogs()

                // Сохраняем для чата: Приветствие + (Анализ диалога ИЛИ Вопрос из анкеты)
                val completePhrase = if (!dialogAnalysis.isNullOrEmpty()) {
                    "${tvWelcomeTitle.text} $dialogAnalysis"
                } else {
                    "${tvWelcomeTitle.text} ${tvWelcomeQuestion.text}"
                }

                saveCompleteWelcomePhraseForChat(completePhrase)

                withContext(uiDispatcher) {
                    tvWelcomeContext.text = dialogAnalysis ?: "Давайте продолжим наш разговор!"
                    Log.d(TAG, "Third stage dialog analysis shown: ${dialogAnalysis ?: "fallback"}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing dialog", e)
                withContext(uiDispatcher) {
                    tvWelcomeContext.text = "Давайте продолжим наш разговор!"

                    // Сохраняем приветствие + вопрос из анкеты как fallback
                    val completePhrase = "${tvWelcomeTitle.text} ${tvWelcomeQuestion.text}"
                    saveCompleteWelcomePhraseForChat(completePhrase)
                }
            }
        }
    }

    /**
     * Генерирует вопрос на основе анкеты пользователя
     */
    private suspend fun generateContextualQuestionFromProfile(): String = withContext(initDispatcher) {
        return@withContext try {
            userProfile?.let { profile ->
                when {
                    profile.occupation.isNotEmpty() -> "Как продвигается работа в сфере ${profile.occupation}?"
                    profile.hobbies.isNotEmpty() -> "Удалось позаниматься ${profile.getHobbiesList().firstOrNull()}?"
                    profile.hasChildren -> "Как дела у детей?"
                    profile.fitnessLevel.isNotEmpty() && profile.fitnessLevel != "Не занимаюсь спортом" ->
                        "Как ваши тренировки? Удалось позаниматься сегодня?"
                    profile.musicPreferences.isNotEmpty() ->
                        "Слушали что-то интересное из ${profile.musicPreferences}?"
                    else -> "Что нового в вашей жизни?"
                }
            } ?: "Чем увлекаетесь в последнее время?"
        } catch (e: Exception) {
            "Как ваши дела?"
        }
    }

    /**
     * Анализирует предыдущие диалоги для продолжения темы
     */
    private suspend fun analyzePreviousDialogs(): String? = withContext(initDispatcher) {
        return@withContext try {
            val analyzer = contextAnalyzer ?: return@withContext null
            val deepContext = analyzer.analyzeDeepContext()

            // Ищем незавершенные обсуждения
            deepContext.pendingDiscussions.firstOrNull()?.let { discussion ->
                when (discussion.type) {
                    "natural_continuation" -> "Мы обсуждали ${discussion.topic}... Хотите продолжить?"
                    "unanswered_question" -> "Вы спрашивали про ${discussion.topic}... Удалось разобраться?"
                    else -> "Давайте вернемся к нашей беседе о ${discussion.topic}"
                }
            } ?: deepContext.activeTopics.firstOrNull()?.let { topic ->
                if (topic.weight > 1.0) {
                    "Мы недавно говорили о ${topic.name}... Есть что-то новое?"
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Сохраняет ПОЛНОЕ приветствие для передачи в чат
     */
    private fun saveCompleteWelcomePhraseForChat(completePhrase: String) {
        lifecycleScope.launch(ioDispatcher) {
            try {
                val sharedPref = getSharedPreferences("chat_prefs", MODE_PRIVATE)
                sharedPref.edit().putString("complete_welcome_phrase", completePhrase).apply()
                Log.d(TAG, "Complete welcome phrase saved for chat: $completePhrase")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving complete welcome phrase", e)
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
     * Асинхронная загрузка профиля пользователя
     */
    private suspend fun loadUserProfileAsync(): UserProfile? = withContext(ioDispatcher) {
        val currentUser = Firebase.auth.currentUser ?: return@withContext null

        try {
            val snapshot = Firebase.database.reference
                .child("user_profiles")
                .child(currentUser.uid)
                .get()
                .await()

            if (snapshot.exists()) {
                val profile = snapshot.getValue(UserProfile::class.java)
                Log.d(TAG, "User profile loaded: ${profile != null}")
                return@withContext profile
            } else {
                Log.d(TAG, "No user profile found")
                createBasicUserProfile(currentUser.uid)
                return@withContext UserProfile(userId = currentUser.uid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user profile", e)
            return@withContext null
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

        try {
            val snapshot = Firebase.database.reference
                .child("users")
                .child(currentUserId)
                .get()
                .await()

            if (snapshot.exists()) {
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
                return@withContext true
            } else {
                loadUserNameFromAuthAsync()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user data", e)
            loadUserNameFromAuthAsync()
            return@withContext false
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
     * Показывает базовое приветствие (fallback)
     */
    private fun showBasicWelcomeMessage() {
        val userName = getUserName()
        val greeting = getTimeBasedGreeting()
        tvWelcomeTitle.text = "$greeting, $userName!"
        tvWelcomeQuestion.text = "Чем увлекаетесь в последнее время?"
        tvWelcomeContext.text = "Давайте продолжим наш разговор!"
        welcomeCard.visibility = View.VISIBLE
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
     * Скрытие приветственного сообщения
     */
    private fun hideWelcomeMessage() {
        try {
            // Отменяем последовательность приветствия
            welcomeSequenceJob?.cancel()
            isWelcomeSequenceRunning = false

            welcomeCard.visibility = View.GONE
            Log.d(TAG, "Welcome message hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding welcome message", e)
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
                    .placeholder(R.drawable.ic_default_profile)
                    .error(R.drawable.ic_default_profile)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(binding.ivUserAvatar)
            } ?: run {
                binding.ivUserAvatar.setImageResource(R.drawable.ic_default_profile)
            }

            Log.d(TAG, "User info updated: $fullName")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating toolbar user info", e)
            binding.ivUserAvatar.setImageResource(R.drawable.ic_default_profile)
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

    private fun showPopupMenu(view: View) {
        try {
            val popup = PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_profile -> {
                        try {
                            startActivity(Intent(this, ProfileActivity::class.java))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting ProfileActivity", e)
                        }
                        true
                    }
                    R.id.menu_questionnaire -> {
                        startUserQuestionnaireActivity()
                        true
                    }
                    R.id.menu_mozgi -> {
                        try {
                            startActivity(Intent(this, CategoriesActivity::class.java))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting CategoriesActivity", e)
                        }
                        true
                    }
                    R.id.menu_alarm -> {
                        try {
                            startActivity(Intent(this, AlarmActivity::class.java))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting AlarmActivity", e)
                        }
                        true
                    }
                    R.id.menu_logout -> {
                        logoutUser()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing popup menu", e)
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

        Log.d(TAG, "onResume: Activity становится активной")

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

    override fun onDestroy() {
        Log.d(TAG, "MainActivity destroyed")
        // Отменяем все корутины и задачи
        welcomeSequenceJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        fragmentCache.clear()
        isLocationServiceStarting.set(false)
        isStepServiceStarting.set(false)
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
     * Оптимизированное переключение фрагментов с проверкой
     */
    private fun switchToFragment(tag: String, fragmentFactory: () -> Fragment) {
        if (currentFragmentTag == tag) return

        lifecycleScope.launch(uiDispatcher) {
            try {
                val fragment = supportFragmentManager.findFragmentByTag(tag)

                if (fragment != null && fragment.isAdded) {
                    // Фрагмент уже добавлен, просто показываем его
                    showFragment(tag)
                } else {
                    // Создаем новый фрагмент
                    val newFragment = fragmentFactory()
                    loadFragment(newFragment, tag)
                }

                currentFragmentTag = tag

            } catch (e: Exception) {
                Log.e(TAG, "Error switching to fragment: $tag", e)
                // Fallback: простая замена
                loadFragment(fragmentFactory(), tag)
            }
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
     * Асинхронный переход к чату
     */
    private suspend fun switchToChatAsync() = withContext(uiDispatcher) {
        Log.d(TAG, "Start chat clicked")
        hideWelcomeMessage()
        saveLastChatTime()

        // Берем ПОЛНОЕ сохраненное приветствие
        val completeWelcomePhrase = withContext(ioDispatcher) {
            val sharedPref = getSharedPreferences("chat_prefs", MODE_PRIVATE)
            sharedPref.getString("complete_welcome_phrase", "Привет! Рад вас видеть! Чем могу помочь?")
        }

        // Сохраняем полную фразу для чата
        saveWelcomePhraseForChat(completeWelcomePhrase ?: "Привет! Рад вас видеть! Чем могу помочь?")
        safeSwitchToFragment(CHAT_FRAGMENT_TAG) { ChatWithGigaFragment() }
        binding.bottomNavigation.selectedItemId = R.id.nav_gigachat
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