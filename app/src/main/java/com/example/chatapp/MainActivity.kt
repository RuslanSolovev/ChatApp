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
import android.graphics.Outline
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
import com.example.chatapp.zametki.ui.main.NotesActivity
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

class MainActivity : AppCompatActivity() {
    lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var bottomNav: BottomNavigationView
    private var isFirstLaunch = true
    private var currentPermissionIndex = 0
    private var isAppInitialized = false

    // Welcome Manager
    private lateinit var welcomeManager: WelcomeManager
    lateinit var welcomeCard: CardView // Доступен извне для WelcomeManager

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

    // TTS (Text-to-Speech) переменные
    private lateinit var ttsManager: TTSManager
    private var isTTSInitialized = false
    private var ttsInitializationAttempts = 0
    private val MAX_TTS_INIT_ATTEMPTS = 3

    var isUserNameLoaded = false
    private var cachedUserName = "Пользователь"

    // Переменные для отслеживания состояния приложения
    private var isActivityResumed = false
    private var wasActivityPaused = false

    // Переменные для управления навигацией
    private var isTopNavigationHidden = false
    private var isInFullscreenMode = false
    private var systemUiFlagsBeforeFullscreen = 0
    private var decorViewSystemUiVisibilityBefore = 0

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
        lifecycleScope.launch(Dispatchers.Main) {
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
        lifecycleScope.launch(Dispatchers.Main) {
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
        const val HOME_FRAGMENT_TAG = "home_fragment"
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

        // TTS задержки
        private const val TTS_INIT_DELAY = 1500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            // 1. Инициализация ViewBinding и установка контента
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // 2. Настройка Toolbar
            setSupportActionBar(binding.toolbar)
            supportActionBar?.apply {
                setTitle(null)
                setDisplayShowTitleEnabled(false)
                setDisplayShowCustomEnabled(true)
            }

            // 3. Настройка системных UI флагов
            systemUiFlagsBeforeFullscreen = window.decorView.systemUiVisibility
            decorViewSystemUiVisibilityBefore = window.decorView.systemUiVisibility

            // 4. Настройка полноэкранного режима
            setupFullscreenSystemUI()

            // 5. Загрузка имени пользователя
            lifecycleScope.launch(Dispatchers.Main) {
                loadUserNameFirst()
            }

            // 6. Инициализация TTS Manager
            initTTSManagerSync()

            // 7. Активация кнопок и навигации
            enableAllButtonsImmediately()

            // 8. Настройка экстренной навигации
            setupEmergencyNavigation()

            // 9. Загрузка начального фрагмента
            loadInitialFragmentFast()

            // 10. Базовая инициализация UI
            try {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                setupBottomNavigation()
                Log.d(TAG, "Critical UI setup completed")

                // Дополнительная проверка Toolbar
                Log.d(TAG, "Toolbar title: ${supportActionBar?.title}")

            } catch (e: Exception) {
                Log.e(TAG, "Error in critical UI setup", e)
            }

            // 11. Асинхронная проверка авторизации
            lifecycleScope.launch(Dispatchers.Main) {
                checkAuthAsync()
            }

            // 12. Настройка прозрачных панелей
            lifecycleScope.launch(Dispatchers.Main) {
                makeSystemBarsTransparent()
                handleSystemBarsInsets()
            }

            // 13. Инициализация менеджера приветствия
            welcomeManager = WelcomeManager(this, binding).apply {
                setupWelcomeCard()
                setTTSManager(ttsManager)
            }

            // 14. Отложенное приветствие
            handler.postDelayed({
                welcomeManager.showInstantBasicGreeting()
            }, 500)

            // 15. Установка слушателей
            handler.postDelayed({
                setupBasicClickListeners()
            }, 700)

        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate", e)
            showEmergencyUI()
        }
    }


