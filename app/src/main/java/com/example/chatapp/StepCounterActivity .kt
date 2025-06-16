
package com.example.chatapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class StepCounterActivity : AppCompatActivity() {
    private lateinit var tvToday: TextView
    private lateinit var tvWeek: TextView
    private lateinit var tvMonth: TextView
    private lateinit var tvYear: TextView
    private lateinit var tvAverage: TextView
    private lateinit var tvMaxDay: TextView

    private val prefs by lazy {
        getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
    }

    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            loadAndShowStats()
            updateHandler.postDelayed(this, 5000)
        }
    }

    private val stepsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == StepCounterService.ACTION_STEPS_UPDATED) {
                loadAndShowStats()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_step_counter)

        initViews()
        checkPermissions()
        setupButton()

        // Регистрация BroadcastReceiver с учетом версии Android
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(stepsReceiver, IntentFilter(StepCounterService.ACTION_STEPS_UPDATED), receiverFlags)
    }

    override fun onResume() {
        super.onResume()
        loadAndShowStats()
        updateHandler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        updateHandler.removeCallbacks(updateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stepsReceiver)
    }

    private fun initViews() {
        tvToday = findViewById(R.id.tv_today)
        tvWeek = findViewById(R.id.tv_week)
        tvMonth = findViewById(R.id.tv_month)
        tvYear = findViewById(R.id.tv_year)
        tvAverage = findViewById(R.id.tv_average)
        tvMaxDay = findViewById(R.id.tv_max_day)
    }

    private fun setupButton() {
        findViewById<Button>(R.id.btn_show_top).setOnClickListener {
            startActivity(Intent(this, TopUsersActivity::class.java))
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            add(Manifest.permission.BODY_SENSORS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
        } else {
            StepCounterService.startService(this)
        }
    }

    private fun loadAndShowStats() {
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val calendar = Calendar.getInstance()

        val stepsToday = prefs.getInt(todayKey, 0)
        val stepsWeek = calculateWeeklySteps()
        val stepsMonth = calculateMonthlySteps()
        val stepsYear = calculateYearlySteps()
        val avgWeek = if (stepsWeek > 0) stepsWeek / 7 else 0
        val maxDay = prefs.getInt("max_steps_30days", 0)

        tvToday.text = getString(R.string.today_steps, stepsToday)
        tvWeek.text = getString(R.string.week_steps, stepsWeek)
        tvMonth.text = getString(R.string.month_steps, stepsMonth)
        tvYear.text = getString(R.string.year_steps, stepsYear)
        tvAverage.text = getString(R.string.average_week, avgWeek)
        tvMaxDay.text = getString(R.string.max_day_steps, maxDay)
    }

    private fun calculateWeeklySteps(): Int {
        val calendar = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
        }
        val currentWeek = calendar.get(Calendar.WEEK_OF_YEAR)
        val currentYear = calendar.get(Calendar.YEAR)

        // Установка на начало недели (понедельник)
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        calendar.set(Calendar.WEEK_OF_YEAR, currentWeek)
        calendar.set(Calendar.YEAR, currentYear)

        var sum = 0
        repeat(7) {
            val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
            sum += prefs.getInt(dayKey, 0)
            calendar.add(Calendar.DATE, 1)
        }
        return sum
    }

    private fun calculateMonthlySteps(): Int {
        val monthKey = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        return prefs.all.entries
            .filter { entry -> entry.key is String && entry.key.toString().startsWith(monthKey) }
            .sumBy { entry -> (entry.value as? Int) ?: 0 }
    }

    private fun calculateYearlySteps(): Int {
        val yearKey = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date())
        return prefs.all.entries
            .filter { entry -> entry.key is String && entry.key.toString().startsWith(yearKey) }
            .sumBy { entry -> (entry.value as? Int) ?: 0 }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            StepCounterService.startService(this)
        } else {
            Toast.makeText(
                this,
                "Для работы шагомера необходимы все разрешения",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
/*
 * Главный экран приложения с отображением статистики шагов.
 * Функционал:
 *  - Запрашивает необходимые разрешения
 *  - Запускает фоновый сервис StepCounterService
 *  - Регистрирует BroadcastReceiver для обновления UI
 *  - Отображает статистику за разные периоды (день, неделя, месяц, год)
 *  - Рассчитывает среднее значение и максимальное количество шагов
 *  - Обновляет данные каждые 5 секунд и при получении новых шагов
 *  - Переход к топу пользователей через кнопку
 * 
 * Улучшения:
 *  - Исправлен расчет недели (теперь учитывается понедельник как начало недели)
 *  - Оптимизирована регистрация BroadcastReceiver для новых версий Android
 */