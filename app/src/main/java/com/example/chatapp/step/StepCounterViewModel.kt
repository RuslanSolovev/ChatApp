package com.example.chatapp.step

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.chatapp.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

class StepCounterViewModel(application: Application) : AndroidViewModel(application) {

    // Основные LiveData
    private val _todaySteps = MutableLiveData<Int>()
    val todaySteps: LiveData<Int> = _todaySteps

    private val _todayDistance = MutableLiveData<Float>()
    val todayDistance: LiveData<Float> = _todayDistance

    private val _todayCalories = MutableLiveData<Float>()
    val todayCalories: LiveData<Float> = _todayCalories

    private val _activeTime = MutableLiveData<Int>()
    val activeTime: LiveData<Int> = _activeTime

    private val _goalProgress = MutableLiveData<Pair<Int, Int>>()
    val goalProgress: LiveData<Pair<Int, Int>> = _goalProgress

    private val _progressPercentage = MutableLiveData<Int>()
    val progressPercentage: LiveData<Int> = _progressPercentage

    private val _comparisonText = MutableLiveData<String>()
    val comparisonText: LiveData<String> = _comparisonText

    private val _comparisonColor = MutableLiveData<Int>()
    val comparisonColor: LiveData<Int> = _comparisonColor

    // Быстрые карточки
    private val _quickWeek = MutableLiveData<Int>()
    val quickWeek: LiveData<Int> = _quickWeek

    private val _quickMonth = MutableLiveData<Int>()
    val quickMonth: LiveData<Int> = _quickMonth

    private val _quickRecord = MutableLiveData<Int>()
    val quickRecord: LiveData<Int> = _quickRecord

    // Графики
    private val _weeklyChartData = MutableLiveData<List<Int>>()
    val weeklyChartData: LiveData<List<Int>> = _weeklyChartData

    private val _monthlyChartData = MutableLiveData<List<Int>>()
    val monthlyChartData: LiveData<List<Int>> = _monthlyChartData

    private val _yearlyChartData = MutableLiveData<List<Int>>()
    val yearlyChartData: LiveData<List<Int>> = _yearlyChartData

    // Прогноз
    private val _forecastText = MutableLiveData<String>()
    val forecastText: LiveData<String> = _forecastText

    // Виртуальное путешествие
    private val _journeyProgress = MutableLiveData<Int>()
    val journeyProgress: LiveData<Int> = _journeyProgress

    private val _journeyText = MutableLiveData<String>()
    val journeyText: LiveData<String> = _journeyText

    // Серия дней (streak)
    private val _streakDays = MutableLiveData<Int>()
    val streakDays: LiveData<Int> = _streakDays

    // Индивидуальные цели
    private val _hasCustomGoals = MutableLiveData<Boolean>()
    val hasCustomGoals: LiveData<Boolean> = _hasCustomGoals

    // Настройки
    private val _motivationEnabled = MutableLiveData<Boolean>()
    val motivationEnabled: LiveData<Boolean> = _motivationEnabled

    // Данные
    private val sharedPreferences = application.getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
    private val firebaseDatabase = FirebaseDatabase.getInstance()
    private val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    private var dailyStepGoal = sharedPreferences.getInt("daily_goal", 10000)
    private var hasCustomGoalsFlag = false
    private val dailyGoalsMap = mutableMapOf<Int, Int>() // Calendar.DAY_OF_WEEK -> цель

    // Константы для расчетов
    private val stepLengthMeters = 0.75f // Средняя длина шага в метрах
    private val caloriesPerStep = 0.04f // Средние калории на шаг
    private val stepsPerMinute = 100f // Средняя скорость ходьбы

    // Виртуальные маршруты
    private val journeys = listOf(
        JourneyRoute("Париж-Лондон", "Париж", "Лондон", 450f, "Путешествие через Ла-Манш"),
        JourneyRoute("Москва-СПб", "Москва", "Санкт-Петербург", 710f, "Исторический маршрут"),
        JourneyRoute("Великая стена", "Шанхайгуань", "Цзяюйгуань", 8851f, "Вдоль Великой стены"),
        JourneyRoute("Транссиб", "Москва", "Владивосток", 9288f, "Самая длинная железная дорога"),
        JourneyRoute("Аппалачская тропа", "Спрингер", "Катадин", 3500f, "Легендарная пешая тропа")
    )

