package com.example.chatapp.viewmodels

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
            // Загружаем данные в фоне
            val today = withContext(Dispatchers.IO) { calculateTodaySteps() }
            val week = withContext(Dispatchers.IO) { calculateWeeklySteps() }
            val month = withContext(Dispatchers.IO) { calculateMonthlySteps() }
            val year = withContext(Dispatchers.IO) { calculateYearlySteps() }
            val max = withContext(Dispatchers.IO) { calculateMaxSteps() }
            val avg = if (week > 0) week / 7 else 0

            // Обновляем LiveData в основном потоке
            _todaySteps.postValue(today)
            _weeklySteps.postValue(week)
            _monthlySteps.postValue(month)
            _yearlySteps.postValue(year)
            _averageSteps.postValue(avg)
            _maxSteps.postValue(max)

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

    // Расчеты статистики
    private fun calculateTodaySteps(): Int {
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return sharedPreferences.getInt(todayKey, 0)
    }

    private fun calculateWeeklySteps(): Int {
        val calendar = Calendar.getInstance().apply {
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

        while (calendar.get(Calendar.MONTH) == currentMonth &&
            calendar.get(Calendar.YEAR) == currentYear) {
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
        }

        return totalSteps
    }

    fun updateTodaySteps(steps: Int) {
        _todaySteps.postValue(steps)
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
}