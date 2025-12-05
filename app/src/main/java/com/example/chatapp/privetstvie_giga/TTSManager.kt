package com.example.chatapp.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

class TTSManager(
    private val context: Context,
    private val onInitComplete: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TTSManager"
        private const val MAX_TTS_INIT_WAIT_TIME = 8000L
        private const val PREFS_NAME = "voice_settings"
        private const val KEY_VOICE_NAME = "voice_name"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_PITCH = "pitch"
        private const val KEY_VOICE_GENDER = "voice_gender"
        private const val KEY_IS_TTS_ENABLED = "tts_enabled"

        // Типы сообщений
        const val TYPE_GREETING = "greeting"
        const val TYPE_CHAT_BOT = "chat_bot"
        const val TYPE_CHAT_USER = "chat_user"
        const val TYPE_SYSTEM = "system"
        const val TYPE_ERROR = "error"

        // Голоса Яндекс SpeechKit
        val YANDEX_VOICES = mapOf(
            "oksana" to "Оксана (женский, нейтральный)",
            "jane" to "Джейн (женский, эмоциональный)",
            "omazh" to "Омаж (женский, деловой)",
            "zahar" to "Захар (мужской, нейтральный)",
            "ermil" to "Ермил (мужской, глубокий)",
            "alena" to "Алёна (женский, добрый)",
            "filipp" to "Филипп (мужской, дружелюбный)"
        )

        // Пол голоса
        const val GENDER_FEMALE = "female"
        const val GENDER_MALE = "male"
        const val GENDER_NEUTRAL = "neutral"
    }

    private lateinit var textToSpeech: TextToSpeech
    private lateinit var voiceSettings: SharedPreferences
    val isInitialized: Boolean get() = _isInitialized
    private var _isInitialized = false
    private var initializationStartTime: Long = 0
    private var initializationAttempts = 0
    private val MAX_INIT_ATTEMPTS = 3

    var isSpeaking = false
    private val speechQueue = mutableListOf<SpeechItem>()
    private var currentUtteranceId: String? = null

    data class SpeechItem(
        val text: String,
        val type: String = TYPE_SYSTEM,
        val priority: Int = 0,
        val onComplete: (() -> Unit)? = null
    )

    init {
        voiceSettings = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        initializeTTS()
    }

    private fun initializeTTS() {
        if (initializationAttempts >= MAX_INIT_ATTEMPTS) {
            Log.w(TAG, "Max TTS initialization attempts reached")
            onInitComplete(false)
            return
        }

        initializationAttempts++
        initializationStartTime = System.currentTimeMillis()

        try {
            textToSpeech = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                TextToSpeech(context, this, "com.google.android.tts")
            } else {
                TextToSpeech(context, this)
            }

            Log.d(TAG, "TTS initialization attempt $initializationAttempts started")

            android.os.Handler(context.mainLooper).postDelayed({
                if (!_isInitialized) {
                    Log.w(TAG, "TTS initialization timeout after ${System.currentTimeMillis() - initializationStartTime}ms")
                    if (initializationAttempts < MAX_INIT_ATTEMPTS) {
                        Log.d(TAG, "Retrying TTS initialization...")
                        initializeTTS()
                    } else {
                        onInitComplete(false)
                    }
                }
            }, MAX_TTS_INIT_WAIT_TIME)

        } catch (e: Exception) {
            Log.e(TAG, "Error creating TextToSpeech instance", e)
            if (initializationAttempts < MAX_INIT_ATTEMPTS) {
                try {
                    textToSpeech = TextToSpeech(context, this)
                } catch (e2: Exception) {
                    Log.e(TAG, "Second TTS creation attempt failed", e2)
                    onInitComplete(false)
                }
            } else {
                onInitComplete(false)
            }
        }
    }

    override fun onInit(status: Int) {
        val initDuration = System.currentTimeMillis() - initializationStartTime
        Log.d(TAG, "TTS onInit called after ${initDuration}ms with status: $status")

        _isInitialized = status == TextToSpeech.SUCCESS

        if (_isInitialized) {
            try {
                // Устанавливаем русский язык
                val locale = Locale("ru", "RU")
                var result = textToSpeech.setLanguage(locale)

                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Russian language not fully supported, trying fallbacks...")
                    result = textToSpeech.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        result = textToSpeech.setLanguage(Locale.getDefault())
                    }
                }

                // ПРИМЕНЯЕМ НАСТРОЙКИ ГОЛОСА
                applyVoiceSettings()

                // Настраиваем слушатель прогресса
                textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        this@TTSManager.isSpeaking = true
                        currentUtteranceId = utteranceId
                        Log.d(TAG, "TTS started: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        this@TTSManager.isSpeaking = false
                        currentUtteranceId = null
                        Log.d(TAG, "TTS finished: $utteranceId")

                        val item = speechQueue.find {
                            utteranceId?.startsWith(it.type) == true
                        }
                        item?.onComplete?.invoke()
                        speechQueue.removeAll {
                            utteranceId?.startsWith(it.type) == true
                        }
                        processQueue()
                    }

                    override fun onError(utteranceId: String?) {
                        this@TTSManager.isSpeaking = false
                        currentUtteranceId = null
                        Log.e(TAG, "TTS error: $utteranceId")
                        speechQueue.removeAll {
                            utteranceId?.startsWith(it.type) == true
                        }
                        processQueue()
                    }
                })

                Log.d(TAG, "TTS initialized successfully with voice settings")
                onInitComplete(true)

            } catch (e: Exception) {
                Log.e(TAG, "Error during TTS initialization", e)
                _isInitialized = false
                onInitComplete(false)
            }
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
            if (initializationAttempts < MAX_INIT_ATTEMPTS) {
                Log.d(TAG, "Retrying TTS initialization...")
                initializeTTS()
            } else {
                onInitComplete(false)
            }
        }
    }

    /**
     * ПРИМЕНЕНИЕ НАСТРОЕК ГОЛОСА
     */
    private fun applyVoiceSettings() {
        if (!_isInitialized) return

        try {
            // Скорость речи (0.5 - 2.0)
            val rate = voiceSettings.getFloat(KEY_SPEECH_RATE, 1.0f).coerceIn(0.5f, 2.0f)
            textToSpeech.setSpeechRate(rate)

            // Тон голоса (0.5 - 2.0)
            val pitch = voiceSettings.getFloat(KEY_PITCH, 1.0f).coerceIn(0.5f, 2.0f)
            textToSpeech.setPitch(pitch)

            // Попытка установить голос по полу (для Android API 21+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val gender = voiceSettings.getString(KEY_VOICE_GENDER, GENDER_FEMALE) ?: GENDER_FEMALE
                setSystemVoiceByGender(gender)
            }

            Log.d(TAG, "Voice settings applied: rate=$rate, pitch=$pitch")

        } catch (e: Exception) {
            Log.e(TAG, "Error applying voice settings", e)
        }
    }

    /**
     * Устанавливает системный голос по полу
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun setSystemVoiceByGender(gender: String) {
        try {
            val voices = textToSpeech.voices
            if (voices == null || voices.isEmpty()) return

            // Ищем русский голос по полу
            val targetVoice = voices.find { voice ->
                voice.locale.language == "ru" &&
                        when (gender) {
                            GENDER_MALE -> voice.name.contains("male", true) ||
                                    voice.name.contains("мужск", true) ||
                                    voice.name.contains("zahar", true) ||
                                    voice.name.contains("ermil", true) ||
                                    voice.name.contains("filipp", true)
                            GENDER_FEMALE -> voice.name.contains("female", true) ||
                                    voice.name.contains("женск", true) ||
                                    voice.name.contains("oksana", true) ||
                                    voice.name.contains("jane", true) ||
                                    voice.name.contains("alena", true) ||
                                    voice.name.contains("omazh", true)
                            else -> true // нейтральный - любой голос
                        }
            }

            targetVoice?.let {
                textToSpeech.voice = it
                Log.d(TAG, "Voice set to: ${it.name}")
            } ?: run {
                // Используем первый доступный русский голос
                val russianVoice = voices.find { it.locale.language == "ru" }
                russianVoice?.let { textToSpeech.voice = it }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting system voice by gender", e)
        }
    }

    fun speak(text: String, type: String = TYPE_SYSTEM, interrupt: Boolean = true, onComplete: (() -> Unit)? = null) {
        // Проверяем включен ли TTS
        if (!isTTSEnabled()) {
            Log.d(TAG, "TTS is disabled, skipping speech")
            onComplete?.invoke()
            return
        }

        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }

        val cleanText = prepareTextForSpeech(text, type)
        if (cleanText.isBlank()) {
            onComplete?.invoke()
            return
        }

        if (!_isInitialized) {
            Log.d(TAG, "TTS not initialized yet, adding to queue: ${cleanText.take(30)}...")
            speechQueue.add(SpeechItem(cleanText, type, getPriorityForType(type), onComplete))
            return
        }

        if (isSpeaking) {
            if (interrupt) {
                Log.d(TAG, "Interrupting current speech for type: $type")
                stop()
                speechQueue.add(0, SpeechItem(cleanText, type, getPriorityForType(type), onComplete))
                processQueue()
            } else {
                Log.d(TAG, "Adding to queue (non-interrupt): ${cleanText.take(30)}...")
                speechQueue.add(SpeechItem(cleanText, type, getPriorityForType(type), onComplete))
            }
        } else {
            speakImmediately(cleanText, type, onComplete)
        }
    }

    private fun prepareTextForSpeech(text: String, type: String): String {
        return try {
            var cleanText = text.trim()
            cleanText = cleanText.replace(Regex("https?://\\S+"), " ссылка ")
            cleanText = cleanText.replace(Regex("[*_~`>|<\\[\\]{}()]"), "")
            cleanText = cleanText.replace(Regex("\\n+"), ". ")
            cleanText = cleanText.replace(Regex("\\s+"), " ")

            when (type) {
                TYPE_GREETING -> {
                    if (cleanText.length > 300) "${cleanText.take(280)}..." else cleanText
                }
                TYPE_CHAT_BOT -> {
                    if (cleanText.length > 400) "${cleanText.take(380)}... дальше" else cleanText
                }
                else -> {
                    if (cleanText.length > 300) "${cleanText.take(280)}..." else cleanText
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing text for speech", e)
            text.take(200)
        }
    }

    private fun getPriorityForType(type: String): Int {
        return when (type) {
            TYPE_GREETING -> 4
            TYPE_ERROR -> 3
            TYPE_CHAT_BOT -> 2
            TYPE_SYSTEM -> 1
            TYPE_CHAT_USER -> 0
            else -> 0
        }
    }

    private fun speakImmediately(text: String, type: String, onComplete: (() -> Unit)? = null) {
        try {
            val utteranceId = "${type}_${System.currentTimeMillis()}"

            // Устанавливаем параметры для разных типов
            when (type) {
                TYPE_GREETING -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val baseRate = voiceSettings.getFloat(KEY_SPEECH_RATE, 1.0f)
                        textToSpeech.setSpeechRate(baseRate * 0.9f) // Медленнее для приветствий
                        val basePitch = voiceSettings.getFloat(KEY_PITCH, 1.0f)
                        textToSpeech.setPitch(basePitch * 1.1f) // Выше для приветствий
                    }
                }
                TYPE_CHAT_BOT -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val baseRate = voiceSettings.getFloat(KEY_SPEECH_RATE, 1.0f)
                        textToSpeech.setSpeechRate(baseRate * 1.05f) // Чуть быстрее для бота
                        val basePitch = voiceSettings.getFloat(KEY_PITCH, 1.0f)
                        textToSpeech.setPitch(basePitch) // Нормальный тон
                    }
                }
                TYPE_CHAT_USER -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val baseRate = voiceSettings.getFloat(KEY_SPEECH_RATE, 1.0f)
                        textToSpeech.setSpeechRate(baseRate * 1.1f) // Быстрее для пользователя
                        val basePitch = voiceSettings.getFloat(KEY_PITCH, 1.0f)
                        textToSpeech.setPitch(basePitch * 0.95f) // Чуть ниже для пользователя
                    }
                }
            }

            val params = HashMap<String, String>()
            params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = utteranceId

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                textToSpeech.speak(
                    text,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    utteranceId
                )
            } else {
                @Suppress("DEPRECATION")
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params)
            }

            Log.d(TAG, "Speaking ($type, ${text.length} chars): ${text.take(30)}...")
            speechQueue.add(SpeechItem(text, type, getPriorityForType(type), onComplete))

        } catch (e: Exception) {
            Log.e(TAG, "Error speaking text", e)
            isSpeaking = false
            onComplete?.invoke()
            processQueue()
        }
    }

    private fun processQueue() {
        if (speechQueue.isNotEmpty() && !isSpeaking) {
            speechQueue.sortByDescending { it.priority }
            val nextItem = speechQueue.removeAt(0)
            speakImmediately(nextItem.text, nextItem.type, nextItem.onComplete)
        }
    }

    fun stop() {
        try {
            if (::textToSpeech.isInitialized && isSpeaking) {
                textToSpeech.stop()
                isSpeaking = false
                currentUtteranceId = null
                Log.d(TAG, "TTS stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
    }

    fun stopType(type: String) {
        speechQueue.removeAll { it.type == type }
        if (isSpeaking && currentUtteranceId?.startsWith(type) == true) {
            stop()
        }
    }

    fun clearQueue() {
        speechQueue.clear()
        stop()
    }

    fun release() {
        try {
            stop()
            if (::textToSpeech.isInitialized) {
                textToSpeech.shutdown()
                _isInitialized = false
                speechQueue.clear()
                Log.d(TAG, "TTS released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing TTS", e)
        }
    }

    /**
     * ОБНОВЛЕНИЕ НАСТРОЕК ГОЛОСА
     */
    fun updateVoiceSettings(
        voiceName: String? = null,
        speechRate: Float? = null,
        pitch: Float? = null,
        gender: String? = null
    ) {
        val editor = voiceSettings.edit()

        voiceName?.let {
            editor.putString(KEY_VOICE_NAME, it)
        }
        speechRate?.let {
            editor.putFloat(KEY_SPEECH_RATE, it.coerceIn(0.5f, 2.0f))
        }
        pitch?.let {
            editor.putFloat(KEY_PITCH, it.coerceIn(0.5f, 2.0f))
        }
        gender?.let {
            editor.putString(KEY_VOICE_GENDER, it)
        }

        editor.apply()
        applyVoiceSettings()
    }


    /**
     * ПОЛУЧЕНИЕ ИМЕНИ ГОЛОСА ДЛЯ ОТОБРАЖЕНИЯ
     */
    fun getVoiceDisplayName(): String {
        val voiceName = voiceSettings.getString(KEY_VOICE_NAME, "oksana") ?: "oksana"
        return YANDEX_VOICES[voiceName] ?: voiceName
    }

    /**
     * СБРОС НАСТРОЕК ГОЛОСА К ЗНАЧЕНИЯМ ПО УМОЛЧАНИЮ
     */
    fun resetVoiceSettings() {
        voiceSettings.edit()
            .putString(KEY_VOICE_NAME, "oksana")
            .putFloat(KEY_SPEECH_RATE, 1.0f)
            .putFloat(KEY_PITCH, 1.0f)
            .putString(KEY_VOICE_GENDER, GENDER_FEMALE)
            .apply()
        applyVoiceSettings()
        Log.d(TAG, "Voice settings reset to defaults")
    }

    /**
     * ТЕСТИРОВАНИЕ ГОЛОСА С ТЕКУЩИМИ НАСТРОЙКАМИ
     */
    fun testVoice(text: String = "Привет! Это тестовая озвучка с текущими настройками голоса.", onComplete: (() -> Unit)? = null) {
        if (!isTTSEnabled()) {
            Log.d(TAG, "TTS is disabled, cannot test voice")
            onComplete?.invoke()
            return
        }

        speak(text, TYPE_SYSTEM, interrupt = true, onComplete)
    }

    /**
     * ВКЛЮЧЕНИЕ/ВЫКЛЮЧЕНИЕ TTS
     */
    fun setTTSEnabled(enabled: Boolean) {
        voiceSettings.edit()
            .putBoolean(KEY_IS_TTS_ENABLED, enabled)
            .apply()

        if (!enabled) {
            stop()
            clearQueue()
        }

        Log.d(TAG, "TTS enabled: $enabled")
    }

    /**
     * ПРОВЕРКА ВКЛЮЧЕН ЛИ TTS
     */
    fun isTTSEnabled(): Boolean {
        return voiceSettings.getBoolean(KEY_IS_TTS_ENABLED, true)
    }

    /**
     * ПОЛУЧЕНИЕ СТАТУСА TTS
     */
    fun getTTSStatus(): String {
        return when {
            !_isInitialized -> "Не инициализирован"
            !isTTSEnabled() -> "Отключен"
            isSpeaking -> "Говорит"
            else -> "Готов"
        }
    }

    /**
     * Проверяет доступность TTS
     */
    fun checkTTSAvailability(): Boolean {
        return try {
            if (!::textToSpeech.isInitialized) return false
            val engineInfo = textToSpeech.engines
            engineInfo?.isNotEmpty() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking TTS availability", e)
            false
        }
    }

    /**
     * ПОЛУЧЕНИЕ СПИСКА ДОСТУПНЫХ ГОЛОСОВ
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun getAvailableVoices(): List<String> {
        return try {
            textToSpeech.voices?.map { voice ->
                "${voice.name} (${voice.locale.displayName})"
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting available voices", e)
            emptyList()
        }
    }

    /**
     * СОХРАНЕНИЕ КАСТОМНЫХ НАСТРОЕК ГОЛОСА
     */
    fun saveCustomVoicePreset(name: String, settings: Map<String, Any>) {
        val editor = voiceSettings.edit()

        settings["voiceName"]?.let { editor.putString("${name}_voiceName", it.toString()) }
        settings["speechRate"]?.let { editor.putFloat("${name}_speechRate", (it as? Number)?.toFloat() ?: 1.0f) }
        settings["pitch"]?.let { editor.putFloat("${name}_pitch", (it as? Number)?.toFloat() ?: 1.0f) }
        settings["voiceGender"]?.let { editor.putString("${name}_voiceGender", it.toString()) }

        editor.apply()
        Log.d(TAG, "Voice preset '$name' saved")
    }

    /**
     * ЗАГРУЗКА КАСТОМНЫХ НАСТРОЕК ГОЛОСА
     */
    fun loadCustomVoicePreset(name: String) {
        val voiceName = voiceSettings.getString("${name}_voiceName", null)
        val speechRate = voiceSettings.getFloat("${name}_speechRate", 1.0f)
        val pitch = voiceSettings.getFloat("${name}_pitch", 1.0f)
        val gender = voiceSettings.getString("${name}_voiceGender", null)

        updateVoiceSettings(voiceName, speechRate, pitch, gender)
        Log.d(TAG, "Voice preset '$name' loaded")
    }
}