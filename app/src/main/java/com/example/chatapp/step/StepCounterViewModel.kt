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

    companion object {
        private const val TAG = "StepCounterViewModel"
        private const val STEP_LENGTH_METERS = 0.75f // Средняя длина шага
        private const val CALORIES_PER_STEP = 0.04f // Калории на шаг
        private const val STEPS_PER_MINUTE = 100f // Средняя скорость
    }

    private var isSyncingFromFirebase = false

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

    private val _weeklyChartLabels = MutableLiveData<List<String>>()
    val weeklyChartLabels: LiveData<List<String>> = _weeklyChartLabels

    private val _monthlyChartLabels = MutableLiveData<List<String>>()
    val monthlyChartLabels: LiveData<List<String>> = _monthlyChartLabels

    private val _yearlyChartLabels = MutableLiveData<List<String>>()
    val yearlyChartLabels: LiveData<List<String>> = _yearlyChartLabels

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
    private val stepDataManager = StepDataManager.getInstance(application)
    private val sharedPreferences = application.getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
    private val firebaseDatabase = FirebaseDatabase.getInstance()
    private val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    private var dailyStepGoal = 10000
    private var hasCustomGoalsFlag = false
    private val dailyGoalsMap = mutableMapOf<Int, Int>()

    // Виртуальные маршруты
    private val journeys = listOf(
        JourneyRoute("Фрязино → Щелково", "Фрязино", "Щелково", 12.5f, "Первая остановка - соседний город"),
        JourneyRoute("Фрязино → Ивантеевка", "Фрязино", "Ивантеевка", 10.3f, "Вдоль реки Учи"),
        JourneyRoute("Фрязино → Королёв", "Фрязино", "Королёв", 20.7f, "Город космонавтики"),
        JourneyRoute("Фрязино → Мытищи", "Фрязино", "Мытищи", 28.4f, "Крупный соседний город"),
        JourneyRoute("Фрязино → Москва", "Фрязино", "Москва", 35.0f, "Столица России"),
        JourneyRoute("Фрязино → Санкт-Петербург", "Фрязино", "Санкт-Петербург", 710.0f, "Северная столица"),
        JourneyRoute("Фрязино → Сочи", "Фрязино", "Сочи", 1820.0f, "Черноморское побережье"),
        JourneyRoute("Фрязино → Крым", "Фрязино", "Крым", 2000.0f, "Полуостров в Чёрном море")
    )

    init {
        setupFirebaseListeners()
        loadUserGoals()

        // Миграция старых данных при первом запуске
        viewModelScope.launch(Dispatchers.IO) {
            stepDataManager.migrateOldData()
            loadStatistics()
        }
    }

    private fun setupFirebaseListeners() {
        val userId = currentUserId
        if (userId.isEmpty()) {
            Log.w(TAG, "Пользователь не авторизован")
            loadStatistics() // Загружаем локальные данные
            return
        }

        val userRef = firebaseDatabase.reference.child("users").child(userId)

        // Слушатель шагов
        userRef.child("stepsData").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d(TAG, "Данные шагов обновлены из Firebase")

                // Синхронизируем данные из Firebase с локальным хранилищем
                viewModelScope.launch(Dispatchers.IO) {
                    syncFromFirebase(snapshot)
                    loadStatistics()
                    isSyncingFromFirebase = false
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Ошибка загрузки шагов: ${error.message}")
            }
        })

        // Слушатель целей и настроек
        userRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Загружаем цель
                dailyStepGoal = snapshot.child("dailyGoal").getValue(Int::class.java) ?: 10000
                sharedPreferences.edit().putInt("daily_goal_$currentUserId", dailyStepGoal).apply()

                // Проверяем индивидуальные цели
                hasCustomGoalsFlag = snapshot.child("hasCustomGoals").getValue(Boolean::class.java) ?: false
                _hasCustomGoals.postValue(hasCustomGoalsFlag)

                if (hasCustomGoalsFlag) {
                    loadCustomGoals(snapshot)
                }

                // Настройки мотивации
                _motivationEnabled.postValue(
                    snapshot.child("motivationEnabled").getValue(Boolean::class.java) ?: true
                )

                loadStatistics()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Ошибка загрузки целей: ${error.message}")
            }
        })
    }

    private suspend fun syncFromFirebase(snapshot: DataSnapshot) {
        withContext(Dispatchers.IO) {
            try {
                snapshot.children.forEach { daySnapshot ->
                    val date = daySnapshot.key ?: return@forEach
                    val steps = daySnapshot.getValue(Int::class.java) ?: 0

                    // Сохраняем в локальное хранилище через StepDataManager
                    stepDataManager.saveSteps(date, steps)

                    // Также сохраняем в SharedPreferences для обратной совместимости

                }
                Log.d(TAG, "Данные синхронизированы из Firebase")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка синхронизации из Firebase", e)
            }
        }
    }

    private fun loadCustomGoals(snapshot: DataSnapshot) {
        dailyGoalsMap.clear()
        val goals = snapshot.child("dailyGoals")

        if (goals.exists()) {
            val dayMapping = mapOf(
                "monday" to Calendar.MONDAY,
                "tuesday" to Calendar.TUESDAY,
                "wednesday" to Calendar.WEDNESDAY,
                "thursday" to Calendar.THURSDAY,
                "friday" to Calendar.FRIDAY,
                "saturday" to Calendar.SATURDAY,
                "sunday" to Calendar.SUNDAY
            )

            dayMapping.forEach { (key, day) ->
                val goal = goals.child(key).getValue(Int::class.java) ?: dailyStepGoal
                dailyGoalsMap[day] = goal
            }
        }
    }

    private fun loadUserGoals() {
        val goalKey = "daily_goal_$currentUserId"
        dailyStepGoal = sharedPreferences.getInt(goalKey, 10000)
    }

    fun loadStatistics() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Загрузка статистики...")

                // Основные расчеты
                val today = calculateTodaySteps()
                val week = calculateWeeklySteps()
                val month = calculateMonthlySteps()
                val record = calculateMaxSteps()
                val avg = calculateAverageSteps()

                Log.d(TAG, "Рассчитано: today=$today, week=$week, month=$month, record=$record, avg=$avg")

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
                val comparison = calculateWeekComparison()
                _comparisonText.postValue(comparison.first)
                _comparisonColor.postValue(comparison.second)

                // Прогноз
                val forecast = calculateForecast(avg)
                _forecastText.postValue(forecast)

                // Виртуальное путешествие
                val journey = calculateJourneyProgress()
                _journeyProgress.postValue(journey.first)
                _journeyText.postValue(journey.second)

                // Данные для графиков
                val weeklyData = getWeeklyChartData()
                val monthlyData = getMonthlyChartData()
                val yearlyData = getYearlyChartData()

                _weeklyChartData.postValue(weeklyData)
                _monthlyChartData.postValue(monthlyData)
                _yearlyChartData.postValue(yearlyData)

                // Метки для графиков
                _weeklyChartLabels.postValue(getWeekDaysRussian())
                _monthlyChartLabels.postValue(getMonthDaysLabels(monthlyData.size))
                _yearlyChartLabels.postValue(getMonthNamesRussianShort())

                // Серия дней
                calculateStreak()

                Log.d(TAG, "Статистика успешно загружена")

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки статистики: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // Основные расчеты
    private fun calculateTodaySteps(): Int {
        return stepDataManager.getTodaySteps()
    }

    private fun calculateWeeklySteps(): Int {
        val calendar = Calendar.getInstance()
        calendar.firstDayOfWeek = Calendar.MONDAY
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        resetCalendarTime(calendar)

        var totalSteps = 0
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        repeat(7) {
            val dayKey = dateFormat.format(calendar.time)
            totalSteps += getStepsForDate(dayKey)
            calendar.add(Calendar.DATE, 1)
        }

        return totalSteps
    }

    private fun calculateMonthlySteps(): Int {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        resetCalendarTime(calendar)

        val currentMonth = calendar.get(Calendar.MONTH)
        val currentYear = calendar.get(Calendar.YEAR)
        var totalSteps = 0
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        while (calendar.get(Calendar.MONTH) == currentMonth &&
            calendar.get(Calendar.YEAR) == currentYear) {
            val dayKey = dateFormat.format(calendar.time)
            totalSteps += getStepsForDate(dayKey)
            calendar.add(Calendar.DATE, 1)
        }

        return totalSteps
    }

    private fun calculateYearlySteps(): Int {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.MONTH, Calendar.JANUARY)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        resetCalendarTime(calendar)

        val currentYear = calendar.get(Calendar.YEAR)
        var totalSteps = 0
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        while (calendar.get(Calendar.YEAR) == currentYear) {
            val dayKey = dateFormat.format(calendar.time)
            totalSteps += getStepsForDate(dayKey)
            calendar.add(Calendar.DATE, 1)

            if (calendar.get(Calendar.YEAR) > currentYear) break
        }

        return totalSteps
    }

    private fun calculateMaxSteps(): Int {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DATE, -30)
        resetCalendarTime(calendar)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        var maxSteps = 0

        repeat(30) {
            val dayKey = dateFormat.format(calendar.time)
            maxSteps = max(maxSteps, getStepsForDate(dayKey))
            calendar.add(Calendar.DATE, 1)
        }

        return maxSteps
    }

    private fun calculateAverageSteps(): Int {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DATE, -30)
        resetCalendarTime(calendar)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        var totalSteps = 0
        var daysWithData = 0

        repeat(30) {
            val dayKey = dateFormat.format(calendar.time)
            val daySteps = getStepsForDate(dayKey)
            if (daySteps > 0) {
                totalSteps += daySteps
                daysWithData++
            }
            calendar.add(Calendar.DATE, 1)
        }

        return if (daysWithData > 0) totalSteps / daysWithData else 0
    }

    private fun getStepsForDate(dateKey: String): Int {
        return stepDataManager.getSteps(dateKey)
    }

    // Вспомогательные методы
    private fun getDateKey(date: Date): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
    }

    private fun resetCalendarTime(calendar: Calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
    }

    // Детализированная статистика
    private fun calculateDistance(steps: Int): Float {
        return steps * STEP_LENGTH_METERS / 1000f
    }

    private fun calculateCalories(steps: Int): Float {
        return steps * CALORIES_PER_STEP
    }

    private fun calculateActiveTime(steps: Int): Int {
        return (steps / STEPS_PER_MINUTE).toInt()
    }

    // Цели
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
            difference > 500 -> Pair("↑ На $percentage% больше, чем на прошлой неделе!", R.color.gradientStart)
            difference > 0 -> Pair("↑ На $percentage% больше, чем на прошлой неделе", R.color.external_news_bg)
            difference < -500 -> Pair("↓ На ${-percentage}% меньше, чем на прошлой неделе", R.color.red)
            difference < 0 -> Pair("↓ На ${-percentage}% меньше, чем на прошлой неделе", R.color.round_active_color)
            else -> Pair("↔ Примерно столько же, как на прошлой неделе", R.color.gray)
        }
    }

    private fun calculateLastWeekSteps(): Int {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.WEEK_OF_YEAR, -1)
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            resetCalendarTime(this)
        }

        var totalSteps = 0
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        repeat(7) {
            val dayKey = dateFormat.format(calendar.time)
            totalSteps += getStepsForDate(dayKey)
            calendar.add(Calendar.DATE, 1)
        }

        return totalSteps
    }

    // Прогноз
    private fun calculateForecast(averageDaily: Int): String {
        val calendar = Calendar.getInstance()
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val remainingDays = daysInMonth - dayOfMonth + 1

        val forecastMonth = averageDaily * daysInMonth
        val forecastRemaining = averageDaily * remainingDays

        val yearTotal = calculateYearlySteps()
        val daysPassed = calendar.get(Calendar.DAY_OF_YEAR)
        val yearForecast = (yearTotal.toFloat() / daysPassed * 365).toInt()

        return "Прогноз на основе вашей активности:\n" +
                "• За месяц: ${formatNumber(forecastMonth)} шагов\n" +
                "• За оставшиеся $remainingDays дней: ${formatNumber(forecastRemaining)} шагов\n" +
                "• За год: ${formatNumber(yearForecast)} шагов"
    }

    // Виртуальное путешествие
    private fun calculateJourneyProgress(): Pair<Int, String> {
        val totalSteps = calculateYearlySteps()
        val distancePerStep = 0.00075f
        val traveledKm = totalSteps * distancePerStep

        // Определяем текущий маршрут
        var accumulatedDistance = 0f
        var currentJourneyIndex = 0
        var journeyDistance = 0f

        for ((index, journey) in journeys.withIndex()) {
            accumulatedDistance += journey.distanceKm
            if (traveledKm <= accumulatedDistance) {
                currentJourneyIndex = index
                journeyDistance = journey.distanceKm
                break
            }
        }

        // Если прошли все маршруты, остаемся на последнем
        if (traveledKm > accumulatedDistance) {
            currentJourneyIndex = journeys.size - 1
            journeyDistance = journeys.last().distanceKm
            accumulatedDistance = journeys.sumOf { it.distanceKm.toDouble() }.toFloat()
        }

        val journey = journeys[currentJourneyIndex]
        val distanceBeforeThisJourney = accumulatedDistance - journeyDistance
        val progressInCurrentJourney = traveledKm - distanceBeforeThisJourney
        val progressPercentage = if (journeyDistance > 0) {
            (progressInCurrentJourney / journeyDistance * 100).toInt().coerceIn(0, 100)
        } else {
            100
        }

        val status = if (progressPercentage >= 100 && currentJourneyIndex < journeys.size - 1) {
            val nextJourney = journeys[currentJourneyIndex + 1]
            "🎉 Поздравляем! Вы достигли ${journey.to}!\n" +
                    "Следующая цель: ${nextJourney.name}\n" +
                    "${nextJourney.description}"
        } else {
            "${journey.name}\n" +
                    "Прогресс: ${String.format("%.1f", progressInCurrentJourney)} из ${journey.distanceKm} км\n" +
                    "${journey.description}"
        }

        return Pair(progressPercentage, status)
    }

    // Серия дней (streak)
    private fun calculateStreak() {
        viewModelScope.launch(Dispatchers.IO) {
            val calendar = Calendar.getInstance()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            var streak = 0
            var currentDate = Date()
            var continueChecking = true

            while (continueChecking) {
                val key = dateFormat.format(currentDate)
                val steps = getStepsForDate(key)
                val goal = getTodaysGoal()

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

    // Графики
    fun getWeekDaysRussian(): List<String> {
        return listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    }

    fun getMonthNamesRussianShort(): List<String> {
        return listOf("Янв", "Фев", "Мар", "Апр", "Май", "Июн",
            "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек")
    }

    private fun getMonthDaysLabels(count: Int): List<String> {
        return (1..count).map { "$it" }
    }

    private fun getWeeklyChartData(): List<Int> {
        val calendar = Calendar.getInstance()
        calendar.firstDayOfWeek = Calendar.MONDAY
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        resetCalendarTime(calendar)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val stepsList = mutableListOf<Int>()

        repeat(7) {
            val dayKey = dateFormat.format(calendar.time)
            stepsList.add(getStepsForDate(dayKey))
            calendar.add(Calendar.DATE, 1)
        }

        return stepsList
    }

    private fun getMonthlyChartData(): List<Int> {
        val calendar = Calendar.getInstance()
        val currentMonth = calendar.get(Calendar.MONTH)
        val currentYear = calendar.get(Calendar.YEAR)

        calendar.set(currentYear, currentMonth, 1)
        resetCalendarTime(calendar)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val stepsList = mutableListOf<Int>()

        while (calendar.get(Calendar.MONTH) == currentMonth) {
            val dayKey = dateFormat.format(calendar.time)
            stepsList.add(getStepsForDate(dayKey))
            calendar.add(Calendar.DATE, 1)
        }

        return stepsList
    }

    private fun getYearlyChartData(): List<Int> {
        val monthlyData = mutableListOf<Int>()

        for (month in 0..11) {
            val monthSteps = calculateStepsForMonth(month)
            monthlyData.add(monthSteps)
        }

        return monthlyData
    }

    private fun calculateStepsForMonth(month: Int): Int {
        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)

        calendar.set(currentYear, month, 1)
        resetCalendarTime(calendar)

        val endMonth = if (month == 11) 0 else month + 1
        val endYear = if (month == 11) currentYear + 1 else currentYear

        val endCalendar = Calendar.getInstance()
        endCalendar.set(endYear, endMonth, 1)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        var totalSteps = 0

        while (calendar.before(endCalendar)) {
            val dayKey = dateFormat.format(calendar.time)
            totalSteps += getStepsForDate(dayKey)
            calendar.add(Calendar.DATE, 1)
        }

        return totalSteps
    }

    // Форматирование
    private fun formatNumber(number: Int): String {
        return when {
            number >= 1_000_000 -> String.format("%.1fM", number / 1_000_000f)
            number >= 10_000 -> String.format("%.0fK", number / 1_000f)
            number >= 1_000 -> String.format("%.1fK", number / 1_000f)
            else -> number.toString()
        }
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