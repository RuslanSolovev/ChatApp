package com.example.chatapp.activities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
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
import com.example.chatapp.step.StepCounterApp
import com.example.chatapp.step.StepCounterFragment
import com.example.chatapp.step.StepCounterService
import com.example.chatapp.step.StepCounterServiceWorker
import com.example.chatapp.utils.PhilosophyQuoteWorker
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var bottomNav: BottomNavigationView
    private var isFirstLaunch = true
    private var currentPermissionIndex = 0

    // Атомарные флаги для предотвращения многократного запуска
    private val isLocationServiceStarting = AtomicBoolean(false)
    private val isStepServiceStarting = AtomicBoolean(false)

    // Обработчик для отложенных задач
    private val handler = Handler(Looper.getMainLooper())

    // Кэш для фрагментов
    private val fragmentCache = mutableMapOf<String, Fragment>()
    private var currentFragmentTag: String? = null

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
        try {
            Log.d(TAG, "Basic permissions result: $permissions")
            currentPermissionIndex = 0
            // Запускаем с задержкой для стабильности
            handler.postDelayed({
                requestNextAdditionalPermission()
            }, PERMISSION_REQUEST_DELAY)
        } catch (e: Exception) {
            Log.e(TAG, "Error in basic permission callback", e)
            proceedWithMainInitialization()
        }
    }

    // --- ActivityResultLauncher для ДОПОЛНИТЕЛЬНЫХ разрешений ---
    private val additionalPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
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
        private const val TRACKING_CHECK_TIMEOUT = 5000L // 5 секунд
        private const val SERVICE_INIT_TIMEOUT = 10000L // 10 секунд
        private const val PERMISSION_REQUEST_DELAY = 300L // Задержка для стабильности UI
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            setupExceptionHandler()

            // Упрощенная проверка авторизации
            auth = Firebase.auth
            if (auth.currentUser == null) {
                Log.d(TAG, "onCreate: Пользователь не авторизован, переход к AuthActivity")
                redirectToAuth()
                return
            }

            // ЗАПУСКАЕМ ПЕРЕЗАПУСК СЕРВИСОВ ПОСЛЕ КРАША СРАЗУ
            restartServicesAfterCrash()

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            WindowCompat.setDecorFitsSystemWindows(window, false)

            setupToolbar()
            setupBottomNavigation()
            loadCurrentUserData()

            // Загрузка начального фрагмента без задержек
            if (savedInstanceState == null) {
                loadFragment(HomeFragment(), HOME_FRAGMENT_TAG)
                binding.bottomNavigation.selectedItemId = R.id.nav_home
            }

            // Отложенная инициализация сервисов
            if (savedInstanceState == null && isFirstLaunch) {
                Log.d(TAG, "onCreate: Первый запуск, проверка разрешений")
                handler.postDelayed({
                    checkAndRequestMainPermissions()
                }, 1000)
            } else {
                Log.d(TAG, "onCreate: Не первый запуск или есть состояние")
                proceedWithMainInitialization()
            }

            // Асинхронная проверка трекинга без блокировки UI
            checkTrackingStatusAsync()

        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate", e)
            showErrorAndFinish("Ошибка запуска приложения")
        }
    }

    /**
     * УНИВЕРСАЛЬНЫЙ перезапуск сервисов после краша
     */
    private fun restartServicesAfterCrash() {
        lifecycleScope.launch {
            try {
                val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
                val wasCrash = prefs.getBoolean("was_crash", false)

                if (wasCrash) {
                    Log.w(TAG, "Обнаружен краш приложения, перезапускаем сервисы...")

                    // Используем метод из Application для универсального перезапуска
                    (application as? StepCounterApp)?.restartServicesAfterCrash()

                    // Сбрасываем флаг краша
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
     * Установка обработчика непойманных исключений
     */
    private fun setupExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Сохраняем информацию о краше и состоянии сервисов
                val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
                val editor = prefs.edit()

                // Сохраняем текущее состояние сервисов перед крашем
                val stepApp = application as? StepCounterApp
                editor.putBoolean("step_service_active", stepApp?.getServiceState("step") == true)
                editor.putBoolean("location_tracking_active", stepApp?.getServiceState("location") == true)
                editor.putBoolean("was_crash", true)
                editor.putLong("last_crash_time", System.currentTimeMillis())
                editor.apply()

                Log.e(TAG, "Uncaught exception, saving crash state", throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Error in exception handler", e)
            } finally {
                // Вызываем стандартный обработчик
                defaultHandler?.uncaughtException(thread, throwable)
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

        lifecycleScope.launch {
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

    private fun showErrorAndFinish(message: String) {
        try {
            Log.e(TAG, "showErrorAndFinish: $message")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            lifecycleScope.launch {
                delay(2000)
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "showErrorAndFinish: Критическая ошибка", e)
            finish()
        }
    }

    // --- БЕЗОПАСНЫЙ ЗАПРОС РАЗРЕШЕНИЙ ---
    private fun checkAndRequestMainPermissions() {
        try {
            Log.d(TAG, "checkAndRequestMainPermissions: Начало")
            val missingBasicPermissions = basicPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
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

    private fun requestNextAdditionalPermission() {
        try {
            if (currentPermissionIndex < additionalPermissions.size) {
                val nextPermission = additionalPermissions[currentPermissionIndex]
                if (ContextCompat.checkSelfPermission(this, nextPermission) != PackageManager.PERMISSION_GRANTED &&
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

    // --- УЛУЧШЕННАЯ ИНИЦИАЛИЗАЦИЯ СЕРВИСОВ ---
    private fun proceedWithMainInitialization() {
        Log.d(TAG, "Proceeding with main initialization. isFirstLaunch=$isFirstLaunch")
        if (isFirstLaunch) {
            lifecycleScope.launch {
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

        // Параллельная инициализация сервисов с индивидуальными таймаутами
        val locationJob = lifecycleScope.async {
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

        val stepJob = lifecycleScope.async {
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

        val quotesJob = lifecycleScope.async {
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

        // Ожидаем завершения с общим таймаутом
        try {
            withTimeout(15000L) {
                locationJob.await()
                stepJob.await()
                quotesJob.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Общий таймаут инициализации сервисов", e)
        }

        // Отложенная настройка WorkManager (не критично для старта)
        lifecycleScope.launch {
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
        try {
            Log.d(TAG, "startPhilosophyQuotes: Запуск ежечасных философских цитат")
            val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val quotesStarted = sharedPreferences.getBoolean("philosophy_quotes_started", false)
            if (!quotesStarted) {
                val workManager = WorkManager.getInstance(this)
                val quoteWorkRequest = PeriodicWorkRequestBuilder<PhilosophyQuoteWorker>(
                    1, TimeUnit.HOURS,
                    15, TimeUnit.MINUTES
                ).build()
                workManager.enqueueUniquePeriodicWork(
                    PHILOSOPHY_QUOTES_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    quoteWorkRequest
                )
                sharedPreferences.edit().putBoolean("philosophy_quotes_started", true).apply()
                Log.d(TAG, "✅ Ежечасные философские цитаты запущены")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска философских цитат", e)
        }
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

    override fun onResume() {
        super.onResume()

        Log.d(TAG, "onResume: Activity становится активной, запускаем сервисы...")

        try {
            // 1. ЗАПУСКАЕМ СЕРВИСЫ КОГДА ACTIVITY АКТИВНА
            val stepCounterApp = application as? StepCounterApp
            if (stepCounterApp != null) {
                Log.d(TAG, "onResume: Запускаем сервисы через StepCounterApp...")
                stepCounterApp.startServicesFromActivity()
            } else {
                Log.e(TAG, "onResume: StepCounterApp is null, запускаем сервисы напрямую")
                // Fallback: запускаем сервисы напрямую
                startServicesDirectly()
            }

            // 2. ПРОВЕРЯЕМ СТАТУС ТРЕКИНГА
            if (auth.currentUser != null) {
                Log.d(TAG, "onResume: Пользователь авторизован, проверяем статус трекинга...")
                LocationServiceManager.isTrackingActive(this) { isTracking ->
                    Log.d(TAG, "onResume: Статус трекинга - $isTracking")

                    // Если трекинг активен но сервис не запущен - запускаем
                    if (isTracking && !LocationServiceManager.isServiceRunning(this@MainActivity)) {
                        Log.w(TAG, "onResume: Трекинг активен но сервис не запущен, запускаем...")
                        startLocationUpdateService()
                    }
                }

                // 3. ПРОВЕРЯЕМ СОСТОЯНИЕ СЕРВИСОВ
                checkServicesState()
            } else {
                Log.w(TAG, "onResume: Пользователь не авторизован, сервисы не запускаем")
            }

            // 4. ОБНОВЛЯЕМ ДАННЫЕ ПОЛЬЗОВАТЕЛЯ
            loadCurrentUserData()

            Log.d(TAG, "onResume: Все операции завершены успешно")

        } catch (e: Exception) {
            Log.e(TAG, "onResume: Критическая ошибка при запуске сервисов", e)
            // Показываем уведомление пользователю
            showServiceStartErrorNotification()
        }
    }

    /**
     * Прямой запуск сервисов (fallback)
     */
    private fun startServicesDirectly() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "startServicesDirectly: Прямой запуск сервисов...")

                // Проверяем разрешения перед запуском
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
        lifecycleScope.launch {
            try {
                val stepCounterApp = application as? StepCounterApp
                val isStepActive = stepCounterApp?.getServiceState("step") ?: false
                val isLocationActive = stepCounterApp?.getServiceState("location") ?: false

                Log.d(TAG, "checkServicesState: Step service active: $isStepActive, Location service active: $isLocationActive")

                // Если сервисы должны быть активны но не запущены - пытаемся запустить
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
        return prefs.getBoolean("step_service_should_be_active", true) // По умолчанию true
    }

    /**
     * Проверяет должен ли быть активен сервис локации
     */
    private fun shouldLocationServiceBeActive(): Boolean {
        val prefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("location_service_should_be_active", false) // По умолчанию false
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

    /**
     * Безопасный запуск сервиса локации с защитой от дублирования
     */
    private fun startLocationUpdateService() {
        if (isLocationServiceStarting.getAndSet(true)) {
            Log.d(TAG, "Location service start already in progress, skipping")
            return
        }

        try {
            val serviceIntent = Intent(this, LocationUpdateService::class.java).apply {
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
            // Сбрасываем флаг через небольшой промежуток времени
            handler.postDelayed({
                isLocationServiceStarting.set(false)
            }, 2000)
        }
    }

    /**
     * Безопасный запуск сервиса шагомера с защитой от дублирования
     */
    private fun startStepCounterService() {
        if (isStepServiceStarting.getAndSet(true)) {
            Log.d(TAG, "Step service start already in progress, skipping")
            return
        }

        try {
            StepCounterService.startService(this)
            Log.d(TAG, "Step service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start step service", e)
        } finally {
            handler.postDelayed({
                isStepServiceStarting.set(false)
            }, 1000)
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

    private fun setupToolbar() {
        try {
            Log.d(TAG, "setupToolbar: Начало")
            val toolbar = binding.toolbar
            setSupportActionBar(toolbar)

            binding.btnMusic.setOnClickListener {
                try {
                    startActivity(Intent(this, MusicMainActivity::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting MusicActivity", e)
                    Toast.makeText(this, "Ошибка открытия музыки", Toast.LENGTH_SHORT).show()
                }
            }

            binding.btnMenu.setOnClickListener { view ->
                showPopupMenu(view)
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
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up toolbar", e)
        }
    }

    private fun loadCurrentUserData() {
        try {
            Log.d(TAG, "loadCurrentUserData: Начало")
            val currentUserId = auth.currentUser?.uid ?: return

            val database = Firebase.database.reference
            database.child("users").child(currentUserId).addListenerForSingleValueEvent(
                object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        try {
                            if (snapshot.exists()) {
                                val user = snapshot.getValue(User::class.java)
                                user?.let { updateToolbarUserInfo(it) }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing user data", e)
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Failed to load user data", error.toException())
                    }
                })
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user data", e)
        }
    }

    private fun updateToolbarUserInfo(user: User) {
        try {
            binding.tvUserName.text = user.getFullName()
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
        } catch (e: Exception) {
            Log.e(TAG, "Error updating toolbar user info", e)
            binding.ivUserAvatar.setImageResource(R.drawable.ic_default_profile)
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

    private fun logoutUser() {
        lifecycleScope.launch {
            try {
                // Останавливаем сервисы
                stopService(Intent(this@MainActivity, LocationUpdateService::class.java))
                stopService(Intent(this@MainActivity, StepCounterService::class.java))

                // Отменяем WorkManager задачи
                WorkManager.getInstance(this@MainActivity).apply {
                    cancelUniqueWork(STEP_SERVICE_WORK_NAME)
                    cancelUniqueWork(PHILOSOPHY_QUOTES_WORK_NAME)
                }

                // Очищаем настройки
                getSharedPreferences("app_prefs", MODE_PRIVATE).edit().clear().apply()

                // Очищаем кэш
                fragmentCache.clear()

                // Выход из Firebase
                auth.signOut()

                // Переход к авторизации
                startActivity(Intent(this@MainActivity, AuthActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()

            } catch (e: Exception) {
                Log.e(TAG, "Error during logout", e)
                // Принудительный переход при ошибке
                startActivity(Intent(this@MainActivity, AuthActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
        }
    }

    private fun setupBottomNavigation() {
        try {
            bottomNav = binding.bottomNavigation

            bottomNav.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_home -> {
                        switchToFragment(HOME_FRAGMENT_TAG) { HomeFragment() }
                        true
                    }
                    R.id.nav_gigachat -> {
                        switchToFragment(CHAT_FRAGMENT_TAG) { ChatWithGigaFragment() }
                        true
                    }
                    R.id.nav_steps -> {
                        switchToFragment(STEPS_FRAGMENT_TAG) { StepCounterFragment() }
                        true
                    }
                    R.id.nav_maps -> {
                        switchToFragment(MAPS_FRAGMENT_TAG) { LocationPagerFragment() }
                        true
                    }
                    R.id.nav_games -> {
                        switchToFragment(GAMES_FRAGMENT_TAG) { GamesFragment() }
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
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up bottom navigation", e)
        }
    }

    /**
     * Умное переключение между фрагментами с кэшированием
     */
    private fun switchToFragment(tag: String, fragmentFactory: () -> Fragment) {
        try {
            if (currentFragmentTag == tag) return

            val fragment = fragmentCache[tag] ?: run {
                val newFragment = fragmentFactory()
                fragmentCache[tag] = newFragment
                newFragment
            }

            // Скрываем текущий фрагмент
            currentFragmentTag?.let { currentTag ->
                fragmentCache[currentTag]?.let { currentFragment ->
                    if (currentFragment.isAdded) {
                        supportFragmentManager.beginTransaction()
                            .hide(currentFragment)
                            .commitAllowingStateLoss()
                    }
                }
            }

            // Показываем целевой фрагмент
            if (fragment.isAdded) {
                supportFragmentManager.beginTransaction()
                    .show(fragment)
                    .commitAllowingStateLoss()
            } else {
                supportFragmentManager.beginTransaction()
                    .add(R.id.fragment_container, fragment, tag)
                    .commitAllowingStateLoss()
            }

            currentFragmentTag = tag

        } catch (e: Exception) {
            Log.e(TAG, "Error switching to fragment: $tag", e)
            // Fallback
            loadFragment(fragmentFactory(), tag)
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

    /**
     * Стандартная загрузка фрагмента
     */
    private fun loadFragment(fragment: Fragment, tag: String) {
        try {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment, tag)
                .commitAllowingStateLoss()

            fragmentCache[tag] = fragment
            currentFragmentTag = tag
        } catch (e: Exception) {
            Log.e(TAG, "Error loading fragment: $tag", e)
        }
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

    private fun redirectToAuth() {
        startActivity(Intent(this, AuthActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        Log.d(TAG, "MainActivity destroyed")
        // Очищаем все отложенные задачи
        handler.removeCallbacksAndMessages(null)
        // Очищаем кэш фрагментов
        fragmentCache.clear()
        // Сбрасываем флаги запуска
        isLocationServiceStarting.set(false)
        isStepServiceStarting.set(false)
        super.onDestroy()
    }
}