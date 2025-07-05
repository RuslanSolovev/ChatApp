package com.example.chatapp

import android.Manifest
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.*

class StepCounterActivity : AppCompatActivity() {

    private lateinit var tvToday: TextView
    private lateinit var tvWeek: TextView
    private lateinit var tvMonth: TextView
    private lateinit var tvYear: TextView
    private lateinit var tvAverage: TextView
    private lateinit var tvMaxDay: TextView
    private lateinit var tvGoal: TextView
    private lateinit var progressSteps: ProgressBar
    private lateinit var cardToday: CardView
    private lateinit var cardWeek: CardView
    private lateinit var cardMonth: CardView
    private lateinit var cardYear: CardView
    private lateinit var cardAverage: CardView
    private lateinit var cardMax: CardView

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

    private lateinit var database: FirebaseDatabase
    private lateinit var userId: String
    private var dailyGoal = 10000 // Цель по шагам

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_step_counter)

        userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        database = FirebaseDatabase.getInstance()

        initViews()
        setupAnimations()
        checkPermissions()
        setupButton()
        setupFirebaseListeners()

        dailyGoal = prefs.getInt("daily_goal", 10000)
        progressSteps.max = dailyGoal

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
        tvGoal = findViewById(R.id.tv_goal)
        progressSteps = findViewById(R.id.progress_steps)

        cardToday = findViewById(R.id.card_today)
        cardWeek = findViewById(R.id.card_week)
        cardMonth = findViewById(R.id.card_month)
        cardYear = findViewById(R.id.card_year)
        cardAverage = findViewById(R.id.card_average)
        cardMax = findViewById(R.id.card_max)
    }

    private fun setupAnimations() {
        val cards = listOf(cardToday, cardWeek, cardMonth, cardYear, cardAverage, cardMax)
        cards.forEachIndexed { index, card ->
            card.alpha = 0f
            card.translationY = 50f
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(200L * index)
                .setDuration(500)
                .start()
        }
    }

    private fun setupButton() {
        findViewById<Button>(R.id.btn_show_top).setOnClickListener {
            startActivity(Intent(this, TopUsersActivity::class.java))
        }
    }

    private fun setupFirebaseListeners() {
        val stepsRef = database.reference.child("users").child(userId).child("stepsData")
        stepsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                loadAndShowStats()
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(
                    this@StepCounterActivity,
                    "Ошибка синхронизации: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        val goalRef = database.reference.child("users").child(userId).child("dailyGoal")
        goalRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                dailyGoal = snapshot.getValue(Int::class.java) ?: 10000
                progressSteps.max = dailyGoal
                prefs.edit().putInt("daily_goal", dailyGoal).apply()

                val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val stepsToday = prefs.getInt(todayKey, 0)
                updateGoalText(stepsToday, dailyGoal)
            }

            override fun onCancelled(error: DatabaseError) {
                // Используем значение по умолчанию
            }
        })
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
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
        val stepsToday = prefs.getInt(todayKey, 0)
        val stepsWeek = calculateWeeklySteps()
        val stepsMonth = calculateMonthlySteps()
        val stepsYear = calculateYearlySteps()
        val avgWeek = if (stepsWeek > 0) stepsWeek / 7 else 0
        val maxDay = prefs.getInt("max_steps_30days", 0)

        updateGoalText(stepsToday, dailyGoal)
        animateValueChange(tvToday, stepsToday, "Сегодня: %d шагов")
        animateValueChange(tvWeek, stepsWeek, "Неделя: %d шагов")
        animateValueChange(tvMonth, stepsMonth, "Месяц: %d шагов")
        animateValueChange(tvYear, stepsYear, "Год: %d шагов")
        animateValueChange(tvAverage, avgWeek, "Среднее: %d шагов/день")
        animateValueChange(tvMaxDay, maxDay, "Рекорд: %d шагов")
        animateProgress(stepsToday)

        if (stepsToday >= dailyGoal) {
            cardToday.setCardBackgroundColor(Color.parseColor("#E8F5E9"))
            progressSteps.progressTintList = ContextCompat.getColorStateList(this, R.color.goal_achieved)
        } else {
            cardToday.setCardBackgroundColor(Color.WHITE)
            progressSteps.progressTintList = ContextCompat.getColorStateList(this, R.color.colorPrimary)
        }
    }

    private fun updateGoalText(currentSteps: Int, goal: Int) {
        val goalText = "$currentSteps / $goal шагов"
        tvGoal.text = goalText
    }

    private fun animateValueChange(textView: TextView, newValue: Int, format: String) {
        val oldValue = textView.text
            .replace(Regex("[^\\d]"), "")
            .toIntOrNull() ?: 0

        if (oldValue != newValue) {
            val animator = ValueAnimator.ofInt(oldValue, newValue)
            animator.duration = 1000
            animator.interpolator = DecelerateInterpolator()
            animator.addUpdateListener { animation ->
                val animatedValue = animation.animatedValue as Int
                textView.text = String.format(format, animatedValue)
            }
            animator.start()
        }
    }

    private fun animateProgress(newProgress: Int) {
        val animator = ValueAnimator.ofInt(progressSteps.progress, newProgress)
        animator.duration = 1000
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            progressSteps.progress = animation.animatedValue as Int
        }
        animator.start()
    }

    private fun calculateWeeklySteps(): Int {
        val calendar = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
        }
        val currentWeek = calendar.get(Calendar.WEEK_OF_YEAR)
        val currentYear = calendar.get(Calendar.YEAR)

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

