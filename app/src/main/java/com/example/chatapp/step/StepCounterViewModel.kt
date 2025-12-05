package com.example.chatapp.step

import android.app.Application
import android.content.Context
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

    // LiveData для UI
    private val _todaySteps = MutableLiveData<Int>()
    val todaySteps: LiveData<Int> = _todaySteps

    private val _weeklySteps = MutableLiveData<Int>()
    val weeklySteps: LiveData<Int> = _weeklySteps

    private val _monthlySteps = MutableLiveData<Int>()
    val monthlySteps: LiveData<Int> = _monthlySteps

    private val _yearlySteps = MutableLiveData<Int>()
    val yearlySteps: LiveData<Int> = _yearlySteps

    private val _averageSteps = MutableLiveData<Int>()
    val averageSteps: LiveData<Int> = _averageSteps

    private val _maxSteps = MutableLiveData<Int>()
    val maxSteps: LiveData<Int> = _maxSteps

    private val _goalProgress = MutableLiveData<Pair<Int, Int>>()
    val goalProgress: LiveData<Pair<Int, Int>> = _goalProgress

    private val _cardColorRes = MutableLiveData<Int>()
    val cardColorRes: LiveData<Int> = _cardColorRes

    // 🔹 НОВОЕ: LiveData для графика (последние 7 дней)
    private val _weeklyChartData = MutableLiveData<List<Int>>()
    val weeklyChartData: LiveData<List<Int>> = _weeklyChartData

    // Данные
    private val sharedPreferences = application.getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
    private val firebaseDatabase = FirebaseDatabase.getInstance()
    private val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    private var dailyStepGoal = sharedPreferences.getInt("daily_goal", 10000)

    init {
        setupFirebaseListeners()
        loadStatistics()
    }

    private fun setupFirebaseListeners() {
        val stepsReference = firebaseDatabase.reference
            .child("users")
            .child(currentUserId)
            .child("stepsData")

        stepsReference.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                loadStatistics()
            }

            override fun onCancelled(error: DatabaseError) {
                // Ошибка обрабатывается в репозитории
            }
        })

        val goalReference = firebaseDatabase.reference
            .child("users")
            .child(currentUserId)
            .child("dailyGoal")

        goalReference.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                dailyStepGoal = snapshot.getValue(Int::class.java) ?: 10000
                sharedPreferences.edit().putInt("daily_goal", dailyStepGoal).apply()
                updateGoalProgress()
            }

            override fun onCancelled(error: DatabaseError) {
                // Используем значение по умолчанию
            }
        })
    }

    fun loadStatistics() {
        viewModelScope.launch {
            val today = withContext(Dispatchers.IO) { calculateTodaySteps() }
            val week = withContext(Dispatchers.IO) { calculateWeeklySteps() }
            val month = withContext(Dispatchers.IO) { calculateMonthlySteps() }
            val year = withContext(Dispatchers.IO) { calculateYearlySteps() }
            val max = withContext(Dispatchers.IO) { calculateMaxSteps() }
            val avg = withContext(Dispatchers.IO) { calculateAverageSteps() }
            val chartData = withContext(Dispatchers.IO) { getWeeklyChartData() }

            _todaySteps.postValue(today)
            _weeklySteps.postValue(week)
            _monthlySteps.postValue(month)
            _yearlySteps.postValue(year)
            _averageSteps.postValue(avg)
            _maxSteps.postValue(max)
            _weeklyChartData.postValue(chartData) // 🔹

            updateGoalProgress()
            updateCardColor(today)
        }
    }

    private fun updateGoalProgress() {
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val stepsToday = sharedPreferences.getInt(todayKey, 0)
        _goalProgress.postValue(Pair(stepsToday, dailyStepGoal))
    }

    private fun updateCardColor(stepsToday: Int) {
        val colorRes = if (stepsToday >= dailyStepGoal) {
            R.color.goal_achieved
        } else {
            R.color.colorPrimary
        }
        _cardColorRes.postValue(colorRes)
    }

    // Улучшенные методы расчета статистики
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

    // 🔹 НОВОЕ: возвращает список шагов за последние 7 дней (от старых к новым)
    private fun getWeeklyChartData(): List<Int> {
        val calendar = Calendar.getInstance()
        calendar.time = Date()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val stepsList = mutableListOf<Int>()

        // Добавляем от 6 дней назад до сегодня (всего 7 дней)
        for (i in 6 downTo 0) {
            val date = calendar.clone() as Calendar
            date.add(Calendar.DATE, -i)
            val key = dateFormat.format(date.time)
            stepsList.add(sharedPreferences.getInt(key, 0))
        }

        return stepsList
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

    fun updateTodaySteps(steps: Int) {
        _todaySteps.postValue(steps)
    }
}