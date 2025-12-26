package com.example.chatapp.activities

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.R
import com.example.chatapp.databinding.ActivityMainBinding
import com.example.chatapp.privetstvie_giga.*
import com.example.chatapp.utils.TTSManager
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.*
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class WelcomeManager(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private var ttsManager: TTSManager? = null
) : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = activity.lifecycleScope.coroutineContext

    private val handler = Handler(Looper.getMainLooper())

    // UI элементы приветственной карточки
    lateinit var welcomeCard: CardView
    lateinit var tvWelcomeTitle: TextView
    lateinit var tvWelcomeQuestion: TextView
    lateinit var tvWelcomeContext: TextView
    lateinit var btnStartChat: Button
    lateinit var btnMaybeLater: Button
    lateinit var btnCloseWelcome: ImageButton
    lateinit var progressWelcome: LinearProgressIndicator
    lateinit var welcomeContent: LinearLayout
    lateinit var ivWelcomeAvatar: ImageView

    // Анализаторы контекста
    private var contextAnalyzer: SmartContextAnalyzer? = null
    private var greetingGenerator: SmartQuestionGenerator? = null
    private var userProfile: UserProfile? = null

    // Флаги для отслеживания состояния приветствия
    private var isWelcomeSequenceRunning = false
    private var welcomeSequenceJob: Job? = null
    private var welcomeRetryCount = 0
    private val MAX_WELCOME_RETRIES = 3

    // AI анализ переменные
    private var aiAnalysisJob: Job? = null
    private var cachedAIContinuation: String? = null
    private var lastAIAnalysisTime: Long = 0
    private val AI_ANALYSIS_CACHE_TIME = 300000L // 5 минут

    // TTS переменные
    private var isTTSInitialized = false
    private var hasGreetingBeenSpoken = false
    private var hasAIContinuationBeenSpoken = false
    private var isActivityResumed = false

    // Флаг для отслеживания установки обработчиков
    private var areListenersSetup = false

    // Константы
    companion object {
        private const val TAG = "WelcomeManager"
        private const val WELCOME_STAGE_1_DELAY = 0L
        private const val WELCOME_STAGE_2_DELAY = 1500L
        private const val WELCOME_STAGE_3_DELAY = 3000L
        private const val WELCOME_RETRY_DELAY = 2000L
        private const val TTS_GREETING_DELAY = 500L
        private const val TTS_CONTINUATION_DELAY = 2500L
    }

    /**
     * Настройка карточки приветствия
     */
    fun setupWelcomeCard() {
        try {
            Log.d(TAG, "setupWelcomeCard: Инициализация элементов приветственной карточки")

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

            // ВАЖНО: Изначально устанавливаем видимость, но с прозрачностью
            handler.post {
                welcomeCard.visibility = View.VISIBLE
                welcomeCard.alpha = 0f

                progressWelcome.visibility = View.VISIBLE
                progressWelcome.alpha = 0f

                welcomeContent.visibility = View.VISIBLE
                welcomeContent.alpha = 0f

                // Включаем все элементы
                welcomeCard.isEnabled = true
                welcomeCard.isClickable = true
                welcomeCard.isFocusable = true

                btnStartChat.isEnabled = true
                btnStartChat.isClickable = true
                btnStartChat.isFocusable = true
                btnMaybeLater.isEnabled = true
                btnMaybeLater.isClickable = true
                btnMaybeLater.isFocusable = true
                btnCloseWelcome.isEnabled = true
                btnCloseWelcome.isClickable = true
                btnCloseWelcome.isFocusable = true

                Log.d(TAG, "setupWelcomeCard: Элементы инициализированы, видимость установлена")
            }

            // Настройка внешнего вида
            setupWelcomeCardAppearance()

            Log.d(TAG, "Welcome card setup completed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up welcome card", e)
        }
    }

    /**
     * Настройка внешнего вида приветственной карточки
     */
    private fun setupWelcomeCardAppearance() {
        try {
            Log.d(TAG, "setupWelcomeCardAppearance: Настройка внешнего вида карточки")

            // Устанавливаем цвет фона карточки
            welcomeCard.setCardBackgroundColor(Color.WHITE)
            welcomeCard.cardElevation = 16f
            welcomeCard.radius = activity.resources.getDimension(R.dimen.card_corner_radius)

            // Используем стандартный аватар для скорости
            ivWelcomeAvatar.setImageResource(R.drawable.ic_default_profile)

            // Устанавливаем фон и стили для кнопок
            setupButtonAppearance(btnStartChat, Color.parseColor("#6200EE"), "Начать общение")
            setupButtonAppearance(btnMaybeLater, Color.parseColor("#03DAC6"), "Позже")

            // Настройка кнопки закрытия
            btnCloseWelcome.setBackgroundColor(Color.TRANSPARENT)
            btnCloseWelcome.setImageResource(R.drawable.ic_close)
            btnCloseWelcome.contentDescription = "Закрыть приветствие"

            // Устанавливаем стили текста
            tvWelcomeTitle.setTextColor(Color.BLACK)
            tvWelcomeTitle.textSize = 20f
            tvWelcomeTitle.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)

            tvWelcomeQuestion.setTextColor(Color.parseColor("#424242"))
            tvWelcomeQuestion.textSize = 16f

            tvWelcomeContext.setTextColor(Color.parseColor("#757575"))
            tvWelcomeContext.textSize = 14f

            // Настройка прогресс-бара - УПРОЩЕННАЯ ВЕРСИЯ
            try {
                // Только устанавливаем цвет трека, цвет индикатора оставляем по умолчанию
                progressWelcome.trackColor = Color.parseColor("#E0E0E0")

                // ИЛИ если и это не работает, используем безопасный метод:
                // progressWelcome.setTrackColor(Color.parseColor("#E0E0E0"))

            } catch (e: Exception) {
                Log.e(TAG, "Error setting progress bar colors", e)
            }

            Log.d(TAG, "setupWelcomeCardAppearance: Внешний вид настроен успешно")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up welcome card appearance", e)
        }
    }

    /**
     * Настройка внешнего вида кнопки
     */
    private fun setupButtonAppearance(button: Button, color: Int, text: String) {
        try {
            // Создаем градиентный фон с закругленными углами
            val gradientDrawable = GradientDrawable()
            gradientDrawable.cornerRadius = 24f
            gradientDrawable.setColor(color)
            gradientDrawable.setStroke(2, Color.parseColor("#E0E0E0"))

            // Устанавливаем фон
            button.background = gradientDrawable

            // Устанавливаем текст
            button.text = text
            button.setTextColor(Color.WHITE)
            button.textSize = 16f
            button.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            button.isAllCaps = false

            // Устанавливаем отступы
            val padding = activity.resources.getDimensionPixelSize(R.dimen.button_padding)
            button.setPadding(padding, padding, padding, padding)

            // Устанавливаем минимальные размеры
            button.minHeight = 48.dpToPx()
            button.minWidth = 120.dpToPx()

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up button appearance", e)
        }
    }

    /**
     * Конвертация dp в px
     */
    private fun Int.dpToPx(): Int {
        return (this * activity.resources.displayMetrics.density).toInt()
    }

    /**
     * Установка тестовых обработчиков кликов (ТОЛЬКО как fallback)
     */
    private fun setupTestClickListenersAsFallback() {
        try {
            Log.d(TAG, "setupTestClickListenersAsFallback: Установка тестовых обработчиков")

            // Очищаем все предыдущие обработчики
            btnStartChat.setOnClickListener(null)
            btnMaybeLater.setOnClickListener(null)
            btnCloseWelcome.setOnClickListener(null)

            // Устанавливаем тестовые обработчики
            btnStartChat.setOnClickListener {
                Log.d(TAG, "FALLBACK TEST: Start Chat button clicked")
                Toast.makeText(activity, "Тест: Начать общение (рабочие обработчики не установлены)", Toast.LENGTH_LONG).show()
            }

            btnMaybeLater.setOnClickListener {
                Log.d(TAG, "FALLBACK TEST: Maybe Later button clicked")
                Toast.makeText(activity, "Тест: Позже (рабочие обработчики не установлены)", Toast.LENGTH_LONG).show()
            }

            btnCloseWelcome.setOnClickListener {
                Log.d(TAG, "FALLBACK TEST: Close Welcome button clicked")
                Toast.makeText(activity, "Тест: Закрыть (рабочие обработчики не установлены)", Toast.LENGTH_LONG).show()
            }

            Log.d(TAG, "Fallback test click listeners setup")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up fallback test click listeners", e)
        }
    }

    /**
     * Настройка слушателей для карточки приветствия
     */
    fun setupWelcomeCardListeners(
        onStartChatClicked: () -> Unit,
        onMaybeLaterClicked: () -> Unit,
        onCloseWelcomeClicked: () -> Unit
    ) {
        try {
            Log.d(TAG, "setupWelcomeCardListeners: Настройка рабочих обработчиков")

            // Убедимся, что элементы инициализированы
            if (!::btnStartChat.isInitialized || !::btnMaybeLater.isInitialized || !::btnCloseWelcome.isInitialized) {
                Log.e(TAG, "setupWelcomeCardListeners: Кнопки не инициализированы, откладываем настройку")
                // Попробуем позже
                handler.postDelayed({
                    setupWelcomeCardListeners(onStartChatClicked, onMaybeLaterClicked, onCloseWelcomeClicked)
                }, 500)
                return
            }

            // ВАЖНО: Очищаем ВСЕ предыдущие слушатели
            handler.post {
                try {
                    // Очищаем все обработчики
                    btnStartChat.setOnClickListener(null)
                    btnMaybeLater.setOnClickListener(null)
                    btnCloseWelcome.setOnClickListener(null)

                    // Устанавливаем новые РАБОЧИЕ слушатели
                    btnStartChat.setOnClickListener {
                        Log.d(TAG, "РАБОЧИЙ: Start Chat button clicked - opening chat")
                        // Анимация нажатия
                        animateButtonClick(it as Button)

                        // Проверяем, что обработчик работает
                        Toast.makeText(activity, "Переход в чат...", Toast.LENGTH_SHORT).show()

                        onStartChatClicked()
                    }

                    btnMaybeLater.setOnClickListener {
                        Log.d(TAG, "РАБОЧИЙ: Maybe Later button clicked - hiding welcome")
                        // Анимация нажатия
                        animateButtonClick(it as Button)

                        // Проверяем, что обработчик работает
                        Toast.makeText(activity, "Скрываем приветствие...", Toast.LENGTH_SHORT).show()

                        onMaybeLaterClicked()
                    }

                    btnCloseWelcome.setOnClickListener {
                        Log.d(TAG, "РАБОЧИЙ: Close Welcome button clicked - hiding welcome")

                        // Проверяем, что обработчик работает
                        Toast.makeText(activity, "Закрываем приветствие...", Toast.LENGTH_SHORT).show()

                        onCloseWelcomeClicked()
                    }

                    areListenersSetup = true

                    // Проверяем, что слушатели установлены
                    Log.d(TAG, "РАБОЧИЕ Listeners setup complete:")
                    Log.d(TAG, "btnStartChat has listeners: ${btnStartChat.hasOnClickListeners()}")
                    Log.d(TAG, "btnMaybeLater has listeners: ${btnMaybeLater.hasOnClickListeners()}")
                    Log.d(TAG, "btnCloseWelcome has listeners: ${btnCloseWelcome.hasOnClickListeners()}")

                    // Проверяем через 1 секунду, что обработчики все еще есть
                    handler.postDelayed({
                        checkListenersStillActive(onStartChatClicked, onMaybeLaterClicked, onCloseWelcomeClicked)
                    }, 1000)

                    // Если через 5 секунд обработчики не установились, ставим тестовые
                    handler.postDelayed({
                        if (!btnStartChat.hasOnClickListeners() || !btnMaybeLater.hasOnClickListeners()) {
                            Log.w(TAG, "Обработчики не установились через 5 секунд, ставим тестовые")
                            setupTestClickListenersAsFallback()
                        }
                    }, 5000)

                } catch (e: Exception) {
                    Log.e(TAG, "Error in handler post", e)
                }
            }

            Log.d(TAG, "Welcome card listeners setup completed")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up welcome card listeners", e)
            // Если не удалось установить рабочие обработчики, ставим тестовые
            setupTestClickListenersAsFallback()
        }
    }

    /**
     * Проверяет, что обработчики все еще активны
     */
    private fun checkListenersStillActive(
        onStartChatClicked: () -> Unit,
        onMaybeLaterClicked: () -> Unit,
        onCloseWelcomeClicked: () -> Unit
    ) {
        Log.d(TAG, "Проверка активности обработчиков:")
        Log.d(TAG, "btnStartChat has listeners: ${btnStartChat.hasOnClickListeners()}")
        Log.d(TAG, "btnMaybeLater has listeners: ${btnMaybeLater.hasOnClickListeners()}")
        Log.d(TAG, "btnCloseWelcome has listeners: ${btnCloseWelcome.hasOnClickListeners()}")

        // Если нет обработчиков, переустанавливаем
        if (!btnStartChat.hasOnClickListeners() || !btnMaybeLater.hasOnClickListeners()) {
            Log.w(TAG, "Обработчики пропали! Переустанавливаем...")
            setupWelcomeCardListeners(onStartChatClicked, onMaybeLaterClicked, onCloseWelcomeClicked)
        }
    }

    /**
     * Анимация нажатия кнопки
     */
    private fun animateButtonClick(button: Button) {
        try {
            val originalScaleX = button.scaleX
            val originalScaleY = button.scaleY

            button.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(100)
                .withEndAction {
                    button.animate()
                        .scaleX(originalScaleX)
                        .scaleY(originalScaleY)
                        .setDuration(100)
                        .start()
                }
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "Error animating button click", e)
        }
    }

    /**
     * Установка TTS Manager
     */
    fun setTTSManager(ttsManager: TTSManager) {
        this.ttsManager = ttsManager
        this.isTTSInitialized = ttsManager.isInitialized
        Log.d(TAG, "TTS Manager set, initialized: $isTTSInitialized")
    }

    /**
     * Установка профиля пользователя
     */
    fun setUserProfile(userProfile: UserProfile?) {
        this.userProfile = userProfile
        this.contextAnalyzer = SmartContextAnalyzer(activity.applicationContext)
        this.greetingGenerator = SmartQuestionGenerator(activity.applicationContext, userProfile)
        Log.d(TAG, "User profile set: ${userProfile != null}")
    }

    /**
     * Обновление профиля пользователя
     */
    fun updateUserProfile(newProfile: UserProfile) {
        userProfile = newProfile
        greetingGenerator = SmartQuestionGenerator(activity.applicationContext, userProfile)

        // Обновляем приветствие если оно видимо
        if (isWelcomeCardVisible()) {
            updateWelcomeMessageWithProfile()
        }

        Log.d(TAG, "User profile updated in WelcomeManager")
    }

    /**
     * Показ прогресса загрузки
     */
    fun showLoadingProgress() {
        try {
            handler.post {
                progressWelcome.visibility = View.VISIBLE
                progressWelcome.alpha = 1f
                welcomeContent.visibility = View.VISIBLE
                welcomeContent.alpha = 0.3f
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing loading progress", e)
        }
    }

    /**
     * Скрытие прогресса загрузки
     */
    fun hideLoadingProgress() {
        try {
            handler.post {
                progressWelcome.visibility = View.GONE
                welcomeContent.alpha = 1f
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding loading progress", e)
        }
    }

    /**
     * Показывает мгновенное базовое приветствие
     */
    fun showInstantBasicGreeting() {
        try {
            Log.d(TAG, "showInstantBasicGreeting: Запуск мгновенного приветствия")

            // Ждем загрузки имени (но с таймаутом)
            if (!activity.isUserNameLoaded) {
                Log.d(TAG, "User name not loaded yet, showing generic greeting")

                // Показываем общее приветствие без имени
                handler.post {
                    tvWelcomeTitle.text = "Добро пожаловать!"
                    tvWelcomeQuestion.text = "Рад видеть вас!"
                    tvWelcomeContext.text = "Давайте начнем наше общение"

                    // Сразу показываем карточку
                    showWelcomeCardImmediately()
                }

                // Запускаем отложенную загрузку приветствия с именем
                handler.postDelayed({
                    if (activity.isUserNameLoaded) {
                        showGreetingWithName()
                    } else {
                        // Все равно показываем с fallback именем
                        showGreetingWithName()
                    }
                }, 500)

                return
            }

            // Если имя уже загружено, показываем сразу
            showGreetingWithName()

        } catch (e: Exception) {
            Log.e(TAG, "Error showing instant greeting", e)
            showGreetingWithName()
        }
    }

    /**
     * Мгновенный показ карточки приветствия
     */
    private fun showWelcomeCardImmediately() {
        try {
            handler.post {
                // Устанавливаем видимость
                welcomeCard.visibility = View.VISIBLE
                welcomeContent.visibility = View.VISIBLE
                progressWelcome.visibility = View.GONE

                // Устанавливаем прозрачность
                welcomeCard.alpha = 1f
                welcomeContent.alpha = 1f

                // Включаем интерактивность
                welcomeCard.isEnabled = true
                welcomeCard.isClickable = true
                btnStartChat.isEnabled = true
                btnMaybeLater.isEnabled = true
                btnCloseWelcome.isEnabled = true

                // Принудительная перерисовка
                welcomeCard.requestLayout()
                welcomeCard.invalidate()

                Log.d(TAG, "Welcome card shown immediately with buttons enabled")

                // Отладочная информация
                debugWelcomeCardState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing welcome card immediately", e)
        }
    }

    /**
     * Показывает приветствие с именем
     */
    private fun showGreetingWithName() {
        try {
            val userName = activity.getUserNameForGreeting()
            val greeting = getTimeBasedGreeting()
            val greetingText = "$greeting, $userName!"

            // Сразу устанавливаем текст приветствия
            handler.post {
                tvWelcomeTitle.text = greetingText
                tvWelcomeQuestion.text = "Как ваши дела сегодня?"
                tvWelcomeContext.text = "Я готов помочь вам с любыми вопросами!"

                // Показываем карточку
                showWelcomeCardImmediately()
            }

            Log.d(TAG, "Greeting shown with name: $userName")

            // ОЗВУЧИВАЕМ базовое приветствие
            handler.postDelayed({
                if (isTTSInitialized && isActivityResumed) {
                    speakGreeting(greetingText)
                }
            }, TTS_GREETING_DELAY)

        } catch (e: Exception) {
            Log.e(TAG, "Error showing greeting with name", e)
            // Fallback на приветствие без имени
            handler.post {
                tvWelcomeTitle.text = "Добро пожаловать!"
                showWelcomeCardImmediately()
            }
        }
    }

    /**
     * Обновляем базовое приветственное сообщение
     */
    fun showBasicWelcomeMessage() {
        try {
            Log.d(TAG, "showBasicWelcomeMessage: Показ базового приветствия")

            val userName = activity.getUserNameForGreeting()
            val greeting = getTimeBasedGreeting()
            val greetingText = "$greeting, $userName!"

            // Обновляем UI
            handler.post {
                tvWelcomeTitle.text = greetingText
                tvWelcomeQuestion.text = "Чем увлекаетесь в последнее время?"

                // Генерируем осмысленную фразу
                val meaningfulContinuation = generateNaturalContinuationPhrase()
                tvWelcomeContext.text = meaningfulContinuation

                // Показываем карточку
                showWelcomeCardImmediately()
            }

            // 1. Сначала озвучиваем основное приветствие
            speakGreeting(greetingText)

            // 2. Через паузу озвучиваем продолжение
            handler.postDelayed({
                speakAIContinuation(generateNaturalContinuationPhrase())
            }, TTS_CONTINUATION_DELAY)

            // Сохраняем эту фразу для чата
            saveCompleteWelcomePhraseForChatAsync(generateNaturalContinuationPhrase())

            Log.d(TAG, "Basic welcome message shown for: $userName")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing basic welcome message", e)
            // Fallback
            handler.post {
                tvWelcomeTitle.text = "Добро пожаловать!"
                showWelcomeCardImmediately()
            }
        }
    }

    /**
     * Сброс состояния карточки
     */
    private fun resetWelcomeCardState() {
        try {
            handler.post {
                welcomeCard.visibility = View.VISIBLE
                welcomeCard.alpha = 0f
                welcomeCard.scaleX = 0.9f
                welcomeCard.scaleY = 0.9f
                welcomeCard.translationY = -50f

                progressWelcome.visibility = View.VISIBLE
                progressWelcome.progress = 0
                progressWelcome.alpha = 1f

                welcomeContent.visibility = View.VISIBLE
                welcomeContent.alpha = 0f

                // Включаем кнопки
                btnStartChat.isEnabled = true
                btnMaybeLater.isEnabled = true
                btnCloseWelcome.isEnabled = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting welcome card state", e)
        }
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
                    // Включаем кнопки после анимации
                    btnStartChat.isEnabled = true
                    btnMaybeLater.isEnabled = true
                    btnCloseWelcome.isEnabled = true
                }
            })
        }

        contentAnimator.start()
    }

    /**
     * Запускает AI-улучшенное поэтапное отображение приветствия
     */
    fun startStagedWelcomeSequence() {
        try {
            if (isWelcomeSequenceRunning) {
                Log.d(TAG, "Welcome sequence already running, skipping")
                return
            }

            isWelcomeSequenceRunning = true
            welcomeRetryCount = 0

            Log.d(TAG, "Starting IMPROVED AI-enhanced staged welcome sequence with TTS")

            welcomeSequenceJob?.cancel()
            welcomeSequenceJob = launch(Dispatchers.Main) {
                try {
                    // 1. Мгновенно - базовое приветствие (уже показано, озвучка идет)
                    Log.d(TAG, "Stage 1: Basic greeting already shown and being spoken")

                    // Даем время на озвучку приветствия
                    delay(WELCOME_STAGE_2_DELAY - TTS_GREETING_DELAY)

                    // 2. Показываем контекстный вопрос (ТОЛЬКО ТЕКСТ, БЕЗ ОЗВУЧКИ)
                    val contextQuestion = withContext(Dispatchers.Default) {
                        try {
                            withTimeoutOrNull(1500L) {
                                generateContextualQuestionFromProfile()
                            } ?: "Как ваши дела?"
                        } catch (e: Exception) {
                            "Как ваши дела?"
                        }
                    }

                    withContext(Dispatchers.Main) {
                        animateTextChange(tvWelcomeQuestion, contextQuestion)
                        Log.d(TAG, "Stage 2: Context question shown (no TTS): $contextQuestion")
                    }

                    // Даем время пользователю увидеть вопрос
                    delay(1000L)

                    // 3. Параллельно запускаем AI анализ для продолжения
                    val aiAnalysisDeferred = if (activity.isNetworkAvailable()) {
                        async(Dispatchers.Default) {
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

                    val dialogAnalysisDeferred = async(Dispatchers.Default) {
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

                    withContext(Dispatchers.Main) {
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
                        withContext(Dispatchers.Main) {
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
     * Показывает естественное fallback-приветствие
     */
    private fun showNaturalFallbackGreeting() {
        try {
            val naturalGreeting = generateNaturalContinuationPhrase()

            handler.post {
                tvWelcomeContext.text = naturalGreeting
            }

            // 1. Сначала говорим основное приветствие (если еще не сказали)
            if (!hasGreetingBeenSpoken && isTTSInitialized) {
                val userName = activity.getUserNameForGreeting()
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
     * Обновляет приветственное сообщение с учетом профиля пользователя
     */
    fun updateWelcomeMessageWithProfile() {
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
     * Анимация изменения текста
     */
    private fun animateTextChange(textView: TextView, newText: String) {
        val fadeOut = android.animation.ObjectAnimator.ofFloat(textView, "alpha", 1f, 0f).apply {
            duration = 150
        }

        val fadeIn = android.animation.ObjectAnimator.ofFloat(textView, "alpha", 0f, 1f).apply {
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
     * Озвучивает базовое приветствие
     */
    private fun speakGreeting(text: String) {
        if (!isTTSInitialized || !isActivityResumed || ttsManager == null) {
            Log.w(TAG, "TTS not initialized or activity not resumed, skipping speech: $text")
            return
        }

        try {
            // Используем TYPE_GREETING для специальной обработки приветствий
            ttsManager?.speak(text, TTSManager.TYPE_GREETING, true)
            hasGreetingBeenSpoken = true

            Log.d(TAG, "Greeting spoken: ${text.take(50)}...")

        } catch (e: Exception) {
            Log.e(TAG, "Error speaking greeting", e)
        }
    }

    /**
     * Озвучивает начальное приветствие
     */
    fun speakInitialGreeting() {
        if (!isTTSInitialized || hasGreetingBeenSpoken || !isActivityResumed || ttsManager == null) {
            Log.w(TAG, "Skipping initial greeting - TTS: $isTTSInitialized, Spoken: $hasGreetingBeenSpoken, Resumed: $isActivityResumed")
            return
        }

        try {
            val userName = activity.getUserNameForGreeting()
            val greeting = getTimeBasedGreeting()
            val greetingText = "$greeting, $userName!"

            Log.d(TAG, "Speaking initial greeting: $greetingText")

            // Сразу помечаем как сказанное, чтобы не повторять
            hasGreetingBeenSpoken = true

            // Используем TTS Manager для озвучки
            ttsManager?.speak(greetingText, TTSManager.TYPE_GREETING, true)

        } catch (e: Exception) {
            Log.e(TAG, "Error speaking initial greeting", e)
            // Сбрасываем флаг при ошибке, чтобы можно было повторить
            hasGreetingBeenSpoken = false
        }
    }

    /**
     * Озвучивает AI-продолжение диалога
     */
    private fun speakAIContinuation(continuation: String) {
        if (!isTTSInitialized || hasAIContinuationBeenSpoken || !isActivityResumed || ttsManager == null) {
            Log.w(TAG, "Skipping continuation - TTS: $isTTSInitialized, Spoken: $hasAIContinuationBeenSpoken, Resumed: $isActivityResumed")
            return
        }

        try {
            // Используем TYPE_CHAT_BOT для естественного звучания
            ttsManager?.speak(continuation, TTSManager.TYPE_CHAT_BOT, true)
            hasAIContinuationBeenSpoken = true

            Log.d(TAG, "Continuation spoken: ${continuation.take(50)}...")

        } catch (e: Exception) {
            Log.e(TAG, "Error speaking continuation", e)
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
     * Генерирует контекстный вопрос на основе анкеты пользователя
     */
    private suspend fun generateContextualQuestionFromProfile(): String = withContext(Dispatchers.Default) {
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
     * AI анализ истории чата для генерации контекстного продолжения
     */
    private suspend fun analyzeChatHistoryWithAI(): String? = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        return@withContext try {
            Log.d(TAG, "Starting AI analysis of chat history...")

            // ПРОВЕРКА 1: есть ли сеть?
            if (!activity.isNetworkAvailable()) {
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
     * Анализирует предыдущие диалоги для продолжения
     */
    private suspend fun analyzePreviousDialogsForContinuation(): String? = withContext(Dispatchers.Default) {
        return@withContext try {
            // ПЕРВЫЙ ПРИОРИТЕТ: AI анализ истории чата (только если есть сеть)
            if (activity.isNetworkAvailable()) {
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
     * Загружает историю чата для AI анализа (последние 6 сообщений)
     */
    private suspend fun loadRecentChatHistoryForAI(): List<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val sharedPref = activity.getSharedPreferences("chat_history", Context.MODE_PRIVATE)
            val historyJson = sharedPref.getString("recent_messages", "[]")
            val messages = com.google.gson.Gson().fromJson(historyJson, Array<String>::class.java).toList()

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
     * Загружает историю чата из SharedPreferences
     */
    private suspend fun loadRecentChatHistory(): List<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val sharedPref = activity.getSharedPreferences("chat_history", Context.MODE_PRIVATE)
            val historyJson = sharedPref.getString("recent_messages", "[]")
            val messages = com.google.gson.Gson().fromJson(historyJson, Array<String>::class.java).toList()
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

            // Проверяем, достаточно ли контекста для персонализированного продолжения
            cleanMessage.length > 25 && hasSubstantialContent(cleanMessage) ->
                generateEngagingContinuation(lastMessage)

            else -> null
        }
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
     * Быстрый метод получения AI продолжения (из кэша или синхронно)
     */
    private suspend fun getQuickAIContinuation(): String? = withContext(Dispatchers.Default) {
        // Пробуем взять из кэша
        cachedAIContinuation?.let {
            Log.d(TAG, "Using cached AI continuation")
            return@withContext it
        }

        // Если кэша нет, проверяем сеть
        if (!activity.isNetworkAvailable()) {
            Log.d(TAG, "Network unavailable for quick AI continuation")
            return@withContext null
        }

        // Если есть сообщения - быстрый синхронный запрос с таймаутом
        val hasMessages = withContext(Dispatchers.IO) {
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
     * Получает токен для анализа
     */
    private suspend fun getAuthTokenForAnalysis(): String = withContext(Dispatchers.IO) {
        return@withContext try {
            suspendCoroutine { continuation ->
                val rqUid = UUID.randomUUID().toString()
                val authHeader = "Basic M2JhZGQ0NzktNGVjNy00ZmYyLWE4ZGQtNTMyOTViZDgzYzlkOjU4OGRkZDg1LTMzZmMtNDNkYi04MmJmLWFmZDM5Nzk5NmM2MQ=="

                val call = com.example.chatapp.api.AuthRetrofitInstance.authApi.getAuthToken(
                    rqUid = rqUid,
                    authHeader = authHeader,
                    scope = "GIGACHAT_API_PERS"
                )

                call.enqueue(object : retrofit2.Callback<com.example.chatapp.api.AuthResponse> {
                    override fun onResponse(
                        call: retrofit2.Call<com.example.chatapp.api.AuthResponse>,
                        response: retrofit2.Response<com.example.chatapp.api.AuthResponse>
                    ) {
                        if (response.isSuccessful) {
                            continuation.resume(response.body()?.access_token ?: "")
                        } else {
                            Log.e(TAG, "Auth failed for analysis: ${response.code()}")
                            continuation.resume("")
                        }
                    }

                    override fun onFailure(
                        call: retrofit2.Call<com.example.chatapp.api.AuthResponse>,
                        t: Throwable
                    ) {
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
     * Форматирует история диалога для лучшей читаемости
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

    /**
     * Отправка запроса анализа
     */
    private suspend fun sendAnalysisRequest(token: String, prompt: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            // Создаем запрос БЕЗ temperature, так как его нет в модели
            val request = com.example.chatapp.api.GigaChatRequest(
                model = "GigaChat",
                messages = listOf(com.example.chatapp.api.Message(role = "user", content = prompt)),
                max_tokens = 100
            )

            val call = com.example.chatapp.api.RetrofitInstance.api.sendMessage("Bearer $token", request)
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
     * Асинхронное сохранение сгенерированной фразы для чата
     */
    private fun saveCompleteWelcomePhraseForChatAsync(continuationPhrase: String? = null) {
        launch(Dispatchers.Default) {
            try {
                // Берем либо переданную фразу, либо текущую из TextView
                val phraseToSave = continuationPhrase ?: tvWelcomeContext.text.toString()

                // Проверяем, что это не начальная заглушка
                if (phraseToSave != "Анализирую наши предыдущие обсуждения..." &&
                    phraseToSave != "Формирую вопрос на основе ваших интересов...") {

                    // Очищаем предыдущие сохраненные фразы
                    val sharedPref = activity.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
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
     * Получение фразы продолжения для чата
     */
    fun getContinuationPhraseForChat(): String {
        return try {
            val sharedPref = activity.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
            val phrase = sharedPref.getString("continuation_phrase", null)
            sharedPref.edit().remove("continuation_phrase").apply()
            phrase ?: generateTimeBasedGreetingFallback()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading continuation phrase", e)
            generateTimeBasedGreetingFallback()
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
     * Показать fallback приветствие
     */
    fun showFallbackGreeting() {
        try {
            handler.post {
                tvWelcomeTitle.text = "Добро пожаловать!"
                tvWelcomeQuestion.text = "Чем увлекаетесь в последнее время?"
                tvWelcomeContext.text = "Давайте продолжим наш разговор!"
                showWelcomeCardImmediately()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing fallback greeting", e)
        }
    }

    /**
     * Проверяет видима ли карточка приветствия
     */
    fun isWelcomeCardVisible(): Boolean {
        return try {
            ::welcomeCard.isInitialized && welcomeCard.visibility == View.VISIBLE && welcomeCard.alpha > 0.1f
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Скрытие карточки приветствия (только видимость)
     */
    fun hideWelcomeCard() {
        try {
            if (::welcomeCard.isInitialized && welcomeCard.visibility == View.VISIBLE) {
                handler.post {
                    welcomeCard.visibility = View.GONE
                    welcomeCard.alpha = 0f
                }
                Log.d(TAG, "Welcome card hidden (visibility only)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding welcome card", e)
        }
    }

    /**
     * Скрытие приветственного сообщения с анимацией и остановкой TTS
     */
    fun hideWelcomeMessage() {
        try {
            // Отменяем последовательность приветствия
            welcomeSequenceJob?.cancel()
            isWelcomeSequenceRunning = false

            // Останавливаем ВСЕ TTS
            ttsManager?.stop()
            ttsManager?.clearQueue()  // Очищаем очередь

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
            handler.post {
                welcomeCard.visibility = View.GONE
            }
            ttsManager?.stop()
        }
    }

    /**
     * Вызывается при возобновлении активности
     */
    fun onActivityResumed() {
        isActivityResumed = true

        // Сбрасываем флаги TTS для повторного озвучивания
        hasGreetingBeenSpoken = false
        hasAIContinuationBeenSpoken = false

        Log.d(TAG, "WelcomeManager activity resumed")
    }

    /**
     * Вызывается при паузе активности
     */
    fun onActivityPaused() {
        isActivityResumed = false
        Log.d(TAG, "WelcomeManager activity paused")
    }

    /**
     * Очистка ресурсов при уничтожении
     */
    fun onDestroy() {
        welcomeSequenceJob?.cancel()
        aiAnalysisJob?.cancel()
        handler.removeCallbacksAndMessages(null)

        // Останавливаем TTS
        ttsManager?.stop()

        Log.d(TAG, "WelcomeManager destroyed")
    }

    /**
     * Отладочная информация о состоянии карточки
     */
    fun debugWelcomeCardState() {
        Log.d(TAG, "=== WELCOME CARD DEBUG INFO ===")
        Log.d(TAG, "welcomeCard.isInitialized: ${::welcomeCard.isInitialized}")
        if (::welcomeCard.isInitialized) {
            Log.d(TAG, "welcomeCard.visibility: ${welcomeCard.visibility}")
            Log.d(TAG, "welcomeCard.alpha: ${welcomeCard.alpha}")
            Log.d(TAG, "welcomeCard.isEnabled: ${welcomeCard.isEnabled}")
            Log.d(TAG, "welcomeCard.isClickable: ${welcomeCard.isClickable}")
        }

        Log.d(TAG, "btnStartChat.isInitialized: ${::btnStartChat.isInitialized}")
        if (::btnStartChat.isInitialized) {
            Log.d(TAG, "btnStartChat.isEnabled: ${btnStartChat.isEnabled}")
            Log.d(TAG, "btnStartChat.visibility: ${btnStartChat.visibility}")
            Log.d(TAG, "btnStartChat.alpha: ${btnStartChat.alpha}")
            Log.d(TAG, "btnStartChat.text: ${btnStartChat.text}")
            Log.d(TAG, "btnStartChat.hasOnClickListeners: ${btnStartChat.hasOnClickListeners()}")
        }

        Log.d(TAG, "btnMaybeLater.isInitialized: ${::btnMaybeLater.isInitialized}")
        if (::btnMaybeLater.isInitialized) {
            Log.d(TAG, "btnMaybeLater.isEnabled: ${btnMaybeLater.isEnabled}")
            Log.d(TAG, "btnMaybeLater.visibility: ${btnMaybeLater.visibility}")
            Log.d(TAG, "btnMaybeLater.hasOnClickListeners: ${btnMaybeLater.hasOnClickListeners()}")
        }

        Log.d(TAG, "btnCloseWelcome.isInitialized: ${::btnCloseWelcome.isInitialized}")
        if (::btnCloseWelcome.isInitialized) {
            Log.d(TAG, "btnCloseWelcome.hasOnClickListeners: ${btnCloseWelcome.hasOnClickListeners()}")
        }

        Log.d(TAG, "welcomeContent.isInitialized: ${::welcomeContent.isInitialized}")
        if (::welcomeContent.isInitialized) {
            Log.d(TAG, "welcomeContent.visibility: ${welcomeContent.visibility}")
            Log.d(TAG, "welcomeContent.alpha: ${welcomeContent.alpha}")
        }

        Log.d(TAG, "areListenersSetup: $areListenersSetup")
        Log.d(TAG, "=== END DEBUG INFO ===")
    }

    /**
     * Принудительная проверка и установка обработчиков
     */
    fun forceSetupListeners(
        onStartChatClicked: () -> Unit,
        onMaybeLaterClicked: () -> Unit,
        onCloseWelcomeClicked: () -> Unit
    ) {
        Log.d(TAG, "forceSetupListeners: Принудительная установка обработчиков")

        handler.post {
            try {
                // Очищаем ВСЕ обработчики
                btnStartChat.setOnClickListener(null)
                btnMaybeLater.setOnClickListener(null)
                btnCloseWelcome.setOnClickListener(null)

                // Устанавливаем обработчики напрямую
                btnStartChat.setOnClickListener {
                    Log.d(TAG, "FORCE: Start Chat clicked")
                    Toast.makeText(activity, "FORCE: Переход в чат", Toast.LENGTH_SHORT).show()
                    onStartChatClicked()
                }

                btnMaybeLater.setOnClickListener {
                    Log.d(TAG, "FORCE: Maybe Later clicked")
                    Toast.makeText(activity, "FORCE: Скрытие приветствия", Toast.LENGTH_SHORT).show()
                    onMaybeLaterClicked()
                }

                btnCloseWelcome.setOnClickListener {
                    Log.d(TAG, "FORCE: Close Welcome clicked")
                    Toast.makeText(activity, "FORCE: Закрытие приветствия", Toast.LENGTH_SHORT).show()
                    onCloseWelcomeClicked()
                }

                areListenersSetup = true
                Log.d(TAG, "FORCE: Обработчики установлены принудительно")

            } catch (e: Exception) {
                Log.e(TAG, "Error in forceSetupListeners", e)
            }
        }
    }
}