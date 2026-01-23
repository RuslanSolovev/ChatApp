package com.example.chatapp.step

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock

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
        context.getSharedPreferences("step_data_v3", Context.MODE_PRIVATE)

    private val lock = ReentrantReadWriteLock()

    // Ключи для хранения
    private fun getStorageKey(date: String): String {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: "anonymous"
        return "${userId}_$date"
    }

    // Для совместимости со старыми данными
    private fun getLegacyKey(date: String): String = date

    fun saveSteps(date: String, steps: Int): Boolean {
        return try {
            lock.writeLock().lock()
            val editor = sharedPreferences.edit()

            // Получаем текущее значение для проверки
            val currentSteps = getStepsInternal(date)

            // Сохраняем только если новое значение больше
            if (steps > currentSteps) {
                // Сохраняем по новому ключу (с userId)
                editor.putInt(getStorageKey(date), steps)

                // Также сохраняем по старому ключу для Service (временно)
                editor.putInt(date, steps)

                // Сохраняем максимальное значение за день
                val maxKey = "max_${getStorageKey(date)}"
                val currentMax = sharedPreferences.getInt(maxKey, 0)
                if (steps > currentMax) {
                    editor.putInt(maxKey, steps)
                }

                // Сохраняем дату последнего обновления
                editor.putLong("last_update_${getStorageKey(date)}", System.currentTimeMillis())

                // Атомарное сохранение
                val success = editor.commit() // Используем commit вместо apply для немедленного сохранения

                if (success) {
                    Log.d("StepDataManager", "Шаги сохранены: $date - $steps (было: $currentSteps)")
                } else {
                    Log.e("StepDataManager", "Ошибка сохранения шагов: $date - $steps")
                }
                success
            } else {
                Log.d("StepDataManager", "Шаги не сохранены (новое значение не больше): $date - $steps <= $currentSteps")
                true // Уже сохранено большее значение
            }
        } catch (e: Exception) {
            Log.e("StepDataManager", "Ошибка сохранения шагов: ${e.message}")
            false
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun saveStepsWithRetry(date: String, steps: Int, maxRetries: Int = 3): Boolean {
        var retryCount = 0
        while (retryCount < maxRetries) {
            if (saveSteps(date, steps)) {
                return true
            }
            retryCount++
            Thread.sleep(100) // Небольшая пауза между попытками
        }
        return false
    }

    fun getSteps(date: String): Int {
        return try {
            lock.readLock().lock()
            getStepsInternal(date)
        } finally {
            lock.readLock().unlock()
        }
    }

    private fun getStepsInternal(date: String): Int {
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

    fun getMaxSteps(date: String): Int {
        return try {
            lock.readLock().lock()
            val maxKey = "max_${getStorageKey(date)}"
            sharedPreferences.getInt(maxKey, getStepsInternal(date))
        } finally {
            lock.readLock().unlock()
        }
    }

    fun migrateOldData() {
        lock.writeLock().lock()
        try {
            val allEntries = sharedPreferences.all
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return

            allEntries.forEach { (key, value) ->
                // Если ключ в формате даты (yyyy-MM-dd) и это число
                if (key.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && value is Int) {
                    // Создаем новый ключ
                    val newKey = "${userId}_$key"
                    // Копируем данные только если их еще нет
                    if (!sharedPreferences.contains(newKey)) {
                        sharedPreferences.edit().putInt(newKey, value).apply()
                        // Также сохраняем как максимальное значение
                        val maxKey = "max_$newKey"
                        if (!sharedPreferences.contains(maxKey)) {
                            sharedPreferences.edit().putInt(maxKey, value).apply()
                        }
                    }
                }
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    // Метод для принудительного сохранения, даже если значение меньше
    fun forceSaveSteps(date: String, steps: Int): Boolean {
        lock.writeLock().lock()
        return try {
            val editor = sharedPreferences.edit()
            editor.putInt(getStorageKey(date), steps)
            editor.putInt(date, steps) // Для совместимости
            editor.commit()
        } finally {
            lock.writeLock().unlock()
        }
    }

    // Получение всех дат с данными
    fun getAllDatesWithData(): List<String> {
        lock.readLock().lock()
        return try {
            val allEntries = sharedPreferences.all
            val dates = mutableListOf<String>()

            allEntries.forEach { (key, value) ->
                if (key.matches(Regex(".*_\\d{4}-\\d{2}-\\d{2}")) && value is Int) {
                    // Извлекаем дату из ключа
                    val date = key.substringAfterLast("_")
                    if (date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                        dates.add(date)
                    }
                }
            }

            dates.distinct().sorted()
        } finally {
            lock.readLock().unlock()
        }
    }

    // Очистка старых данных (опционально)
    fun cleanupOldData(daysToKeep: Int = 90) {
        lock.writeLock().lock()
        try {
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, -daysToKeep)
            val cutoffDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)

            val allEntries = sharedPreferences.all
            val editor = sharedPreferences.edit()

            allEntries.forEach { (key, value) ->
                if (key.matches(Regex(".*_\\d{4}-\\d{2}-\\d{2}"))) {
                    try {
                        // Извлекаем дату из ключа
                        val dateKey = key.substringAfterLast("_")
                        if (dateKey < cutoffDate) {
                            editor.remove(key)
                            // Удаляем и старый ключ если есть
                            editor.remove(dateKey)
                        }
                    } catch (e: Exception) {
                        // Игнорируем ошибки сравнения
                    }
                }
            }

            editor.apply()
        } finally {
            lock.writeLock().unlock()
        }
    }
}