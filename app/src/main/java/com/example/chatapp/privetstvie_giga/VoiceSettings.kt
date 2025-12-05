package com.example.chatapp.privetstvie_giga

import android.content.Context
import android.content.SharedPreferences

class VoiceSettings(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "voice_settings"
        private const val KEY_VOICE_NAME = "voice_name"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_PITCH = "pitch"
        private const val KEY_VOICE_GENDER = "voice_gender"

        // Голоса по умолчанию
        const val VOICE_FEMALE = "female"
        const val VOICE_MALE = "male"
        const val VOICE_NEUTRAL = "neutral"

        // Голоса для SpeechKit (Yandex)
        val YANDEX_VOICES = mapOf(
            "oksana" to "Оксана (женский, нейтральный)",
            "jane" to "Джейн (женский, эмоциональный)",
            "omazh" to "Омаж (женский, деловой)",
            "zahar" to "Захар (мужской, нейтральный)",
            "ermil" to "Ермил (мужской, глубокий)",
            "alena" to "Алёна (женский, добрый)",
            "filipp" to "Филипп (мужской, дружелюбный)"
        )
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getVoiceName(): String {
        return prefs.getString(KEY_VOICE_NAME, "oksana") ?: "oksana"
    }

    fun setVoiceName(voiceName: String) {
        prefs.edit().putString(KEY_VOICE_NAME, voiceName).apply()
    }

    fun getSpeechRate(): Float {
        return prefs.getFloat(KEY_SPEECH_RATE, 1.0f)
    }

    fun setSpeechRate(rate: Float) {
        prefs.edit().putFloat(KEY_SPEECH_RATE, rate.coerceIn(0.5f, 2.0f)).apply()
    }

    fun getPitch(): Float {
        return prefs.getFloat(KEY_PITCH, 1.0f)
    }

    fun setPitch(pitch: Float) {
        prefs.edit().putFloat(KEY_PITCH, pitch.coerceIn(0.5f, 2.0f)).apply()
    }

    fun getVoiceGender(): String {
        return prefs.getString(KEY_VOICE_GENDER, VOICE_FEMALE) ?: VOICE_FEMALE
    }

    fun setVoiceGender(gender: String) {
        prefs.edit().putString(KEY_VOICE_GENDER, gender).apply()
    }

    fun resetToDefaults() {
        prefs.edit()
            .putString(KEY_VOICE_NAME, "oksana")
            .putFloat(KEY_SPEECH_RATE, 1.0f)
            .putFloat(KEY_PITCH, 1.0f)
            .putString(KEY_VOICE_GENDER, VOICE_FEMALE)
            .apply()
    }

    fun getAllSettings(): Map<String, Any> {
        return mapOf(
            "voiceName" to getVoiceName(),
            "speechRate" to getSpeechRate(),
            "pitch" to getPitch(),
            "voiceGender" to getVoiceGender()
        )
    }
}