    init {
        setupFirebaseListeners()
        loadStatistics()
        calculateStreak()
    }

    private fun setupFirebaseListeners() {
        val userId = currentUserId
        if (userId.isEmpty()) return

        val userRef = firebaseDatabase.reference.child("users").child(userId)

        // Слушатель данных шагов
        userRef.child("stepsData").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                loadStatistics()
                calculateStreak()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("StepCounterViewModel", "Ошибка загрузки шагов: ${error.message}")
            }
        })

        // Слушатель целей
        userRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.hasChild("hasCustomGoals") &&
                    snapshot.child("hasCustomGoals").getValue(Boolean::class.java) == true) {

                    hasCustomGoalsFlag = true
                    _hasCustomGoals.postValue(true)

                    // Загружаем индивидуальные цели
                    val goals = snapshot.child("dailyGoals")
                    if (goals.exists()) {
                        dailyGoalsMap.clear()
                        goals.children.forEach { daySnapshot ->
                            val day = when (daySnapshot.key) {
                                "monday" -> Calendar.MONDAY
                                "tuesday" -> Calendar.TUESDAY
                                "wednesday" -> Calendar.WEDNESDAY
                                "thursday" -> Calendar.THURSDAY
                                "friday" -> Calendar.FRIDAY
                                "saturday" -> Calendar.SATURDAY
                                "sunday" -> Calendar.SUNDAY
                                else -> -1
                            }
                            if (day != -1) {
                                val goal = daySnapshot.getValue(Int::class.java) ?: dailyStepGoal
                                dailyGoalsMap[day] = goal
                            }
                        }
                    }
                } else {
                    hasCustomGoalsFlag = false
                    _hasCustomGoals.postValue(false)
                    dailyStepGoal = snapshot.child("dailyGoal").getValue(Int::class.java) ?: 10000
                    sharedPreferences.edit().putInt("daily_goal", dailyStepGoal).apply()
                }

                // Загружаем настройки мотивации
                _motivationEnabled.postValue(
                    snapshot.child("motivationEnabled").getValue(Boolean::class.java) ?: true
                )

                loadStatistics()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("StepCounterViewModel", "Ошибка загрузки целей: ${error.message}")
            }
        })
    }

    fun loadStatistics() {
        viewModelScope.launch {
            try {
                // Основные расчеты
                val today = withContext(Dispatchers.IO) { calculateTodaySteps() }
                val week = withContext(Dispatchers.IO) { calculateWeeklySteps() }
                val month = withContext(Dispatchers.IO) { calculateMonthlySteps() }
                val record = withContext(Dispatchers.IO) { calculateMaxSteps() }
                val avg = withContext(Dispatchers.IO) { calculateAverageSteps() }

                // Детализированная статистика
                val distance = calculateDistance(today)
                val calories = calculateCalories(today)
                val activeTime = calculateActiveTime(today)

                // Обновление LiveData
                _todaySteps.postValue(today)
                _todayDistance.postValue(distance)
                _todayCalories.postValue(calories)
                _activeTime.postValue(activeTime)
                _quickWeek.postValue(week)
                _quickMonth.postValue(month)
                _quickRecord.postValue(record)

                // Цель на сегодня
                val todayGoal = getTodaysGoal()
                val progressPercent = if (todayGoal > 0) (today * 100 / todayGoal) else 0
                _progressPercentage.postValue(progressPercent.coerceIn(0, 100))
                _goalProgress.postValue(Pair(today, todayGoal))

                // Сравнение с прошлой неделей
                val comparison = withContext(Dispatchers.IO) { calculateWeekComparison() }
                _comparisonText.postValue(comparison.first)
                _comparisonColor.postValue(comparison.second)

                // Прогноз
                val forecast = withContext(Dispatchers.IO) { calculateForecast(avg) }
                _forecastText.postValue(forecast)

                // Виртуальное путешествие
                val journey = withContext(Dispatchers.IO) { calculateJourneyProgress() }
                _journeyProgress.postValue(journey.first)
                _journeyText.postValue(journey.second)

                // Данные для графиков
                val weeklyData = withContext(Dispatchers.IO) { getWeeklyChartData() }
                val monthlyData = withContext(Dispatchers.IO) { getMonthlyChartData() }
                _weeklyChartData.postValue(weeklyData)
                _monthlyChartData.postValue(monthlyData)

            } catch (e: Exception) {
                Log.e("StepCounterViewModel", "Ошибка загрузки статистики: ${e.message}")
            }
        }
    }

    // Расчеты детализированной статистики
    private fun calculateDistance(steps: Int): Float {
        return steps * stepLengthMeters / 1000f // Конвертируем в км
    }

    private fun calculateCalories(steps: Int): Float {
        return steps * caloriesPerStep
    }

    private fun calculateActiveTime(steps: Int): Int {
        return (steps / stepsPerMinute).toInt()
    }

    // Получение цели на сегодня
    private fun getTodaysGoal(): Int {
        if (!hasCustomGoalsFlag) return dailyStepGoal

        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        return dailyGoalsMap[dayOfWeek] ?: dailyStepGoal
    }

    // Сравнение с прошлой неделей
    private fun calculateWeekComparison(): Pair<String, Int> {
        val currentWeek = calculateWeeklySteps()
        val lastWeek = calculateLastWeekSteps()

        if (lastWeek == 0) return Pair("Нет данных за прошлую неделю", R.color.gray)

        val difference = currentWeek - lastWeek
        val percentage = if (lastWeek > 0) {
            (difference.toFloat() / lastWeek * 100).toInt()
        } else 0

        return when {
            difference > 0 -> Pair("↑ На $percentage% больше, чем на прошлой неделе", R.color.gradientStart)
            difference < 0 -> Pair("↓ На ${-percentage}% меньше, чем на прошлой неделе", R.color.red)
            else -> Pair("↔ Такое же количество шагов, как на прошлой неделе", R.color.gray)
        }
    }

    private fun calculateLastWeekSteps(): Int {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.WEEK_OF_YEAR, -1)
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        var totalSteps = 0
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        repeat(7) {
            val dayKey = dateFormat.format(calendar.time)
            totalSteps += sharedPreferences.getInt(dayKey, 0)
            calendar.add(Calendar.DATE, 1)
        }

        return totalSteps
    }

    // Прогноз
    private fun calculateForecast(averageDaily: Int): String {
        val calendar = Calendar.getInstance()
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val remainingDays = daysInMonth - dayOfMonth

        val forecastMonth = averageDaily * daysInMonth
        val forecastRemaining = averageDaily * remainingDays

        val yearTotal = calculateYearlySteps()
        val yearForecast = yearTotal + (averageDaily * (365 - dayOfMonth))

        return "Прогноз на основе средней активности:\n" +
                "• За месяц: ${formatNumber(forecastMonth)} шагов\n" +
                "• За оставшиеся $remainingDays дней: ${formatNumber(forecastRemaining)} шагов\n" +
                "• За год: ${formatNumber(yearForecast)} шагов"
    }

    // Виртуальное путешествие
    private fun calculateJourneyProgress(): Pair<Int, String> {
        val prefs = sharedPreferences
        val journeyIndex = prefs.getInt("current_journey", 0)
        val journey = journeys[journeyIndex.coerceAtMost(journeys.size - 1)]

        val totalSteps = calculateYearlySteps()
        val distancePerStep = 0.00075f
        val traveledKm = totalSteps * distancePerStep

        // Остаток от деления для циклического путешествия
        val progressInCurrentJourney = traveledKm % journey.distanceKm
        val progressPercentage = (progressInCurrentJourney / journey.distanceKm * 100).toInt()

        val completedTimes = (traveledKm / journey.distanceKm).toInt()

        val status = if (completedTimes > 0) {
            "${journey.name}\n" +
                    "Вы прошли маршрут $completedTimes раз(а)!\n" +
                    "Сейчас на ${String.format("%.1f", progressInCurrentJourney)} из ${journey.distanceKm} км"
        } else {
            "${journey.name} (${journey.from} → ${journey.to})\n" +
                    "Вы на ${String.format("%.1f", progressInCurrentJourney)} из ${journey.distanceKm} км\n" +
                    "${journey.description}"
        }

        return Pair(progressPercentage.coerceIn(0, 100), status)
    }

    // Серия дней (streak)
    private fun calculateStreak() {
        viewModelScope.launch(Dispatchers.IO) {
            val calendar = Calendar.getInstance()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val prefs = sharedPreferences

            var streak = 0
            var currentDate = Date()
            var continueChecking = true

            while (continueChecking) {
                val key = dateFormat.format(currentDate)
                val steps = prefs.getInt(key, 0)
                val goal = if (hasCustomGoalsFlag) {
                    calendar.time = currentDate
                    val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                    dailyGoalsMap[dayOfWeek] ?: dailyStepGoal
                } else {
                    dailyStepGoal
                }

                if (steps >= goal) {
                    streak++
                    calendar.time = currentDate
                    calendar.add(Calendar.DATE, -1)
                    currentDate = calendar.time
                } else {
                    continueChecking = false
                }

                // Ограничиваем проверку 365 днями
                if (streak >= 365) continueChecking = false
            }

            _streakDays.postValue(streak)
        }
    }

    // Автоматическая корректировка целей
    fun analyzeAndAdjustGoals() {
        viewModelScope.launch(Dispatchers.IO) {
            val calendar = Calendar.getInstance()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            // Анализируем последние 30 дней
            calendar.add(Calendar.DATE, -30)
            val stepsByDay = mutableListOf<Int>()
            val goalsByDay = mutableListOf<Int>()

            repeat(30) {
                val key = dateFormat.format(calendar.time)
                stepsByDay.add(sharedPreferences.getInt(key, 0))

                calendar.get(Calendar.DAY_OF_WEEK).let { dayOfWeek ->
                    goalsByDay.add(if (hasCustomGoalsFlag) dailyGoalsMap[dayOfWeek] ?: dailyStepGoal else dailyStepGoal)
                }

                calendar.add(Calendar.DATE, 1)
            }

            // Анализируем выполнение целей
            val completionRates = stepsByDay.mapIndexed { index, steps ->
                val goal = goalsByDay[index]
                if (goal > 0) steps.toFloat() / goal else 0f
            }

            val averageCompletion = completionRates.average()

            // Корректируем цели при необходимости
            if (averageCompletion > 1.2) {
                adjustGoals(1.1f, "увеличены") // Увеличиваем на 10%
            } else if (averageCompletion < 0.8) {
                adjustGoals(0.9f, "уменьшены") // Уменьшаем на 10%
            }
        }
    }

    private fun adjustGoals(factor: Float, action: String) {
        val userId = currentUserId
        if (userId.isEmpty()) return

        if (hasCustomGoalsFlag) {
            // Корректируем индивидуальные цели
            val updatedGoals = mutableMapOf<String, Any>()
            val dayKeys = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

            dayKeys.forEachIndexed { index, dayKey ->
                val calendarDay = when (dayKey) {
                    "monday" -> Calendar.MONDAY
                    "tuesday" -> Calendar.TUESDAY
                    "wednesday" -> Calendar.WEDNESDAY
                    "thursday" -> Calendar.THURSDAY
                    "friday" -> Calendar.FRIDAY
                    "saturday" -> Calendar.SATURDAY
                    "sunday" -> Calendar.SUNDAY
                    else -> Calendar.MONDAY
                }

                val currentGoal = dailyGoalsMap[calendarDay] ?: dailyStepGoal
                val newGoal = (currentGoal * factor).toInt().coerceIn(1000, 30000)
                updatedGoals["dailyGoals/$dayKey"] = newGoal
                dailyGoalsMap[calendarDay] = newGoal
            }

            FirebaseDatabase.getInstance().reference
                .child("users")
                .child(userId)
                .updateChildren(updatedGoals)
        } else {
            // Корректируем общую цель
            val newGoal = (dailyStepGoal * factor).toInt().coerceIn(1000, 30000)
            FirebaseDatabase.getInstance().reference
                .child("users")
                .child(userId)
                .child("dailyGoal")
                .setValue(newGoal)
            dailyStepGoal = newGoal
        }

        // Сохраняем время последней корректировки
        sharedPreferences.edit().putLong("last_goal_adjustment", System.currentTimeMillis()).apply()

        // Обновляем статистику
        loadStatistics()
    }

    // Форматирование чисел
    private fun formatNumber(number: Int): String {
        return when {
            number >= 1_000_000 -> String.format("%.1fM", number / 1_000_000f)
            number >= 1_000 -> String.format("%.1fK", number / 1_000f)
            else -> number.toString()
        }
    }

    // Основные расчеты статистики (остаются без изменений)
    private fun calculateTodaySteps(): Int {
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return sharedPreferences.getInt(todayKey, 0)
    }

    private fun calculateWeeklySteps(): Int {
        val calendar = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
        }
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        var totalSteps = 0
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        repeat(7) {
            val dayKey = dateFormat.format(calendar.time)
            totalSteps += sharedPreferences.getInt(dayKey, 0)
            calendar.add(Calendar.DATE, 1)
        }

        return totalSteps
    }

    private fun getWeeklyChartData(): List<Int> {
        val calendar = Calendar.getInstance()
        calendar.time = Date()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val stepsList = mutableListOf<Int>()

        for (i in 6 downTo 0) {
            val date = calendar.clone() as Calendar
            date.add(Calendar.DATE, -i)
            val key = dateFormat.format(date.time)
            stepsList.add(sharedPreferences.getInt(key, 0))
        }

        return stepsList
    }

    private fun getMonthlyChartData(): List<Int> {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        val currentMonth = calendar.get(Calendar.MONTH)
        val currentYear = calendar.get(Calendar.YEAR)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val stepsList = mutableListOf<Int>()

        while (calendar.get(Calendar.MONTH) == currentMonth && calendar.get(Calendar.YEAR) == currentYear) {
            val dayKey = dateFormat.format(calendar.time)
            stepsList.add(sharedPreferences.getInt(dayKey, 0))
            calendar.add(Calendar.DATE, 1)
        }

        // Возвращаем последние 30 дней или меньше
        return if (stepsList.size > 30) stepsList.takeLast(30) else stepsList
    }

    private fun calculateMonthlySteps(): Int {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val currentMonth = calendar.get(Calendar.MONTH)
        val currentYear = calendar.get(Calendar.YEAR)
        var totalSteps = 0
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        while (calendar.get(Calendar.MONTH) == currentMonth && calendar.get(Calendar.YEAR) == currentYear) {
            val dayKey = dateFormat.format(calendar.time)
            totalSteps += sharedPreferences.getInt(dayKey, 0)
            calendar.add(Calendar.DATE, 1)
        }

        return totalSteps
    }

    private fun calculateYearlySteps(): Int {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val currentYear = calendar.get(Calendar.YEAR)
        var totalSteps = 0
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        while (calendar.get(Calendar.YEAR) == currentYear) {
            val dayKey = dateFormat.format(calendar.time)
            totalSteps += sharedPreferences.getInt(dayKey, 0)
            calendar.add(Calendar.DATE, 1)
            if (calendar.get(Calendar.YEAR) > currentYear) break
        }

        return totalSteps
    }

    private fun calculateMaxSteps(): Int {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.DATE, -30)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        var maxSteps = 0

        repeat(30) {
            val dayKey = dateFormat.format(calendar.time)
            maxSteps = max(maxSteps, sharedPreferences.getInt(dayKey, 0))
            calendar.add(Calendar.DATE, 1)
        }

        return maxSteps
    }

    private fun calculateAverageSteps(): Int {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.DATE, -30)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        var totalSteps = 0
        var daysWithData = 0

        repeat(30) {
            val dayKey = dateFormat.format(calendar.time)
            val daySteps = sharedPreferences.getInt(dayKey, 0)
            if (daySteps > 0) {
                totalSteps += daySteps
                daysWithData++
            }
            calendar.add(Calendar.DATE, 1)
        }

        return if (daysWithData > 0) totalSteps / daysWithData else 0
    }

    // Обновление шагов
    fun updateTodaySteps(steps: Int) {
        _todaySteps.postValue(steps)

        // Пересчитываем производные значения
        _todayDistance.postValue(calculateDistance(steps))
        _todayCalories.postValue(calculateCalories(steps))
        _activeTime.postValue(calculateActiveTime(steps))

        // Обновляем прогресс цели
        val todayGoal = getTodaysGoal()
        val progressPercent = if (todayGoal > 0) (steps * 100 / todayGoal) else 0
        _progressPercentage.postValue(progressPercent.coerceIn(0, 100))
        _goalProgress.postValue(Pair(steps, todayGoal))
    }

    // Data class для маршрутов
    data class JourneyRoute(
        val name: String,
        val from: String,
        val to: String,
        val distanceKm: Float,
        val description: String
    )
}