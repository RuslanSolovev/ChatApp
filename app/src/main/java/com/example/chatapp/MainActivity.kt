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
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
import java.text.SimpleDateFormat
import java.util.*
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

    // Переменные для приветственного виджета
    private lateinit var welcomeCard: CardView
    private lateinit var tvWelcomeTitle: TextView
    private lateinit var tvWelcomeMessage: TextView
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
        private const val TRACKING_CHECK_TIMEOUT = 5000L
        private const val SERVICE_INIT_TIMEOUT = 10000L
        private const val PERMISSION_REQUEST_DELAY = 300L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            setupExceptionHandler()
            makeSystemBarsTransparent()

            auth = Firebase.auth
            if (auth.currentUser == null) {
                Log.d(TAG, "onCreate: Пользователь не авторизован, переход к AuthActivity")
                redirectToAuth()
                return
            }

            restartServicesAfterCrash()

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            WindowCompat.setDecorFitsSystemWindows(window, false)

            handleSystemBarsInsets()

            // Инициализация приветственного виджета
            initWelcomeWidget()

            setupToolbar()
            setupBottomNavigation()
            loadCurrentUserData() // ПРИВЕТСТВИЕ БУДЕТ ПОКАЗАНО ПОСЛЕ ЗАГРУЗКИ ДАННЫХ

            // Загрузка начального фрагмента без задержек
            if (savedInstanceState == null) {
                loadFragment(HomeFragment(), HOME_FRAGMENT_TAG)
                binding.bottomNavigation.selectedItemId = R.id.nav_home
            }

            if (savedInstanceState == null && isFirstLaunch) {
                Log.d(TAG, "onCreate: Первый запуск, проверка разрешений")
                handler.postDelayed({
                    checkAndRequestMainPermissions()
                }, 1000)
            } else {
                Log.d(TAG, "onCreate: Не первый запуск или есть состояние")
                proceedWithMainInitialization()
            }

            checkTrackingStatusAsync()

            // Отладочная информация о пользователе
            handler.postDelayed({
                debugUserData()
            }, 2000)

        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate", e)
            showErrorAndFinish("Ошибка запуска приложения")
        }
    }



    /**
     * Инициализация приветственного виджета
     */
    /**
     * Инициализация приветственного виджета
     */
    private fun initWelcomeWidget() {
        try {
            welcomeCard = binding.welcomeCard
            tvWelcomeTitle = binding.tvWelcomeTitle
            tvWelcomeMessage = binding.tvWelcomeMessage
            btnStartChat = binding.btnStartChat
            btnMaybeLater = binding.btnMaybeLater
            btnCloseWelcome = binding.btnCloseWelcome

            // Настройка кликов
            btnStartChat.setOnClickListener {
                Log.d(TAG, "Start chat clicked")
                hideWelcomeMessage()

                // Получаем и сохраняем приветственную фразу
                val welcomePhrase = getWelcomePhraseForChat()
                saveWelcomePhraseForChat(welcomePhrase)

                // Переходим к чату
                switchToFragment(CHAT_FRAGMENT_TAG) { ChatWithGigaFragment() }
                binding.bottomNavigation.selectedItemId = R.id.nav_gigachat
            }

            btnMaybeLater.setOnClickListener {
                Log.d(TAG, "Maybe later clicked")
                hideWelcomeMessage()
            }

            btnCloseWelcome.setOnClickListener {
                Log.d(TAG, "Close welcome clicked")
                hideWelcomeMessage()
            }

            Log.d(TAG, "Welcome widget initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing welcome widget", e)
        }
    }

    /**
     * Показ приветственного сообщения
     */
    private fun showWelcomeMessage() {
        try {
            val userName = getUserName()
            val welcomeData = generateWelcomeMessage(userName)

            tvWelcomeTitle.text = welcomeData.first
            tvWelcomeMessage.text = welcomeData.second

            welcomeCard.visibility = View.VISIBLE

            Log.d(TAG, "Welcome message shown: ${welcomeData.first}")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing welcome message", e)
        }
    }

    /**
     * Скрытие приветственного сообщения
     */
    private fun hideWelcomeMessage() {
        try {
            welcomeCard.visibility = View.GONE
            Log.d(TAG, "Welcome message hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding welcome message", e)
        }
    }

    /**
     * Получение приветственной фразы для начала диалога
     */
    fun getWelcomePhraseForChat(): String {
        val userName = getUserName()
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        val greeting = when (hour) {
            in 5..8 -> "Доброе утро"
            in 9..11 -> "Доброе утро"
            in 12..16 -> "Добрый день"
            in 17..21 -> "Добрый вечер"
            in 22..23 -> "Доброй ночи"
            else -> "Доброй ночи"
        }

        val message = when (hour) {
            in 5..8 -> listOf(
                "Как ваши дела? Собираетесь на работу?",
                "Прекрасное утро для новых начинаний!",
                "Как вы спали? Готовы к продуктивному дню?",
                "Утренний кофе уже в планах?",
                "Какие цели на сегодня ставите?",
                "Надеюсь, вы хорошо выспались!",
                "Прекрасный день для великих дел!",
                "Как начинается ваше утро?",
                "Готовы покорять новые вершины?",
                "Что интересного планируете на сегодня?"
            ).random()

            in 9..11 -> listOf(
                "Как начинается ваш день? Есть планы на сегодня?",
                "Рабочий процесс уже запущен?",
                "Успели сделать что-то важное?",
                "Какие задачи в приоритете?",
                "Нужна помощь с организацией дня?",
                "Как продвигаются ваши проекты?",
                "Утро - лучшее время для сложных задач!",
                "Что мотивирует вас сегодня?",
                "Уже нашли время для себя?",
                "Какие встречи или дела запланированы?"
            ).random()

            in 12..13 -> listOf(
                "Приятного аппетита! Хорошего обеда!",
                "Время подкрепиться и набраться сил!",
                "Какой обед сегодня? Что-то вкусное?",
                "Надеюсь, найдете время отдохнуть за обедом!",
                "Отличный повод сделать паузу в работе!",
                "Что планируете на вторую половину дня?",
                "Время восстановить энергию!",
                "Как ваш аппетит сегодня?",
                "Надеюсь, обед будет вкусным!",
                "Отличное время для небольшого перерыва!"
            ).random()

            in 14..16 -> listOf(
                "Как проходит ваш день? Нужна помощь с задачами?",
                "Послеобеденная продуктивность на высоте?",
                "Какие проекты в работе?",
                "Успеваете выполнить все запланированное?",
                "Нужен перерыв или совет?",
                "Как ваше внимание и концентрация?",
                "Что интересного происходит сегодня?",
                "Есть ли сложные задачи, где могу помочь?",
                "Как ваше настроение во второй половине дня?",
                "Планируете что-то интересное на вечер?"
            ).random()

            in 17..18 -> listOf(
                "Рабочий день подходит к концу. Как он прошел?",
                "Успели завершить все важные задачи?",
                "Какие итоги дня? Довольны результатами?",
                "Что было самым интересным сегодня?",
                "Планируете отдых после работы?",
                "Как ваша усталость после рабочего дня?",
                "Что порадовало вас сегодня?",
                "Готовы к вечернему отдыху?",
                "Какие планы на оставшийся вечер?",
                "Удалось ли сделать все запланированное?"
            ).random()

            in 19..21 -> listOf(
                "Как прошел ваш день? Отдыхаете после работы?",
                "Чем планируете заняться вечером?",
                "Нашли время для себя и хобби?",
                "Как ваше настроение после рабочего дня?",
                "Что помогает вам расслабиться вечером?",
                "Планируете ли ужин с близкими?",
                "Есть ли интересные вечерние планы?",
                "Как ваша энергия после дня?",
                "Что принесло вам радость сегодня?",
                "Нужен совет по вечерним занятиям?"
            ).random()

            in 22..23 -> listOf(
                "Спокойной ночи! Хороших снов!",
                "Время готовиться ко сну! Выспитесь хорошо!",
                "Удалось отдохнуть за вечер?",
                "Какие планы на завтрашний день?",
                "Надеюсь, день был продуктивным!",
                "Готовы к сладким снам?",
                "Что порадовало вас перед сном?",
                "Успели завершить все вечерние дела?",
                "Спите крепко и набирайтесь сил!",
                "Завтра будет новый прекрасный день!"
            ).random()

            else -> listOf(
                "Позднее время... Не забывайте отдыхать!",
                "Вы еще не спите? Надеюсь, все в порядке!",
                "Поздний час - время для размышлений...",
                "Не забывайте, что сон важен для здоровья!",
                "Чем занимаетесь в такое позднее время?",
                "Надеюсь, у вас есть возможность отдохнуть!",
                "Поздняя работа или интересное занятие?",
                "Не перетруждайтесь, здоровье важнее!",
                "Что держит вас бодрствующим так поздно?",
                "Надеюсь, найдете время для полноценного сна!"
            ).random()
        }

        return "$greeting, $userName! $message"
    }

    /**
     * Сохраняет приветственную фразу для использования в чате
     */
    private fun saveWelcomePhraseForChat(phrase: String) {
        val sharedPref = getSharedPreferences("chat_prefs", MODE_PRIVATE)
        sharedPref.edit().putString("welcome_phrase", phrase).apply()
        Log.d(TAG, "Welcome phrase saved for chat: $phrase")
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
        val sharedPref = getSharedPreferences("chat_prefs", MODE_PRIVATE)
        sharedPref.edit().remove("welcome_phrase").apply()
        Log.d(TAG, "Welcome phrase cleared")
    }




    /**
     * Генерация персонализированного приветствия с учетом времени суток
     */
    private fun generateWelcomeMessage(userName: String): Pair<String, String> {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dateFormat = SimpleDateFormat("dd MMMM", Locale("ru"))
        val currentDate = dateFormat.format(Date())

        val greeting = when (hour) {
            in 5..8 -> "Доброе утро"
            in 9..11 -> "Доброе утро"
            in 12..16 -> "Добрый день"
            in 17..21 -> "Добрый вечер"
            in 22..23 -> "Доброй ночи"
            else -> "Доброй ночи"
        }

        val title = "$greeting, $userName!"

        // Увеличенный в 3 раза набор персонализированных сообщений
        val message = when (hour) {
            in 5..8 -> listOf(
                "Как ваши дела? Собираетесь на работу?",
                "Прекрасное утро для новых начинаний!",
                "Как вы спали? Готовы к продуктивному дню?",
                "Утренний кофе уже в планах?",
                "Какие цели на сегодня ставите?",
                "Надеюсь, вы хорошо выспались!",
                "Прекрасный день для великих дел!",
                "Как начинается ваше утро?",
                "Готовы покорять новые вершины?",
                "Что интересного планируете на сегодня?",
                "Утро - время самых смелых идей!",
                "Как ваше настроение в этот ранний час?",
                "Надеюсь, день начнется прекрасно!",
                "Что вдохновляет вас сегодня утром?",
                "Готовы к новым вызовам?",
                "Какие планы на утреннюю пробежку?",
                "Успели позавтракать?",
                "Что читаете или слушаете с утра?",
                "Как ваша энергия после пробуждения?",
                "Что мотивирует вас вставать рано?",
                "Планируете ли утреннюю медитацию?",
                "Какая погода за окном?",
                "Что сделает ваш день идеальным?",
                "Есть ли утренние ритуалы?",
                "Как начинается ваше утро?",
                "Что нового узнаете сегодня?",
                "Готовы к утренним встречам?",
                "Какие задачи самые приоритетные?",
                "Что вдохновляет вас сегодня?",
                "Как ваше самочувствие с утра?",
                "Планируете ли спортивные занятия?",
                "Что поможет настроиться на день?",
                "Какие мысли с утра?",
                "Готовы к рабочим задачам?",
                "Что порадует вас сегодня?",
                "Какой настрой на день?",
                "Что важное предстоит сделать?",
                "Есть ли время для себя утром?",
                "Какие утренние привычки?",
                "Что помогает проснуться?",
                "Какой завтрак предпочитаете?",
                "Планируете ли утренние звонки?",
                "Что читаете с утра?",
                "Как проводите первые часы дня?",
                "Что вдохновляет на подвиги?",
                "Готовы к новым открытиям?"
            ).random()

            in 9..11 -> listOf(
                "Как начинается ваш день? Есть планы на сегодня?",
                "Рабочий процесс уже запущен?",
                "Успели сделать что-то важное?",
                "Какие задачи в приоритете?",
                "Нужна помощь с организацией дня?",
                "Как продвигаются ваши проекты?",
                "Утро - лучшее время для сложных задач!",
                "Что мотивирует вас сегодня?",
                "Уже нашли время для себя?",
                "Какие встречи или дела запланированы?",
                "Как ваша продуктивность сегодня?",
                "Что интересного произошло с утра?",
                "Нужен совет или помощь?",
                "Как ваше творческое настроение?",
                "Что вдохновляет вас в этот час?",
                "Успели навести порядок в делах?",
                "Какие новости сегодня утром?",
                "Как ваша концентрация?",
                "Что помогает сосредоточиться?",
                "Есть ли сложные задачи?",
                "Как проходит утренняя работа?",
                "Что уже успели завершить?",
                "Какие планы на обед?",
                "Нужен перерыв?",
                "Как ваше вдохновение?",
                "Что изучаете сегодня?",
                "Какие встречи предстоят?",
                "Как ваше настроение?",
                "Что радует сегодня?",
                "Какие цели на день?",
                "Уже есть результаты?",
                "Что помогает работать продуктивно?",
                "Какой ритм работы сегодня?",
                "Есть ли время для обучения?",
                "Что нового пробуете?",
                "Какие вызовы сегодня?",
                "Как поддерживаете энергию?",
                "Что читаете в перерывах?",
                "Какие идеи приходят?",
                "Как организуете рабочее пространство?",
                "Что мотивирует двигаться вперед?",
                "Какие маленькие победы уже есть?",
                "Как балансируете работу и отдых?",
                "Что помогает не отвлекаться?",
                "Какие приоритеты сегодня?"
            ).random()

            in 12..13 -> listOf(
                "Приятного аппетита! Хорошего обеда!",
                "Время подкрепиться и набраться сил!",
                "Какой обед сегодня? Что-то вкусное?",
                "Надеюсь, найдете время отдохнуть за обедом!",
                "Отличный повод сделать паузу в работе!",
                "Что планируете на вторую половину дня?",
                "Время восстановить энергию!",
                "Как ваш аппетит сегодня?",
                "Надеюсь, обед будет вкусным!",
                "Отличное время для небольшого перерыва!",
                "Успели проголодаться за утро?",
                "Что сегодня в меню?",
                "Время зарядиться энергией на весь день!",
                "Как проходит ваш обеденный перерыв?",
                "Наслаждайтесь моментом отдыха!",
                "Что готовите на обед?",
                "Планируете обед с коллегами?",
                "Какой ваш любимый обед?",
                "Успеваете отдохнуть во время обеда?",
                "Что пьете во время обеда?",
                "Какой ресторан или кафе предпочитаете?",
                "Готовите ли сами обед?",
                "Что нового пробуете в еде?",
                "Как сочетаете работу и обед?",
                "Что читаете за обедом?",
                "Слушаете ли музыку во время еды?",
                "Общаетесь ли за обедом?",
                "Какой десерт планируете?",
                "Что вдохновляет в кулинарии?",
                "Какой напиток выбираете?",
                "Планируете ли послеобеденную прогулку?",
                "Как восстанавливаете силы?",
                "Что помогает расслабиться?",
                "Какие кулинарные предпочтения?",
                "Что нового в питании?",
                "Как поддерживаете баланс в еде?",
                "Что открыли в кулинарии?",
                "Какой ваш идеальный обед?",
                "Что пожелать на десерт?",
                "Как наслаждаетесь едой?",
                "Что важно в обеденном перерыве?",
                "Как делаете обед особенным?",
                "Что пробуете впервые?",
                "Как сочетаете вкусы?",
                "Что радует в еде сегодня?"
            ).random()

            in 14..16 -> listOf(
                "Как проходит ваш день? Нужна помощь с задачами?",
                "Послеобеденная продуктивность на высоте?",
                "Какие проекты в работе?",
                "Успеваете выполнить все запланированное?",
                "Нужен перерыв или совет?",
                "Как ваше внимание и концентрация?",
                "Что интересного происходит сегодня?",
                "Есть ли сложные задачи, где могу помочь?",
                "Как ваше настроение во второй половине дня?",
                "Планируете что-то интересное на вечер?",
                "Уже видите результаты сегодняшней работы?",
                "Что вдохновляет вас продолжать?",
                "Нужна помощь с планированием?",
                "Как ваша энергия после обеда?",
                "Что самое интересное произошло сегодня?",
                "Какие задачи остались?",
                "Как поддерживаете фокус?",
                "Что помогает не уставать?",
                "Какие встречи еще предстоят?",
                "Как справляетесь с рутиной?",
                "Что изучаете во второй половине дня?",
                "Какие творческие задачи?",
                "Как организуете время?",
                "Что мотивирует завершать дела?",
                "Какие перерывы делаете?",
                "Как восстанавливаете концентрацию?",
                "Что читаете в течение дня?",
                "Какие навыки развиваете?",
                "Как справляетесь со стрессом?",
                "Что радует во второй половине дня?",
                "Какие маленькие радости?",
                "Как балансируете задачи?",
                "Что помогает сохранять энергию?",
                "Какие планы на после работы?",
                "Как завершаете рабочие дела?",
                "Что успели сделать сегодня?",
                "Какие достижения сегодня?",
                "Что вдохновляет на новые идеи?",
                "Как поддерживаете продуктивность?",
                "Что помогает не откладывать дела?",
                "Какие привычки помогают работать?",
                "Как организуете рабочее пространство?",
                "Что делает день успешным?",
                "Какие цели близки к завершению?",
                "Что планируете на завтра?"
            ).random()

            in 17..18 -> listOf(
                "Рабочий день подходит к концу. Как он прошел?",
                "Успели завершить все важные задачи?",
                "Какие итоги дня? Довольны результатами?",
                "Что было самым интересным сегодня?",
                "Планируете отдых после работы?",
                "Как ваша усталость после рабочего дня?",
                "Что порадовало вас сегодня?",
                "Готовы к вечернему отдыху?",
                "Какие планы на оставшийся вечер?",
                "Удалось ли сделать все запланированное?",
                "Что запомнилось больше всего сегодня?",
                "Как ваше настроение в конце дня?",
                "Нужно ли что-то завершить перед отдыхом?",
                "Что поможет вам расслабиться вечером?",
                "Гордитесь ли сегодняшними достижениями?",
                "Какие уроки извлекли сегодня?",
                "Что улучшили в работе?",
                "Какие встречи были полезными?",
                "Что открыли нового?",
                "Как отпразднуете завершение дня?",
                "Что планируете на ужин?",
                "Как проведете время с близкими?",
                "Какие хобби вечером?",
                "Что посмотрите или почитаете?",
                "Как восстановите силы?",
                "Какие планы на выходные?",
                "Что помогает переключиться с работы?",
                "Какой отдых предпочитаете?",
                "Что вдохновляет вечером?",
                "Какие вечерние ритуалы?",
                "Как готовитесь ко сну?",
                "Что проанализируете из дня?",
                "Какие благодарности сегодня?",
                "Что сделало день особенным?",
                "Какие моменты запомнятся?",
                "Что улучшите завтра?",
                "Какие цели на вечер?",
                "Как расслабляетесь после работы?",
                "Что читаете перед сном?",
                "Какие планы на завтра?",
                "Что помогает засыпать?",
                "Какой идеальный вечер?",
                "Что пожелать на ночь?",
                "Какие мысли перед сном?",
                "Что благодарны сегодня?"
            ).random()

            in 19..21 -> listOf(
                "Как прошел ваш день? Отдыхаете после работы?",
                "Чем планируете заняться вечером?",
                "Нашли время для себя и хобби?",
                "Как ваше настроение после рабочего дня?",
                "Что помогает вам расслабиться вечером?",
                "Планируете ли ужин с близкими?",
                "Есть ли интересные вечерние планы?",
                "Как ваша энергия после дня?",
                "Что принесло вам радость сегодня?",
                "Нужен совет по вечерним занятиям?",
                "Чем увлекаетесь в свободное время?",
                "Как проводите время с семьей или друзьями?",
                "Что читаете или смотрите вечером?",
                "Планируете ранний отход ко сну?",
                "Как восстанавливаете силы после дня?",
                "Какие фильмы или сериалы смотрите?",
                "Какую музыку слушаете?",
                "Чем занимаетесь для души?",
                "Какие вечерние прогулки?",
                "Что готовите на ужин?",
                "Как общаетесь с близкими?",
                "Какие игры играете?",
                "Что изучаете вечером?",
                "Как медитируете или расслабляетесь?",
                "Какие творческие занятия?",
                "Что вдохновляет вечером?",
                "Как готовитесь к следующему дню?",
                "Что планируете на выходные?",
                "Какие книги читаете?",
                "Какой спорт вечером?",
                "Что пробуете нового?",
                "Как развиваетесь вечером?",
                "Что помогает заснуть?",
                "Какие вечерние ритуалы?",
                "Что благодарны сегодня?",
                "Как анализируете день?",
                "Что улучшите завтра?",
                "Какие мечты и планы?",
                "Что радует вечером?",
                "Как создаете уют?",
                "Что пьете вечером?",
                "Какие разговоры с близкими?",
                "Что смотрите на ночь?",
                "Как прощаетесь с днем?",
                "Что пожелать на сон?"
            ).random()

            in 22..23 -> listOf(
                "Спокойной ночи! Хороших снов!",
                "Время готовиться ко сну! Выспитесь хорошо!",
                "Удалось отдохнуть за вечер?",
                "Какие планы на завтрашний день?",
                "Надеюсь, день был продуктивным!",
                "Готовы к сладким снам?",
                "Что порадовало вас перед сном?",
                "Успели завершить все вечерние дела?",
                "Спите крепко и набирайтесь сил!",
                "Завтра будет новый прекрасный день!",
                "Отдыхайте и восстанавливайте энергию!",
                "Пусть сны будут светлыми и добрыми!",
                "Надеюсь, завтрашний день принесет радость!",
                "Расслабьтесь и отдохните как следует!",
                "Желаю вам самого крепкого и здорового сна!",
                "Что читаете перед сном?",
                "Какой настрой на завтра?",
                "Что благодарны за сегодня?",
                "Какие цели на завтра?",
                "Как готовитесь ко сну?",
                "Что помогает заснуть?",
                "Какие мысли перед сном?",
                "Что пожелать близким?",
                "Какой был самый яркий момент дня?",
                "Что улучшите завтра?",
                "Какие мечты на ночь?",
                "Что вдохновляет перед сном?",
                "Как создаете атмосферу для сна?",
                "Что слушаете перед сном?",
                "Как медитируете вечером?",
                "Что планируете на утро?",
                "Какой идеальный сон?",
                "Что помогает проснуться бодрым?",
                "Какие сны желаете?",
                "Как прощаетесь с днем?",
                "Что важное сделали сегодня?",
                "Какие уроки извлекли?",
                "Что порадовало сегодня?",
                "Какие планы на неделю?",
                "Что благодарны вселенной?",
                "Как поддерживаете здоровый сон?",
                "Что читаете в кровати?",
                "Какой температурный режим для сна?",
                "Что помогает расслабиться?",
                "Какие ароматы для сна?"
            ).random()

            else -> listOf(
                "Позднее время... Не забывайте отдыхать!",
                "Вы еще не спите? Надеюсь, все в порядке!",
                "Поздний час - время для размышлений...",
                "Не забывайте, что сон важен для здоровья!",
                "Чем занимаетесь в такое позднее время?",
                "Надеюсь, у вас есть возможность отдохнуть!",
                "Поздняя работа или интересное занятие?",
                "Не перетруждайтесь, здоровье важнее!",
                "Что держит вас бодрствующим так поздно?",
                "Надеюсь, найдете время для полноценного сна!",
                "Поздние часы - время творческих озарений!",
                "Отдых тоже может быть продуктивным!",
                "Не забывайте восстанавливать силы!",
                "Что мотивирует вас бодрствовать?",
                "Надеюсь, скоро сможете хорошенько отдохнуть!",
                "Что изучаете ночью?",
                "Какие мысли приходят поздно вечером?",
                "Как поддерживаете энергию ночью?",
                "Что вдохновляет в ночные часы?",
                "Какие проекты создаете ночью?",
                "Как организуете ночную работу?",
                "Что читаете в ночи?",
                "Какая музыка ночью?",
                "Как справляетесь с бессонницей?",
                "Что помогает работать ночью?",
                "Какие идеи приходят ночью?",
                "Как балансируете сон и работу?",
                "Что важное делаете ночью?",
                "Какие ночные ритуалы?",
                "Как восстанавливаетесь после ночи?",
                "Что планируете на утро?",
                "Как готовитесь ко сну поздно?",
                "Что пьете ночью?",
                "Какие размышления ночью?",
                "Что изучаете о сне?",
                "Как поддерживаете здоровье при ночной работе?",
                "Что помогает сосредоточиться ночью?",
                "Какие открытия делаете ночью?",
                "Как справляетесь с усталостью?",
                "Что мотивирует не спать?",
                "Какие планы на сон?",
                "Как создаете ночную атмосферу?",
                "Что важно в ночные часы?",
                "Как находите вдохновение ночью?"
            ).random()
        }

        return Pair(title, "$message\nСегодня $currentDate")
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

    private fun updateToolbarUserInfo(user: User) {
        try {
            val fullName = user.getFullName()
            binding.tvUserName.text = fullName

            // Сохраняем имя в SharedPreferences для использования в приветствии
            val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
            sharedPref.edit().putString("user_name", fullName).apply()

            // Извлекаем только имя (первое слово) для персонализированного обращения
            val firstName = extractFirstName(fullName)
            sharedPref.edit().putString("first_name", firstName).apply()

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

            Log.d(TAG, "User info updated: $fullName (first name: $firstName)")
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

            // Убираем лишние пробелы и разделяем по пробелам
            val cleanedName = fullName.trim().replace(Regex("\\s+"), " ")
            val nameParts = cleanedName.split(" ")

            when {
                nameParts.isNotEmpty() && nameParts[0].isNotBlank() -> nameParts[0]
                else -> {
                    // Если первое слово пустое, ищем следующее непустое
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

            // Принудительная проверка всех возможных источников
            var firstName = sharedPref.getString("first_name", null)
            if (!firstName.isNullOrEmpty() && firstName != "Пользователь" && firstName != "NOT_SET") {
                return firstName
            }

            val fullName = sharedPref.getString("user_name", null)
            if (!fullName.isNullOrEmpty() && fullName != "NOT_SET") {
                val extractedFirstName = extractFirstName(fullName)
                if (extractedFirstName != "Пользователь") {
                    sharedPref.edit().putString("first_name", extractedFirstName).apply()
                    return extractedFirstName
                }
            }

            // Fallback к Firebase Auth
            val currentUser = auth.currentUser
            when {
                currentUser?.displayName?.isNotEmpty() == true -> {
                    val name = extractFirstName(currentUser.displayName!!)
                    sharedPref.edit().putString("first_name", name).apply()
                    name
                }
                currentUser?.email?.isNotEmpty() == true -> {
                    val name = currentUser.email!!.split("@").first()
                    sharedPref.edit().putString("first_name", name).apply()
                    name
                }
                else -> "Пользователь"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting user name", e)
            "Пользователь"
        }
    }

    private fun loadCurrentUserData() {
        try {
            Log.d(TAG, "loadCurrentUserData: Начало")
            val currentUserId = auth.currentUser?.uid
            if (currentUserId == null) {
                Log.e(TAG, "User ID is null")
                loadUserNameFromAuth()
                return
            }

            val database = Firebase.database.reference
            database.child("users").child(currentUserId).addListenerForSingleValueEvent(
                object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        try {
                            Log.d(TAG, "User data snapshot exists: ${snapshot.exists()}")
                            if (snapshot.exists()) {
                                val user = snapshot.getValue(User::class.java)
                                if (user != null) {
                                    Log.d(TAG, "User data loaded: ${user.getFullName()}")
                                    updateToolbarUserInfo(user)

                                    // Сохраняем имя для приветствия сразу после обновления
                                    val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
                                    sharedPref.edit().putString("user_name", user.getFullName()).apply()

                                    // Извлекаем и сохраняем только имя
                                    val firstName = extractFirstName(user.getFullName())
                                    sharedPref.edit().putString("first_name", firstName).apply()

                                    showWelcomeMessage()
                                } else {
                                    Log.e(TAG, "User object is null")
                                    loadUserNameFromAuth()
                                }
                            } else {
                                Log.w(TAG, "User data not found in database")
                                loadUserNameFromAuth()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing user data", e)
                            loadUserNameFromAuth()
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Failed to load user data: ${error.message}")
                        loadUserNameFromAuth()
                    }
                })
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user data", e)
            loadUserNameFromAuth()
        }
    }

    /**
     * Загрузка имени пользователя из Firebase Auth (fallback)
     */
    private fun loadUserNameFromAuth() {
        try {
            val currentUser = auth.currentUser
            val userName = when {
                currentUser?.displayName?.isNotEmpty() == true -> currentUser.displayName!!
                currentUser?.email?.isNotEmpty() == true -> currentUser.email!!.split("@").first()
                else -> "Пользователь"
            }

            // Сохраняем имя для использования в приветствии
            val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
            sharedPref.edit().putString("user_name", userName).apply()

            // Извлекаем и сохраняем только имя
            val firstName = extractFirstName(userName)
            sharedPref.edit().putString("first_name", firstName).apply()

            // Показываем приветствие с загруженным именем
            showWelcomeMessage()

        } catch (e: Exception) {
            Log.e(TAG, "Error loading user name from auth", e)
            showWelcomeMessage() // Все равно показываем приветствие
        }
    }

    /**
     * Отладочная информация о пользователе
     */
    private fun debugUserData() {
        try {
            val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
            val userName = sharedPref.getString("user_name", "NOT_SET")
            val firstName = sharedPref.getString("first_name", "NOT_SET")

            Log.d(TAG, "Debug - User name: $userName")
            Log.d(TAG, "Debug - First name: $firstName")
            Log.d(TAG, "Debug - Current user: ${auth.currentUser?.displayName ?: "NO_DISPLAY_NAME"}")
            Log.d(TAG, "Debug - Current user email: ${auth.currentUser?.email ?: "NO_EMAIL"}")
            Log.d(TAG, "Debug - Current user UID: ${auth.currentUser?.uid ?: "NO_UID"}")
        } catch (e: Exception) {
            Log.e(TAG, "Error in debugUserData", e)
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

    private fun makeSystemBarsTransparent() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.apply {
                    // БОЛЕЕ ПРОЗРАЧНЫЕ системные бары (полупрозрачные)
                    navigationBarColor = Color.parseColor("#10FFFFFF") // 12% прозрачности черного
                    statusBarColor = Color.parseColor("#10FFFFFF") // 12% прозрачности черного

                    // Разрешаем отрисовку контента под системными барами
                    decorView.systemUiVisibility = (
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            )

                    // Для Android 8.0+ - настройка цвета иконок
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        decorView.systemUiVisibility = decorView.systemUiVisibility or
                                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    }

                    // Для Android 6.0+ - светлый статус бар
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

                // Применяем отступы к toolbar
                binding.toolbar.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    topMargin = statusBarHeight
                }

                insets
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling system bars insets", e)
        }
    }

    private fun setupBottomNavigation() {
        try {
            bottomNav = binding.bottomNavigation

            // ДЕЛАЕМ ФОН ПОЛУПРОЗРАЧНЫМ
            bottomNav.background = ContextCompat.getDrawable(this, R.drawable.transparent_nav_background)

            // ДОБАВЛЯЕМ ЛЕГКУЮ ТЕНЬ ДЛЯ ГЛУБИНЫ
            bottomNav.elevation = 8f

            // ОБНОВЛЯЕМ ЦВЕТА ДЛЯ ЛУЧШЕЙ ВИДИМОСТИ НА ПОЛУПРОЗРАЧНОМ ФОНЕ
            bottomNav.itemIconTintList = ContextCompat.getColorStateList(this, R.color.bg_message_right)
            bottomNav.itemTextColor = ContextCompat.getColorStateList(this, R.color.bg_message_right)

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

            Log.d(TAG, "Bottom navigation setup completed with transparent background")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up bottom navigation", e)
        }
    }

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
            handler.postDelayed({
                isLocationServiceStarting.set(false)
            }, 2000)
        }
    }

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
        // Очищаем все отложенные задачи
        handler.removeCallbacksAndMessages(null)
        // Очищаем кэш фрагментов
        fragmentCache.clear()
        // Сбрасываем флаги запуска
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
}