    /**
     * Загружаем имя пользователя В ПЕРВУЮ ОЧЕРЕДЬ
     */
    private suspend fun loadUserNameFirst() = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "Loading user name FIRST...")

            // Сначала пробуем получить из кэша
            val cachedName = loadUserNameFromCache()
            if (cachedName != null && cachedName != "Пользователь") {
                cachedUserName = cachedName
                binding.tvUserName.text = cachedName
                isUserNameLoaded = true
                Log.d(TAG, "Using cached user name: $cachedName")
                return@withContext
            }

            // Проверяем авторизацию
            val currentUser = auth.currentUser ?: Firebase.auth.currentUser
            if (currentUser != null) {
                // Пробуем разные источники
                val userName = when {
                    // 1. Имя из Firebase Database (самое надежное)
                    currentUser.uid.isNotEmpty() -> {
                        loadUserNameFromDatabase(currentUser.uid)
                    }

                    // 2. Имя из Auth displayName
                    currentUser.displayName?.isNotEmpty() == true -> {
                        extractFirstName(currentUser.displayName!!)
                    }

                    // 3. Имя из email
                    currentUser.email?.isNotEmpty() == true -> {
                        currentUser.email!!.split("@").first()
                    }

                    else -> "Пользователь"
                }

                cachedUserName = userName
                binding.tvUserName.text = userName

                // Кэшируем для следующего запуска
                saveUserNameToCache(userName)

                isUserNameLoaded = true
                Log.d(TAG, "User name loaded: $userName")
            } else {
                // Пользователь не авторизован
                cachedUserName = "Гость"
                binding.tvUserName.text = "Гость"
                isUserNameLoaded = true
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading user name first", e)
            // Устанавливаем fallback
            cachedUserName = "Пользователь"
            binding.tvUserName.text = "Пользователь"
            isUserNameLoaded = true
        }
    }

    /**
     * Загружает имя пользователя из базы данных
     */
    private suspend fun loadUserNameFromDatabase(userId: String): String = withContext(Dispatchers.IO) {
        return@withContext try {
            // Пытаемся получить из Firebase Database
            val snapshot = withTimeoutOrNull(2000L) {
                Firebase.database.reference
                    .child("users")
                    .child(userId)
                    .get()
                    .await()
            }

            if (snapshot != null && snapshot.exists()) {
                val user = snapshot.getValue(User::class.java)
                when {
                    user?.name?.isNotEmpty() == true -> {
                        val firstName = extractFirstName(user.name!!)
                        if (firstName != "Пользователь") {
                            return@withContext firstName
                        }
                    }
                    user?.getFullName()?.isNotEmpty() == true -> {
                        val firstName = extractFirstName(user.getFullName())
                        if (firstName != "Пользователь") {
                            return@withContext firstName
                        }
                    }
                }
            }

            // Fallback
            "Пользователь"

        } catch (e: Exception) {
            Log.e(TAG, "Error loading name from database", e)
            "Пользователь"
        }
    }




    /**
     * Скроллит новости к началу с задержкой для стабилизации layout
     */
    private fun scrollNewsToTop() {
        try {
            val homeFragment = supportFragmentManager.findFragmentByTag(HOME_FRAGMENT_TAG) as? HomeFragment
            if (homeFragment != null && homeFragment.isVisible) {
                // Даем небольшую задержку перед скроллингом
                handler.postDelayed({
                    homeFragment.scrollNewsToTop()
                }, 150) // Увеличиваем задержку для стабилизации
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling news to top", e)
        }
    }

    override fun onBackPressed() {
        // Если видна приветственная карточка - скрываем ее
        if (welcomeManager.isWelcomeCardVisible()) {
            welcomeManager.hideWelcomeMessage()

            // Даем фокус HomeFragment после скрытия
            handler.postDelayed({
                val homeFragment = supportFragmentManager.findFragmentByTag(HOME_FRAGMENT_TAG) as? HomeFragment
                homeFragment?.requestFocusForNewsList()
            }, 200)

            return
        }

        // Стандартная обработка
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            super.onBackPressed()
        }
    }



    private suspend fun switchToChatAsync() = withContext(Dispatchers.Main) {
        Log.d(TAG, "Start chat clicked")
        if (!isNetworkAvailable()) {
            Toast.makeText(this@MainActivity, "Для чата требуется интернет", Toast.LENGTH_SHORT).show()
            return@withContext
        }
        welcomeManager.hideWelcomeMessage()
        saveLastChatTime()


        safeSwitchToFragment(CHAT_FRAGMENT_TAG) { ChatWithGigaFragment() }
        binding.bottomNavigation.selectedItemId = -1
    }



    /**
     * Загружает имя из кэша
     */
    private fun loadUserNameFromCache(): String? {
        return try {
            val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)

            // 1. Пробуем имя пользователя (полное)
            val userName = sharedPref.getString("user_name", null)
            if (!userName.isNullOrEmpty() && userName != "NOT_SET") {
                val firstName = extractFirstName(userName)
                if (firstName != "Пользователь") {
                    return firstName
                }
            }

            // 2. Пробуем отдельно сохраненное имя
            val firstName = sharedPref.getString("first_name", null)
            if (!firstName.isNullOrEmpty() && firstName != "Пользователь" && firstName != "NOT_SET") {
                return firstName
            }

            null

        } catch (e: Exception) {
            Log.e(TAG, "Error loading name from cache", e)
            null
        }
    }

    /**
     * Сохраняет имя в кэш
     */
    private fun saveUserNameToCache(userName: String) {
        try {
            val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
            val firstName = extractFirstName(userName)

            sharedPref.edit()
                .putString("user_name", userName)
                .putString("first_name", firstName)
                .apply()

            Log.d(TAG, "User name saved to cache: $userName (first name: $firstName)")

        } catch (e: Exception) {
            Log.e(TAG, "Error saving user name to cache", e)
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

                    // Передаем TTS Manager в WelcomeManager
                    welcomeManager.setTTSManager(ttsManager)

                    // Если активность активна - запускаем приветствие
                    if (isActivityResumed) {
                        handler.postDelayed({
                            welcomeManager.speakInitialGreeting()
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
     * Останавливает TTS
     */
    fun stopTTS() {
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
     * Получает имя пользователя для приветствия
     */
    fun getUserNameForGreeting(): String {
        return if (isUserNameLoaded) {
            cachedUserName
        } else {
            // Если еще не загрузилось, используем fallback
            val fallback = loadUserNameFromCache() ?: "Пользователь"
            fallback
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
     * Проверяет доступность сети
     */
    fun isNetworkAvailable(): Boolean {
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
     * Обновленный метод проверки авторизации
     */
    private suspend fun checkAuthAsync() = withContext(Dispatchers.IO) {
        try {
            // Сначала загружаем имя (уже делается в loadUserNameFirst())
            // Затем проверяем авторизацию

            val currentUser = withTimeoutOrNull(3000L) {
                try {
                    Firebase.auth.currentUser
                } catch (e: Exception) {
                    Log.w(TAG, "Firebase auth check failed", e)
                    null
                }
            }

            if (currentUser == null) {
                val cachedAuth = getSharedPreferences("auth_cache", MODE_PRIVATE)
                    .getBoolean("is_authenticated", false)

                if (!cachedAuth) {
                    withContext(Dispatchers.Main) {
                        redirectToAuth()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        startOfflineMode()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    auth = Firebase.auth
                    // Имя уже загружено, продолжаем инициализацию
                    initializeAppAsync()
                }
            }

        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Auth check timeout")
            withContext(Dispatchers.Main) {
                // Показываем приветствие даже без полной авторизации
                if (!isUserNameLoaded) {
                    cachedUserName = "Гость"
                    binding.tvUserName.text = "Гость"
                    isUserNameLoaded = true
                }
                startOfflineMode()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking auth", e)
            withContext(Dispatchers.Main) {
                startOfflineMode()
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

            // Показываем базовое приветствие через WelcomeManager
            welcomeManager.showBasicWelcomeMessage()

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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
                val firstName = sharedPref.getString("first_name", null)
                val userName = sharedPref.getString("user_name", null)

                withContext(Dispatchers.Main) {
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

    fun showSystemUIForVoiceSettings() {
        try {
            // 1. Показываем системные панели
            window?.decorView?.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )

            // 2. Полупрозрачные цвета для панелей
            window?.navigationBarColor = Color.parseColor("#80000000")
            window?.statusBarColor = Color.parseColor("#80000000")

            // 3. Показываем навигацию приложения
            binding.bottomNavigation.visibility = View.VISIBLE
            binding.toolbar.visibility = View.VISIBLE

            // 4. Для Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window?.isNavigationBarContrastEnforced = false
                window?.navigationBarDividerColor = Color.TRANSPARENT
            }

            // 5. WindowCompat
            WindowCompat.setDecorFitsSystemWindows(window!!, false)

            Log.d(TAG, "System UI shown for voice settings (translucent)")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing system UI for voice settings", e)
        }
    }

    /**
     * Вызывается при открытии настроек голоса из чата
     */
    fun onVoiceSettingsOpenedFromChat() {
        // Показываем навигацию для настроек голоса
        showSystemUIForVoiceSettings()
    }



    private fun setupToolbar() {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                Log.d(TAG, "setupToolbar: Начало")
                val toolbar = binding.toolbar
                setSupportActionBar(toolbar)

                // Скрываем кнопку музыки или делаем невидимой
                binding.btnMusic.visibility = View.GONE
                binding.btnMusic.setOnClickListener(null) // Отключаем слушатель

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


    /**
     * Восстанавливает полноэкранный режим чата
     */
    fun restoreChatFullscreenMode() {
        try {
            // Проверяем, какой фрагмент сейчас активен
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)

            if (currentFragment is ChatWithGigaFragment) {
                // 1. Скрываем системные панели
                hideSystemUIForChat()

                // 2. НЕМЕДЛЕННО скрываем навигацию приложения
                binding.bottomNavigation.visibility = View.GONE
                binding.toolbar.visibility = View.GONE

                // 3. Сбрасываем все анимации и состояния
                binding.bottomNavigation.alpha = 0f
                binding.bottomNavigation.translationY = 0f
                binding.toolbar.alpha = 0f
                binding.toolbar.translationY = 0f
                isTopNavigationHidden = true

                // 4. Скрываем приветственную карточку
                welcomeManager.hideWelcomeCard()

                // 5. Устанавливаем полноэкранный режим для window
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

                Log.d(TAG, "Chat fullscreen mode fully restored (navigation forced hidden)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error restoring chat fullscreen mode", e)
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

            // 4. Скрываем нижнюю навигацию приложения НЕМЕДЛЕННО
            binding.bottomNavigation.visibility = View.GONE
            binding.toolbar.visibility = View.GONE

            // Сбрасываем анимации
            binding.bottomNavigation.alpha = 0f
            binding.bottomNavigation.translationY = 0f
            binding.toolbar.alpha = 0f
            binding.toolbar.translationY = 0f
            isTopNavigationHidden = true

            // 5. Скрываем приветственную карточку если она видна
            welcomeManager.hideWelcomeCard()

            Log.d(TAG, "System UI hidden for chat (FULLSCREEN MODE, navigation forced)")

        } catch (e: Exception) {
            Log.e(TAG, "Error hiding system UI for chat", e)
        }
    }

    /**
     * Улучшенное скрытие верхней навигации с плавной анимацией
     */
    fun hideTopNavigation() {
        try {
            if (isTopNavigationHidden) return

            Log.d(TAG, "hideTopNavigation: Плавное скрытие верхней навигации")
            isTopNavigationHidden = true

            // Плавная анимация для Toolbar
            binding.toolbar.animate()
                .translationY(-binding.toolbar.height.toFloat())
                .alpha(0f)
                .setDuration(400)
                .setInterpolator(AccelerateInterpolator(1.2f))
                .withLayer()
                .withEndAction {
                    binding.toolbar.visibility = View.GONE
                }
                .start()

            // Плавная анимация для BottomNavigation с небольшой задержкой
            Handler(Looper.getMainLooper()).postDelayed({
                binding.bottomNavigation.animate()
                    .translationY(binding.bottomNavigation.height.toFloat())
                    .alpha(0f)
                    .setDuration(400)
                    .setInterpolator(AccelerateInterpolator(1.2f))
                    .withLayer()
                    .withEndAction {
                        binding.bottomNavigation.visibility = View.GONE
                    }
                    .start()
            }, 50)

        } catch (e: Exception) {
            Log.e(TAG, "Error hiding top navigation", e)
        }
    }

    /**
     * Улучшенное отображение верхней навигации с плавной анимацией
     */
    fun showTopNavigation() {
        try {
            if (!isTopNavigationHidden) return

            Log.d(TAG, "showTopNavigation: Плавное отображение верхней навигации")
            isTopNavigationHidden = false

            // Сначала показываем элементы
            binding.toolbar.visibility = View.VISIBLE
            binding.bottomNavigation.visibility = View.VISIBLE

            // Начальные состояния для анимации
            binding.toolbar.translationY = -binding.toolbar.height.toFloat()
            binding.toolbar.alpha = 0f
            binding.bottomNavigation.translationY = binding.bottomNavigation.height.toFloat()
            binding.bottomNavigation.alpha = 0f

            // Плавная анимация для Toolbar
            binding.toolbar.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(500)
                .setInterpolator(OvershootInterpolator(0.8f))
                .withLayer()
                .start()

            // Плавная анимация для BottomNavigation с небольшой задержкой
            Handler(Looper.getMainLooper()).postDelayed({
                binding.bottomNavigation.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(500)
                    .setInterpolator(OvershootInterpolator(0.8f))
                    .withLayer()
                    .start()
            }, 100)

        } catch (e: Exception) {
            Log.e(TAG, "Error showing top navigation", e)
        }
    }

    /**
     * Настройка системы для полноэкранного режима с оптимизацией
     */
    private fun setupFullscreenSystemUI() {
        try {
            // Устанавливаем флаги для работы с системными панелями
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)

            // ОЧЕНЬ ВАЖНО: Отключаем переходные анимации для системы
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.attributes.windowAnimations = android.R.style.Animation_Translucent
            }

            // Для Android 10+ устанавливаем прозрачную навигационную панель
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.navigationBarDividerColor = Color.TRANSPARENT
            }

            // Изначально делаем панели прозрачными
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT

            // Устанавливаем первоначальный режим
            WindowCompat.setDecorFitsSystemWindows(window, false)

            Log.d(TAG, "Optimized fullscreen system UI setup completed")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up optimized fullscreen system UI", e)
        }
    }

    /**
     * Переопределяем onWindowFocusChanged для предотвращения мерцания
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        // Используем postDelayed чтобы избежать конфликтов с системными анимациями
        handler.postDelayed({
            if (hasFocus) {
                // Если активный фрагмент - чат, скрываем системные панели
                if (currentFragmentTag == CHAT_FRAGMENT_TAG) {
                    hideSystemUIForChat()
                } else if (isTopNavigationHidden) {
                    // Если навигация скрыта, включаем полноэкранный режим
                    enableFullscreenMode()
                } else {
                    // Иначе показываем нормальный режим
                    disableFullscreenMode()
                }
            }
        }, 100) // Небольшая задержка для стабильности
    }

    /**
     * Включает полноэкранный режим (скрывает ВСЕ системные панели) с оптимизацией
     */
    private fun enableFullscreenMode() {
        try {
            if (isInFullscreenMode) return

            Log.d(TAG, "Enabling fullscreen mode with optimization")

            // КРИТИЧЕСКИ ВАЖНО: сначала меняем системные UI флаги БЕЗ анимации
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )

            // Сразу скрываем элементы навигации (без анимации)
            binding.toolbar.visibility = View.GONE
            binding.bottomNavigation.visibility = View.GONE

            // Для немедленного эффекта - скрываем элементы через альфа
            binding.toolbar.alpha = 0f
            binding.bottomNavigation.alpha = 0f
            binding.toolbar.translationY = 0f
            binding.bottomNavigation.translationY = 0f

            isInFullscreenMode = true
            Log.d(TAG, "Fullscreen mode enabled instantly")

        } catch (e: Exception) {
            Log.e(TAG, "Error enabling fullscreen mode", e)
        }
    }

    /**
     * Выключает полноэкранный режим (показывает системные панели) с оптимизацией
     */
    private fun disableFullscreenMode() {
        try {
            if (!isInFullscreenMode) return

            Log.d(TAG, "Disabling fullscreen mode with optimization")

            // КРИТИЧЕСКИ ВАЖНО: сначала показываем элементы навигации (без анимации)
            binding.toolbar.visibility = View.VISIBLE
            binding.bottomNavigation.visibility = View.VISIBLE
            binding.toolbar.alpha = 1f
            binding.bottomNavigation.alpha = 1f

            // Затем восстанавливаем системные UI флаги
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )

            isInFullscreenMode = false
            Log.d(TAG, "Fullscreen mode disabled instantly")

        } catch (e: Exception) {
            Log.e(TAG, "Error disabling fullscreen mode", e)
        }
    }

    /**
     * Быстрая настройка слушателей
     */
    private fun setupBasicClickListeners() {
        // Welcome Manager обрабатывает свои кнопки
        welcomeManager.setupWelcomeCardListeners(
            onStartChatClicked = {
                lifecycleScope.launch(Dispatchers.Main) {
                    animateButtonClick(welcomeManager.btnStartChat)
                    switchToChatAsync()
                }
            },
            onMaybeLaterClicked = {
                lifecycleScope.launch(Dispatchers.Main) {
                    animateButtonClick(welcomeManager.btnMaybeLater)
                    welcomeManager.hideWelcomeMessage()
                }
            },
            onCloseWelcomeClicked = {
                lifecycleScope.launch(Dispatchers.Main) {
                    animateButtonClick(welcomeManager.btnCloseWelcome)
                    welcomeManager.hideWelcomeMessage()
                }
            }
        )
    }

    /**
     * Анимация нажатия кнопки
     */
    private suspend fun animateButtonClick(view: View) = withContext(Dispatchers.Main) {
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
     * Проверяет, скрыта ли верхняя навигация
     */
    fun isTopNavigationHidden(): Boolean {
        return isTopNavigationHidden
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
            welcomeManager.hideWelcomeCard()

            // 5. Переключаемся на HomeFragment
            safeSwitchToFragment(HOME_FRAGMENT_TAG) { HomeFragment() }

            Log.d(TAG, "UI restored after chat exit, welcome card hidden")

        } catch (e: Exception) {
            Log.e(TAG, "Error restoring UI after chat", e)
        }
    }


    /**
     * Обновленный метод переключения фрагментов с учетом чата
     */
    private fun switchToFragment(tag: String, fragmentFactory: () -> Fragment) {
        if (currentFragmentTag == tag) return

        lifecycleScope.launch(Dispatchers.Main) {
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

    // Метод для открытия настроек голоса
    fun openVoiceSettings() {
        val fragment = VoiceSettingsFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack("voice_settings")
            .commitAllowingStateLoss()
    }

    fun getTTSManager(): TTSManager {
        return ttsManager
    }

    /**
     * Переключается на фрагмент лотереи
     */
    private fun switchToLotteryFragment() {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                welcomeManager.hideWelcomeMessage()

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
     * Полностью скрывает навигацию (публичный метод для фрагментов)
     */
    fun hideNavigationForChat() {
        try {
            binding.bottomNavigation.visibility = View.GONE
            binding.toolbar.visibility = View.GONE

            // Сбрасываем анимации
            binding.bottomNavigation.alpha = 0f
            binding.bottomNavigation.translationY = 0f
            binding.toolbar.alpha = 0f
            binding.toolbar.translationY = 0f

            // Устанавливаем флаг
            isTopNavigationHidden = true

            Log.d(TAG, "Navigation hidden for chat (called from fragment)")

        } catch (e: Exception) {
            Log.e(TAG, "Error hiding navigation from fragment", e)
        }
    }

    /**
     * Проверяет скрыта ли навигация (публичный метод для фрагментов)
     */
    fun isNavigationHidden(): Boolean {
        return isTopNavigationHidden
    }

    /**
     * ОПТИМИЗИРОВАННАЯ асинхронная инициализация приложения
     */
    private suspend fun initializeAppAsync() = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        try {
            Log.d(TAG, "=== OPTIMIZED ASYNC APP INITIALIZATION ===")

            // Показываем базовое приветствие мгновенно в главном потоке
            withContext(Dispatchers.Main) {
                welcomeManager.showLoadingProgress()
                welcomeManager.showInstantBasicGreeting() // 1-я часть приветствия
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
            val userProfile = profileJob.await()
            val userDataLoaded = userDataJob.await()

            // Передаем профиль в WelcomeManager
            withContext(Dispatchers.Main) {
                this@MainActivity.userProfile = userProfile
                welcomeManager.setUserProfile(userProfile)

                welcomeManager.hideLoadingProgress()
                setupToolbar()

                // Запускаем AI-улучшенное поэтапное приветствие через WelcomeManager
                welcomeManager.startStagedWelcomeSequence()

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
            withContext(Dispatchers.Main) {
                welcomeManager.hideLoadingProgress()
                welcomeManager.showBasicWelcomeMessage()
                isAppInitialized = true
            }
        }
    }

    /**
     * Загружает кэшированные данные асинхронно
     */
    private suspend fun loadCachedUserDataAsync() = withContext(Dispatchers.IO) {
        return@withContext try {
            val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
            val fullName = sharedPref.getString("user_name", null)
            val firstName = sharedPref.getString("first_name", null)

            withContext(Dispatchers.Main) {
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
    private suspend fun loadCachedProfile(): UserProfile? = withContext(Dispatchers.IO) {
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
    private suspend fun initializeBackgroundServices() = withContext(Dispatchers.IO) {
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
    private suspend fun loadAdditionalData() = withContext(Dispatchers.IO) {
        try {
            // Дополнительные данные которые не критичны для запуска
        } catch (e: Exception) {
            Log.e(TAG, "Error loading additional data", e)
        }
    }

    /**
     * Обновляет профиль пользователя при изменении анкеты
     */
    fun updateUserProfile(newProfile: UserProfile) {
        userProfile = newProfile
        welcomeManager.updateUserProfile(newProfile)
        Log.d(TAG, "User profile updated in MainActivity")
    }

    /**
     * Асинхронная загрузка профиля пользователя
     */
    private suspend fun loadUserProfileAsync(): UserProfile? = withContext(Dispatchers.IO) {
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
    private suspend fun createBasicUserProfile(userId: String) = withContext(Dispatchers.IO) {
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
    private suspend fun loadCurrentUserDataAsync(): Boolean = withContext(Dispatchers.IO) {
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
                    withContext(Dispatchers.Main) {
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
    private suspend fun loadUserNameFromAuthAsync() = withContext(Dispatchers.Main) {
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
        lifecycleScope.launch(Dispatchers.Main) {
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
                            welcomeManager.updateWelcomeMessageWithProfile()
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
        lifecycleScope.launch(Dispatchers.Main) {
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
     * Сохраняет время последнего чата для контекста
     */
    fun saveLastChatTime() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sharedPref = getSharedPreferences("chat_prefs", MODE_PRIVATE)
            sharedPref.edit().putLong("last_chat_time", System.currentTimeMillis()).apply()
        }
    }

    /**
     * Сохраняет продолжительность чата и обновляет аналитику
     */
    fun saveChatDuration(duration: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
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
        lifecycleScope.launch(Dispatchers.IO) {
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
     * Сохраняет приветственную фразу для использования в чате
     */
    fun saveWelcomePhraseForChat(phrase: String) {
        lifecycleScope.launch(Dispatchers.IO) {
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
        lifecycleScope.launch(Dispatchers.IO) {
            val sharedPref = getSharedPreferences("chat_prefs", MODE_PRIVATE)
            sharedPref.edit().remove("welcome_phrase").apply()
            Log.d(TAG, "Welcome phrase cleared")
        }
    }

    /**
     * Расширенная диагностика данных пользователя
     */
    private fun debugUserDataExtended() {
        // Только для отладки
        if (!BuildConfig.DEBUG) return

        lifecycleScope.launch(Dispatchers.IO) {
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
        lifecycleScope.launch(Dispatchers.IO) {
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

        // Заметки
        popupView.findViewById<View>(R.id.notes_item).setOnClickListener {
            animateMenuItemClick(it)
            try {
                val intent = Intent(this, NotesActivity::class.java)
                startActivity(intent)
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting NotesActivity", e)
                Toast.makeText(this, "Ошибка открытия заметок", Toast.LENGTH_SHORT).show()
            }
            popupWindow.dismiss()
        }

        // Музыка
        popupView.findViewById<View>(R.id.music_item).setOnClickListener {
            animateMenuItemClick(it)
            try {
                startActivity(Intent(this@MainActivity, MusicMainActivity::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Error starting MusicActivity", e)
                Toast.makeText(this@MainActivity, "Ошибка открытия музыки", Toast.LENGTH_SHORT).show()
            }
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
            startActivity(Intent(this@MainActivity, TimerActivity::class.java))
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

        // Убираем кнопку музыки из экстренной навигации
        /*
        binding.btnMusic.setOnClickListener {
            try {
                startActivity(Intent(this, MusicMainActivity::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Error starting MusicActivity", e)
                Toast.makeText(this, "Ошибка открытия музыки", Toast.LENGTH_SHORT).show()
            }
        }
        */

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




    private fun updateToolbarUserInfo(user: User) {
        try {
            val fullName = user.getFullName()
            binding.tvUserName.text = fullName

            lifecycleScope.launch(Dispatchers.IO) {
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
     * Показывает приветствие с запасным вариантом
     */
    private fun showWelcomeMessageWithFallback() {
        try {
            val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
            val fallbackName = sharedPref.getString("first_name", "Пользователь")

            Log.w(TAG, "Using fallback name: $fallbackName")
            welcomeManager.showBasicWelcomeMessage()
        } catch (e: Exception) {
            Log.e(TAG, "Error in showWelcomeMessageWithFallback", e)
            welcomeManager.showFallbackGreeting()
        }
    }

    /**
     * Принудительное обновление данных пользователя (для диагностики)
     */
    fun forceRefreshUserData() {
        Log.d(TAG, "Forcing user data refresh")
        lifecycleScope.launch(Dispatchers.Default) {
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
        lifecycleScope.launch(Dispatchers.Main) {
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
        lifecycleScope.launch(Dispatchers.Main) {
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
            lifecycleScope.launch(Dispatchers.Default) {
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

    private suspend fun initializeServicesSafely() {
        Log.d(TAG, "initializeServicesSafely: Начало")

        val locationJob = lifecycleScope.async(Dispatchers.IO) {
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

        val stepJob = lifecycleScope.async(Dispatchers.IO) {
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

        val quotesJob = lifecycleScope.async(Dispatchers.IO) {
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

        lifecycleScope.launch(Dispatchers.IO) {
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

        lifecycleScope.launch(Dispatchers.IO) {
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

        lifecycleScope.launch(Dispatchers.IO) {
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



    private fun logoutUser() {
        lifecycleScope.launch(Dispatchers.IO) {
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

    private fun setupCriticalUI() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)

            // Настройка базовых слушателей
            setupBasicClickListeners()

            // ДОБАВЬТЕ ЭТУ СТРОКУ:
            setupBottomNavigation() // <-- ВАЖНО

            Log.d(TAG, "Critical UI setup completed")

        } catch (e: Exception) {
            Log.e(TAG, "Error in critical UI setup", e)
        }
    }

    private fun setupBottomNavigation() {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                bottomNav = binding.bottomNavigation
                Log.d(TAG, "Setting up bottom navigation...")

                // 1. Отключаем ВСЕ системные окрашивания
                bottomNav.itemIconTintList = null
                bottomNav.itemTextColor = null

                // 2. Устанавливаем высоту навигации
                bottomNav.layoutParams.height = 130.dpToPx() // Увеличиваем высоту

                // 3. Прозрачный фон
                bottomNav.background = ContextCompat.getDrawable(
                    this@MainActivity,
                    R.drawable.transparent_nav_background
                )
                bottomNav.elevation = 8f

                // 4. Используем labelVisibilityMode вместо очистки текста
                bottomNav.labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_UNLABELED

                // 5. ОБНОВЛЕННЫЙ слушатель с обработкой навигации
                bottomNav.setOnItemSelectedListener { item ->
                    val itemSelected = when (item.itemId) {
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

                    if (itemSelected) {
                        // Обновляем внешний вид после успешного выбора
                        updateBottomNavAppearance(item.itemId)
                    }

                    itemSelected
                }

                // 6. Инициализируем внешний вид с активным элементом "Главная"
                updateBottomNavAppearance(R.id.nav_home)

                // 7. Устанавливаем активный элемент
                binding.bottomNavigation.selectedItemId = R.id.nav_home

                Log.d(TAG, "Bottom navigation setup completed successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up bottom navigation", e)
            }
        }
    }

    private fun updateBottomNavAppearance(selectedItemId: Int) {
        try {
            // Проходим по всем элементам меню
            val menu = bottomNav.menu
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                val itemView = bottomNav.findViewById<View>(item.itemId)

                // Альтернативный способ найти iconView
                val iconView = findIconView(itemView)

                iconView?.let { imageView ->
                    if (item.itemId == selectedItemId) {
                        // Активный элемент
                        imageView.alpha = 1.0f

                        // Увеличиваем размер активной иконки
                        val layoutParams = imageView.layoutParams
                        layoutParams.width = 75.dpToPx()
                        layoutParams.height = 75.dpToPx()
                        imageView.layoutParams = layoutParams

                        // Закругление
                        imageView.clipToOutline = true
                        imageView.outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) {
                                // Сделаем идеальный круг
                                val radius = (view.width / 2).toFloat()
                                outline.setRoundRect(0, 0, view.width, view.height, radius)
                            }
                        }

                        // Добавляем легкую тень для акцента
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            imageView.elevation = 4f
                        }

                        // Анимация увеличения
                        imageView.animate()
                            .scaleX(1.1f)
                            .scaleY(1.1f)
                            .setDuration(200)
                            .start()

                        // Анимация "подпрыгивания"
                        imageView.animate()
                            .translationY(-8.dpToPx().toFloat())
                            .setDuration(200)
                            .start()

                    } else {
                        // Неактивный элемент
                        imageView.alpha = 0.7f

                        // Стандартный размер
                        val layoutParams = imageView.layoutParams
                        layoutParams.width = 48.dpToPx()
                        layoutParams.height = 48.dpToPx()
                        imageView.layoutParams = layoutParams

                        // Убираем закругление и тень
                        imageView.clipToOutline = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            imageView.elevation = 0f
                        }

                        // Возвращаем к обычному размеру
                        imageView.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0f)
                            .setDuration(200)
                            .start()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating bottom nav appearance", e)
        }
    }

    /**
     * Вспомогательный метод для поиска ImageView в BottomNavigationView
     */
    private fun findIconView(view: View?): ImageView? {
        if (view == null) return null

        return if (view is ImageView) {
            view
        } else if (view is ViewGroup) {
            // Ищем ImageView среди дочерних элементов
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val result = findIconView(child)
                if (result != null) return result
            }
            null
        } else {
            null
        }
    }

    // Добавьте этот extension
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    /**
     * УНИВЕРСАЛЬНЫЙ перезапуск сервисов после краша
     */
    private fun restartServicesAfterCrash() {
        lifecycleScope.launch(Dispatchers.IO) {
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

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withTimeout(TRACKING_CHECK_TIMEOUT) {
                    val isTracking = withContext(Dispatchers.IO) {
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

        isActivityResumed = true
        wasActivityPaused = false

        // Переинициализируем TTS если было паузирование
        if (wasActivityPaused && !isTTSInitialized) {
            Log.d(TAG, "Reinitializing TTS after pause")
            ttsInitializationAttempts = 0
            initTTSManagerSync()
        }

        // Передаем флаг активности в WelcomeManager
        welcomeManager.onActivityResumed()

        // КРИТИЧЕСКИ ВАЖНО: При возвращении в Activity всегда показываем навигацию
        // Это должно быть сделано СРАЗУ, без задержки
        handler.post {
            showTopNavigation()

            // Также показываем навигацию в HomeFragment если он активен
            if (currentFragmentTag == HOME_FRAGMENT_TAG) {
                // Ищем HomeFragment и показываем его навигацию
                supportFragmentManager.fragments.forEach { fragment ->
                    if (fragment is HomeFragment && fragment.isAdded) {
                        fragment.resetAllNavigation()
                    }
                }
            }
        }

        // Если приветствие видно - обновляем и повторно озвучиваем
        if (welcomeManager.isWelcomeCardVisible()) {
            handler.postDelayed({
                welcomeManager.updateWelcomeMessageWithProfile()
            }, 1000)
        }

        // ОТЛОЖИТЬ тяжелые операции
        lifecycleScope.launch(Dispatchers.Main) {
            resumeAppAsync()
        }
    }

    /**
     * Асинхронное возобновление приложения
     */
    private suspend fun resumeAppAsync() = withContext(Dispatchers.Default) {
        try {
            // Обновляем приветствие если нужно (только если виджет видим)
            if (welcomeManager.isWelcomeCardVisible()) {
                withContext(Dispatchers.Main) {
                    handler.postDelayed({
                        welcomeManager.updateWelcomeMessageWithProfile()
                    }, 500)
                }
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
        lifecycleScope.launch(Dispatchers.IO) {
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
        lifecycleScope.launch(Dispatchers.IO) {
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
            lifecycleScope.launch(Dispatchers.Main) {
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

        // Уведомляем WelcomeManager о паузе
        welcomeManager.onActivityPaused()
    }

    override fun onDestroy() {
        Log.d(TAG, "MainActivity destroyed")
        // Отменяем все корутины и задачи
        handler.removeCallbacksAndMessages(null)
        fragmentCache.clear()
        isLocationServiceStarting.set(false)
        isStepServiceStarting.set(false)

        // Освобождаем ресурсы TTS Manager
        ttsManager.release()

        // Уничтожаем WelcomeManager
        welcomeManager.onDestroy()

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
        lifecycleScope.launch(Dispatchers.Main) {
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
            val popupView = inflater.inflate(R.layout.popup_menu_grid, null)

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
                tvProfileName.text = user.displayName ?: extractFirstName(getUserNameForGreeting())
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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val snapshot = Firebase.database.reference
                    .child("users")
                    .child(userId)
                    .get()
                    .await()

                if (snapshot.exists()) {
                    val user = snapshot.getValue(User::class.java)
                    user?.profileImageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        withContext(Dispatchers.Main) {
                            Glide.with(this@MainActivity)
                                .load(url)
                                .circleCrop()
                                .placeholder(R.drawable.fill)
                                .error(R.drawable.fill)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .into(imageView)
                        }
                    } ?: withContext(Dispatchers.Main) {
                        imageView.setImageResource(R.drawable.fill)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
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
            R.id.menu_notes -> {
                try {
                    startActivity(Intent(this, NotesActivity::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting NotesActivity", e)
                    Toast.makeText(this, "Ошибка открытия заметок", Toast.LENGTH_SHORT).show()
                }
            }
            R.id.music_item -> {
                try {
                    startActivity(Intent(this, MusicMainActivity::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting MusicActivity", e)
                    Toast.makeText(this, "Ошибка открытия музыки", Toast.LENGTH_SHORT).show()
                }
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



    // Сохраняем ссылку на popupView для анимации
    private var popupView: View? = null

    /**
     * Безопасное переключение фрагментов с учетом полноэкранного режима
     */
    private fun safeSwitchToFragment(tag: String, fragmentFactory: () -> Fragment) {
        handler.postDelayed({
            lifecycleScope.launch(Dispatchers.Main) {
                try {
                    // При переключении на домашний фрагмент гарантируем показ всей навигации
                    if (tag == HOME_FRAGMENT_TAG) {
                        showTopNavigation()
                    }

                    // При переключении на чат - скрываем всю навигацию
                    if (tag == CHAT_FRAGMENT_TAG) {
                        hideTopNavigation()
                    }

                    switchToFragment(tag, fragmentFactory)
                } catch (e: Exception) {
                    Log.e(TAG, "Safe fragment switch failed for: $tag", e)
                    try {
                        clearAllFragments()
                        loadFragment(fragmentFactory(), tag)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Critical fragment error", e2)
                    }
                }
            }
        }, 50)
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