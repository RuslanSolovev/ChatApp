package com.example.chatapp.step

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.*

class StepDataManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: StepDataManager? = null

        fun getInstance(context: Context): StepDataManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StepDataManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("step_data_v2", Context.MODE_PRIVATE)

    // Ключи для хранения
    private fun getStorageKey(date: String): String {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: "anonymous"
        return "${userId}_$date"
    }

    // Для совместимости со старыми данными
    private fun getLegacyKey(date: String): String = date

    fun saveSteps(date: String, steps: Int) {
        val editor = sharedPreferences.edit()

        // Сохраняем по новому ключу (с userId)
        editor.putInt(getStorageKey(date), steps)

        // Также сохраняем по старому ключу для Service
        editor.putInt(date, steps)

        // Сохраняем дату последнего обновления
        editor.putLong("last_update_${getStorageKey(date)}", System.currentTimeMillis())

        editor.apply()
    }

    fun getSteps(date: String): Int {
        // Сначала пробуем получить по новому ключу
        val steps = sharedPreferences.getInt(getStorageKey(date), -1)

        // Если нет, пробуем по старому ключу
        return if (steps != -1) {
            steps
        } else {
            sharedPreferences.getInt(date, 0)
        }
    }

    fun getTodaySteps(): Int {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return getSteps(today)
    }

    fun migrateOldData() {
        val allEntries = sharedPreferences.all
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        allEntries.forEach { (key, value) ->
            // Если ключ в формате даты (yyyy-MM-dd) и это число
            if (key.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && value is Int) {
                // Создаем новый ключ
                val newKey = "${userId}_$key"
                // Копируем данные
                if (!sharedPreferences.contains(newKey)) {
                    sharedPreferences.edit().putInt(newKey, value).apply()
                }
            }
        }
    }

    // Очистка старых данных (опционально)
    fun cleanupOldData(daysToKeep: Int = 90) {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -daysToKeep)
        val cutoffDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)

        val allEntries = sharedPreferences.all
        val editor = sharedPreferences.edit()

        allEntries.forEach { (key, value) ->
            if (key.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                // Если это старая дата (без userId) и она старше cutoffDate
                try {
                    if (key < cutoffDate && !key.contains("_")) {
                        editor.remove(key)
                    }
                } catch (e: Exception) {
                    // Игнорируем ошибки сравнения
                }
            }
        }

        editor.apply()
    }
}