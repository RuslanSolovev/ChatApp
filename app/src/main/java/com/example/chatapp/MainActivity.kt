package com.example.chatapp.activities

import android.Manifest
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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.chatapp.step.StepCounterServiceWorker
import com.example.chatapp.R
import com.example.chatapp.budilnik.AlarmActivity
import com.example.chatapp.databinding.ActivityMainBinding
import com.example.chatapp.fragments.*
import com.example.chatapp.models.User
import com.example.chatapp.muzika.ui.MusicMainActivity
import com.example.chatapp.novosti.CreateNewsFragment
import com.example.chatapp.novosti.FullScreenImageFragment
import com.example.chatapp.novosti.NewsItem
import com.example.chatapp.location.LocationUpdateService
import com.example.chatapp.step.StepCounterService
import com.example.chatapp.location.LocationPagerFragment
import com.example.chatapp.location.LocationServiceManager
import com.example.chatapp.step.StepCounterFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.* // Добавлено для корутин
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var bottomNav: BottomNavigationView

    private var isFirstLaunch = true
    private val permissionQueue = mutableListOf<String>()
    private var currentPermissionIndex = 0

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
            requestNextAdditionalPermission()
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
            requestNextAdditionalPermission()
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
        private const val LOCATION_SERVICE_WORK_NAME = "LocationServicePeriodicWork"
        private const val STEP_SERVICE_WORK_NAME = "StepCounterServicePeriodicWork"
        private const val SERVICE_MONITOR_WORK_NAME = "ServiceMonitorWork"

        // Интервалы для WorkManager
        private const val LOCATION_SERVICE_INTERVAL_HOURS = 2L
        private const val STEP_SERVICE_INTERVAL_MINUTES = 30L
        private const val SERVICE_MONITOR_INTERVAL_MINUTES = 30L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            // ДОБАВИТЬ ПЕРВЫМ ДЕЛОМ - проверка авторизации ДО проверки трекинга
            auth = Firebase.auth

            if (auth.currentUser == null) {
                Log.d(TAG, "onCreate: Пользователь не авторизован, переход к AuthActivity")
                startActivity(Intent(this, AuthActivity::class.java))
                finish()
                return
            }

            // ТЕПЕРЬ проверяем и запускаем сервис трекинга
            LocationServiceManager.isTrackingActive(this) { isTracking ->
                if (isTracking) {
                    Log.d(TAG, "onCreate: Трекинг активен, запускаем сервис")
                    LocationServiceManager.startLocationService(this)
                } else {
                    Log.d(TAG, "onCreate: Трекинг не активен")
                }
            }

            Log.d(TAG, "onCreate: Начало")
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            WindowCompat.setDecorFitsSystemWindows(window, false)

            setupToolbar()
            setupBottomNavigation()
            loadCurrentUserData()

            if (savedInstanceState == null && isFirstLaunch) {
                Log.d(TAG, "onCreate: Первый запуск, проверка разрешений")
                checkAndRequestMainPermissions()
            } else {
                Log.d(TAG, "onCreate: Не первый запуск или есть состояние, инициализация")
                proceedWithMainInitialization()
            }

            if (savedInstanceState == null) {
                Log.d(TAG, "onCreate: Загрузка начального фрагмента")
                loadFragment(HomeFragment(), HOME_FRAGMENT_TAG)
                binding.bottomNavigation.selectedItemId = R.id.nav_home
            }

            Log.d(TAG, "onCreate: Завершено")

        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate", e)
            showErrorAndFinish("Ошибка запуска приложения")
        }
    }

    private fun showErrorAndFinish(message: String) {
        try {
            Log.e(TAG, "showErrorAndFinish: $message")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "showErrorAndFinish: Критическая ошибка при показе сообщения", e)
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
                Log.d(TAG, "checkAndRequestMainPermissions: Основные разрешения есть, запрос дополнительных")
                requestNextAdditionalPermission()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting permissions", e)
            proceedWithMainInitialization()
        }
    }

    private fun requestNextAdditionalPermission() {
        try {
            Log.d(TAG, "requestNextAdditionalPermission: Индекс=$currentPermissionIndex, Всего=${additionalPermissions.size}")
            if (currentPermissionIndex < additionalPermissions.size) {
                val nextPermission = additionalPermissions[currentPermissionIndex]

                if (ContextCompat.checkSelfPermission(this, nextPermission) != PackageManager.PERMISSION_GRANTED &&
                    isPermissionSupported(nextPermission)) {

                    Log.d(TAG, "Requesting additional permission: $nextPermission")
                    additionalPermissionLauncher.launch(nextPermission)
                } else {
                    Log.d(TAG, "requestNextAdditionalPermission: Разрешение $nextPermission уже предоставлено или не поддерживается")
                    currentPermissionIndex++
                    requestNextAdditionalPermission()
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

    // --- ИЗМЕНЕНО: Инициализация сервисов в фоновом потоке ---
    private fun proceedWithMainInitialization() {
        Log.d(TAG, "Proceeding with main initialization. isFirstLaunch=$isFirstLaunch")

        if (isFirstLaunch) {
            Log.d(TAG, "proceedWithMainInitialization: Запуск инициализации сервисов в фоне")
            // Запускаем корутину в Main scope, чтобы handler.postDelayed работал
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    delay(1000) // 1 секунда задержка перед запуском фоновой задачи
                    // Сама инициализация в IO потоке
                    withContext(Dispatchers.IO) {
                        Log.d(TAG, "proceedWithMainInitialization: Отложенное выполнение initializeServicesSafely в фоне")
                        initializeServicesSafely()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in delayed initialization", e)
                }
            }
            isFirstLaunch = false
        } else {
            Log.d(TAG, "proceedWithMainInitialization: Не первый запуск, пропуск инициализации сервисов")
        }
    }

    // --- ИЗМЕНЕНО: Инициализация сервисов в фоновом потоке ---
    private suspend fun initializeServicesSafely() {
        Log.d(TAG, "initializeServicesSafely: Начало (в фоновом потоке)")
        // Эти методы теперь выполняются в Dispatchers.IO или запускаются в Main Dispatcher через корутины

        try {
            Log.d(TAG, "initializeServicesSafely: Инициализация сервиса локации")
            if (hasLocationPermissions()) {
                // Запуск сервиса требует main thread, используем корутину в Main
                withContext(Dispatchers.Main) {
                    startLocationUpdateService()
                }
            } else {
                Log.w(TAG, "Location permissions not granted, skipping service start")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location service initialization failed", e)
        }

        try {
            Log.d(TAG, "initializeServicesSafely: Инициализация сервиса шагомера")
            if (hasStepPermissions()) {
                // Запуск сервиса требует main thread, используем корутину в Main
                withContext(Dispatchers.Main) {
                    startStepCounterService()
                }
            } else {
                Log.w(TAG, "Step permissions not granted, skipping service start")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Step service initialization failed", e)
        }

        // WorkManager также запускаем с задержкой, но в фоне
        // Запускаем корутину в Main scope, чтобы handler.postDelayed работал
        CoroutineScope(Dispatchers.Main).launch {
            delay(3000) // 3 секунды задержка
            // Сама работа с WorkManager в IO
            withContext(Dispatchers.IO) {


                try {
                    Log.d(TAG, "initializeServicesSafely: Отложенное выполнение schedulePeriodicStepWork")
                    schedulePeriodicStepWork()
                } catch (e: Exception) {
                    Log.e(TAG, "Step WorkManager scheduling failed", e)
                }


            }
        }
        Log.d(TAG, "initializeServicesSafely: Завершено (в фоне)")
    }

    // --- БЕЗОПАСНЫЕ МЕТОДЫ СЕРВИСОВ ---
    private fun initializeLocationService() {
        try {
            Log.d(TAG, "initializeLocationService: Проверка разрешений")
            if (hasLocationPermissions()) {
                Log.d(TAG, "initializeLocationService: Разрешения есть, запуск сервиса")
                startLocationUpdateService()
            } else {
                Log.w(TAG, "Location permissions not granted, skipping service start")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location service init error", e)
        }
    }

    private fun initializeStepService() {
        try {
            Log.d(TAG, "initializeStepService: Проверка разрешений")
            if (hasStepPermissions()) {
                Log.d(TAG, "initializeStepService: Разрешения есть, запуск сервиса")
                startStepCounterService()
            } else {
                Log.w(TAG, "Step permissions not granted, skipping service start")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Step service init error", e)
        }
    }

    private fun hasLocationPermissions(): Boolean {
        return try {
            val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val result = hasFine || hasCoarse
            Log.d(TAG, "hasLocationPermissions: ACCESS_FINE_LOCATION=$hasFine, ACCESS_COARSE_LOCATION=$hasCoarse, Result=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error checking location permissions", e)
            false
        }
    }

    override fun onResume() {
        super.onResume()

        // Перепроверяем статус трекинга при возвращении в приложение
        if (auth.currentUser != null) {
            LocationServiceManager.isTrackingActive(this) { isTracking ->
                Log.d(TAG, "onResume: Статус трекинга - $isTracking")
                // Можно обновить UI если нужно
            }
        }
    }

    private fun hasStepPermissions(): Boolean {
        return try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasActivity = ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "hasStepPermissions: ACTIVITY_RECOGNITION=$hasActivity")
                hasActivity
            } else {
                Log.d(TAG, "hasStepPermissions: Android < Q, считаем разрешения предоставленными")
                true
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error checking step permissions", e)
            false
        }
    }

    private fun startLocationUpdateService() {
        try {
            Log.d(TAG, "startLocationUpdateService: Создание Intent")
            val serviceIntent = Intent(this, LocationUpdateService::class.java).apply {
                action = LocationUpdateService.ACTION_START
            }

            Log.d(TAG, "startLocationUpdateService: Запуск сервиса")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d(TAG, "Location service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location service", e)
        }
    }

    private fun startStepCounterService() {
        try {
            Log.d(TAG, "startStepCounterService: Запуск через статический метод")
            StepCounterService.startService(this)
            Log.d(TAG, "Step service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start step service", e)
        }
    }

    private fun schedulePeriodicStepWork() {
        try {
            Log.d(TAG, "schedulePeriodicStepWork: Начало")
            val workManager = WorkManager.getInstance(this)
            val constraints = Constraints.Builder().build()

            val periodicWorkRequest = PeriodicWorkRequestBuilder<StepCounterServiceWorker>(
                STEP_SERVICE_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).setConstraints(constraints).build()

            workManager.enqueueUniquePeriodicWork(
                STEP_SERVICE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWorkRequest
            )
            Log.d(TAG, "Step WorkManager scheduled successfully every $STEP_SERVICE_INTERVAL_MINUTES minutes")
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
                    Log.d(TAG, "setupToolbar: Кнопка музыки нажата")
                    startActivity(Intent(this, MusicMainActivity::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting MusicActivity", e)
                }
            }

            binding.btnMenu.setOnClickListener { view ->
                Log.d(TAG, "setupToolbar: Кнопка меню нажата")
                showPopupMenu(view)
            }

            binding.ivUserAvatar.setOnClickListener {
                try {
                    Log.d(TAG, "setupToolbar: Аватар нажат")
                    startActivity(Intent(this, ProfileActivity::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting ProfileActivity", e)
                }
            }
            binding.tvUserName.setOnClickListener {
                try {
                    Log.d(TAG, "setupToolbar: Имя пользователя нажато")
                    startActivity(Intent(this, ProfileActivity::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting ProfileActivity", e)
                }
            }
            Log.d(TAG, "setupToolbar: Завершено")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up toolbar", e)
        }
    }

    private fun loadCurrentUserData() {
        try {
            Log.d(TAG, "loadCurrentUserData: Начало")
            val currentUserId = auth.currentUser?.uid ?: run {
                Log.w(TAG, "loadCurrentUserData: Пользователь не авторизован")
                return
            }
            val database = Firebase.database.reference

            database.child("users").child(currentUserId).addListenerForSingleValueEvent(
                object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        try {
                            Log.d(TAG, "loadCurrentUserData: onDataChange вызван")
                            if (snapshot.exists()) {
                                val user = snapshot.getValue(User::class.java)
                                user?.let {
                                    Log.d(TAG, "loadCurrentUserData: Данные пользователя получены, обновление UI")
                                    // runOnUiThread НЕ НУЖЕН - onDataChange уже в main thread
                                    updateToolbarUserInfo(it)
                                }
                            } else {
                                Log.d(TAG, "loadCurrentUserData: Данные пользователя не найдены")
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
            Log.d(TAG, "updateToolbarUserInfo: Обновление информации пользователя")
            binding.tvUserName.text = user.getFullName()
            user.profileImageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                Log.d(TAG, "updateToolbarUserInfo: Загрузка аватара из $url")
                Glide.with(this)
                    .load(url)
                    .circleCrop()
                    .placeholder(R.drawable.ic_default_profile)
                    .error(R.drawable.ic_default_profile)
                    // Добавлено для более агрессивного кэширования
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(binding.ivUserAvatar)
            } ?: run {
                Log.d(TAG, "updateToolbarUserInfo: URL аватара пуст, установка дефолтного")
                binding.ivUserAvatar.setImageResource(R.drawable.ic_default_profile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating toolbar user info", e)
            binding.ivUserAvatar.setImageResource(R.drawable.ic_default_profile)
        }
    }

    private fun showPopupMenu(view: View) {
        try {
            Log.d(TAG, "showPopupMenu: Показ меню")
            val popup = PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_profile -> {
                        try {
                            Log.d(TAG, "showPopupMenu: Выбран пункт Профиль")
                            startActivity(Intent(this, ProfileActivity::class.java))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting ProfileActivity", e)
                        }
                        true
                    }
                    R.id.menu_mozgi -> {
                        try {
                            Log.d(TAG, "showPopupMenu: Выбран пункт Мозги")
                            startActivity(Intent(this, com.example.chatapp.mozgi.ui.CategoriesActivity::class.java))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting CategoriesActivity", e)
                        }
                        true
                    }
                    R.id.menu_alarm -> {
                        try {
                            Log.d(TAG, "showPopupMenu: Выбран пункт Будильник")
                            startActivity(Intent(this, AlarmActivity::class.java))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting AlarmActivity", e)
                        }
                        true
                    }
                    R.id.menu_logout -> {
                        Log.d(TAG, "showPopupMenu: Выбран пункт Выход")
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
        try {
            Log.d(TAG, "logoutUser: Начало процесса выхода")
            try {
                Log.d(TAG, "logoutUser: Отмена WorkManager задач")
                WorkManager.getInstance(this).cancelUniqueWork(LOCATION_SERVICE_WORK_NAME)
                WorkManager.getInstance(this).cancelUniqueWork(STEP_SERVICE_WORK_NAME)
                WorkManager.getInstance(this).cancelUniqueWork(SERVICE_MONITOR_WORK_NAME)
            } catch (e: Exception) {
                Log.w(TAG, "Error canceling work manager jobs", e)
            }

            try {
                Log.d(TAG, "logoutUser: Остановка сервиса локации")
                stopService(Intent(this, LocationUpdateService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping location service", e)
            }

            try {
                Log.d(TAG, "logoutUser: Остановка сервиса шагомера")
                stopService(Intent(this, StepCounterService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping step service", e)
            }

            Log.d(TAG, "logoutUser: Выход из Firebase")
            auth.signOut()

            Log.d(TAG, "logoutUser: Переход к AuthActivity")
            startActivity(Intent(this, AuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error during logout", e)
            try {
                startActivity(Intent(this, AuthActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            } catch (e2: Exception) {
                Log.e(TAG, "Critical error during logout", e2)
            }
        }
    }

    private fun setupBottomNavigation() {
        try {
            Log.d(TAG, "setupBottomNavigation: Начало")
            bottomNav = binding.bottomNavigation

            // Обработчик выбора вкладки
            bottomNav.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_home -> {
                        Log.d(TAG, "setupBottomNavigation: Выбрана вкладка Домой")
                        loadFragment(HomeFragment(), HOME_FRAGMENT_TAG)
                        true
                    }
                    R.id.nav_gigachat -> {
                        Log.d(TAG, "setupBottomNavigation: Выбрана вкладка ГигаЧат")
                        loadFragment(ChatWithGigaFragment(), CHAT_FRAGMENT_TAG)
                        true
                    }
                    R.id.nav_steps -> {
                        Log.d(TAG, "setupBottomNavigation: Выбрана вкладка Шаги")
                        loadFragment(StepCounterFragment(), STEPS_FRAGMENT_TAG)
                        true
                    }
                    R.id.nav_maps -> {
                        Log.d(TAG, "setupBottomNavigation: Выбрана вкладка Карта")
                        loadFragment(LocationPagerFragment(), MAPS_FRAGMENT_TAG)
                        true
                    }
                    R.id.nav_games -> {
                        Log.d(TAG, "setupBottomNavigation: Выбрана вкладка Игры")
                        loadFragment(GamesFragment(), GAMES_FRAGMENT_TAG)
                        true
                    }
                    else -> {
                        Log.d(TAG, "setupBottomNavigation: Неизвестная вкладка itemId=${item.itemId}")
                        false
                    }
                }
            }

            // Обработчик ПОВТОРНОГО нажатия на вкладку
            bottomNav.setOnItemReselectedListener { item ->
                when (item.itemId) {
                    R.id.nav_home -> {
                        Log.d(TAG, "setupBottomNavigation: ПОВТОРНОЕ нажатие на вкладку Домой")
                        scrollNewsToTop()
                    }
                    // Можно добавить обработку для других вкладок при необходимости
                    R.id.nav_gigachat -> {
                        Log.d(TAG, "setupBottomNavigation: ПОВТОРНОЕ нажатие на вкладку ГигаЧат")
                        // Можно добавить скролл чатов к началу
                    }
                }
            }

            Log.d(TAG, "setupBottomNavigation: Завершено")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up bottom navigation", e)
        }
    }

    /**
     * Скроллит новости к началу, если открыта вкладка новостей
     */
    private fun scrollNewsToTop() {
        try {
            Log.d(TAG, "scrollNewsToTop: Попытка скролла новостей к началу")

            // Получаем текущий фрагмент HomeFragment
            val homeFragment = supportFragmentManager.findFragmentByTag(HOME_FRAGMENT_TAG) as? HomeFragment
            if (homeFragment != null && homeFragment.isVisible) {
                Log.d(TAG, "scrollNewsToTop: HomeFragment найден и видим, скроллим новости")
                homeFragment.scrollNewsToTop()
            } else {
                Log.d(TAG, "scrollNewsToTop: HomeFragment не найден или не видим")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling news to top", e)
        }
    }

    private fun loadFragment(fragment: Fragment, tag: String) {
        try {
            Log.d(TAG, "loadFragment: Загрузка фрагмента с тегом $tag")
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment, tag)
                .commitAllowingStateLoss()
            Log.d(TAG, "loadFragment: Фрагмент $tag загружен")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading fragment: $tag", e)
        }
    }

    // Методы для открытия фрагментов новостей
    fun openCreateNewsFragment() {
        try {
            Log.d(TAG, "openCreateNewsFragment: Открытие фрагмента создания новости")
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
            Log.d(TAG, "openEditNewsFragment: Открытие фрагмента редактирования новости")
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
            Log.d(TAG, "openFullScreenImage: Открытие полноэкранного изображения")
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
            Log.d(TAG, "onNewsCreated: Новость создана")
            Toast.makeText(this, "Новость опубликована!", Toast.LENGTH_SHORT).show()
            supportFragmentManager.popBackStack("create_news", 1)
            switchToNewsTab()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling news creation", e)
        }
    }

    private fun switchToNewsTab() {
        try {
            Log.d(TAG, "switchToNewsTab: Переключение на вкладку новостей")
            val homeFragment = HomeFragment().apply {
                arguments = Bundle().apply {
                    putBoolean("open_news_tab", true)
                }
            }
            loadFragment(homeFragment, HOME_FRAGMENT_TAG)
            binding.bottomNavigation.selectedItemId = R.id.nav_home
        } catch (e: Exception) {
            Log.e(TAG, "Error switching to news tab", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "MainActivity destroyed")
        super.onDestroy()
    }
}