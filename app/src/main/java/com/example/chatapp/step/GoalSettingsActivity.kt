package com.example.chatapp.step

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.R
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class GoalSettingsActivity : AppCompatActivity() {

    private lateinit var seekBarGoal: SeekBar
    private lateinit var tvGoalValue: TextView
    private lateinit var switchCustomDays: SwitchMaterial
    private lateinit var containerCustomGoals: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button

    // Массивы для дней недели
    private val dayCardIds = listOf(
        R.id.card_monday, R.id.card_tuesday, R.id.card_wednesday,
        R.id.card_thursday, R.id.card_friday, R.id.card_saturday,
        R.id.card_sunday
    )

    private val dayNames = listOf(
        "Понедельник", "Вторник", "Среда", "Четверг",
        "Пятница", "Суббота", "Воскресенье"
    )

    private val dayKeys = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    private val dailyGoals = mutableMapOf<Int, Int>() // День недели -> цель
    private val defaultGoal = 10000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_goal_settings)

        initViews()
        setupListeners()
        loadCurrentGoals()
    }

    private fun initViews() {
        seekBarGoal = findViewById(R.id.seekBar_goal)
        tvGoalValue = findViewById(R.id.tv_goal_value)
        switchCustomDays = findViewById(R.id.switch_custom_days)
        containerCustomGoals = findViewById(R.id.container_custom_goals)
        btnSave = findViewById(R.id.btn_save)
        btnCancel = findViewById(R.id.btn_cancel)

        // Настройка SeekBar
        seekBarGoal.max = 30000
        seekBarGoal.progress = defaultGoal
        updateGoalText(defaultGoal)
    }

    private fun setupListeners() {
        seekBarGoal.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateGoalText(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        switchCustomDays.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                initCustomDays()
            }
            containerCustomGoals.visibility = if (isChecked) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        btnSave.setOnClickListener { saveGoals() }
        btnCancel.setOnClickListener { finish() }
    }

    private fun updateGoalText(progress: Int) {
        tvGoalValue.text = "$progress шагов"
    }

    private fun initCustomDays() {
        dayCardIds.forEachIndexed { index, cardId ->
            val card = findViewById<androidx.cardview.widget.CardView>(cardId)

            // Ищем TextView внутри карточки
            val tvDay = card.findViewById<TextView>(R.id.tv_day_name)
            val tvGoal = card.findViewById<TextView>(R.id.tv_day_goal)
            val btnIncrease = card.findViewById<android.widget.ImageButton>(R.id.btn_increase)
            val btnDecrease = card.findViewById<android.widget.ImageButton>(R.id.btn_decrease)

            // Проверяем что все View найдены
            if (tvDay == null || tvGoal == null || btnIncrease == null || btnDecrease == null) {
                Log.e("GoalSettings", "Не найдены View для дня $index")
                return@forEachIndexed
            }

            tvDay.text = dayNames[index]

            // Устанавливаем начальное значение
            val initialGoal = dailyGoals[index] ?: defaultGoal
            tvGoal.text = "$initialGoal"

            // Обработчики кнопок
            btnIncrease.setOnClickListener {
                val current = tvGoal.text.toString().toIntOrNull() ?: defaultGoal
                val newValue = minOf(current + 500, 30000)
                tvGoal.text = "$newValue"
                dailyGoals[index] = newValue
            }

            btnDecrease.setOnClickListener {
                val current = tvGoal.text.toString().toIntOrNull() ?: defaultGoal
                val newValue = maxOf(current - 500, 1000)
                tvGoal.text = "$newValue"
                dailyGoals[index] = newValue
            }
        }
    }

    private fun loadCurrentGoals() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        FirebaseDatabase.getInstance().reference
            .child("users")
            .child(userId)
            .child("dailyGoals")
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    // Используем forEach для обхода всех детей
                    snapshot.children.forEach { child ->
                        val dayKey = child.key
                        val goal = child.getValue(Int::class.java) ?: defaultGoal

                        val dayIndex = dayKeys.indexOf(dayKey)
                        if (dayIndex != -1) {
                            dailyGoals[dayIndex] = goal
                        }
                    }

                    // Если есть индивидуальные цели, показываем переключатель
                    if (dailyGoals.isNotEmpty()) {
                        switchCustomDays.isChecked = true
                        initCustomDays() // Инициализируем после установки значения
                    }
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "Ошибка загрузки целей: ${it.message}")
            }
    }

    private fun saveGoals() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val database = FirebaseDatabase.getInstance().reference
            .child("users")
            .child(userId)

        val goalsToSave = mutableMapOf<String, Any>()

        if (switchCustomDays.isChecked) {
            // Сохраняем индивидуальные цели
            dailyGoals.forEach { (dayIndex, goal) ->
                if (dayIndex in dayKeys.indices) {
                    goalsToSave["dailyGoals/${dayKeys[dayIndex]}"] = goal
                }
            }
            goalsToSave["hasCustomGoals"] = true

            // Также сохраняем общую цель как среднее
            val avgGoal = if (dailyGoals.isNotEmpty()) {
                dailyGoals.values.average().toInt()
            } else {
                defaultGoal
            }
            goalsToSave["dailyGoal"] = avgGoal
        } else {
            // Сохраняем общую цель
            val defaultGoal = seekBarGoal.progress
            goalsToSave["dailyGoal"] = defaultGoal
            goalsToSave["hasCustomGoals"] = false
        }

        // Сохраняем в Firebase
        database.updateChildren(goalsToSave)
            .addOnSuccessListener {
                Toast.makeText(this, "Цели сохранены!", Toast.LENGTH_SHORT).show()

                // Обновляем SharedPreferences
                val prefs = getSharedPreferences("step_prefs", MODE_PRIVATE)
                if (!switchCustomDays.isChecked) {
                    prefs.edit().putInt("daily_goal", seekBarGoal.progress).apply()
                }

                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Ошибка сохранения: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    companion object {
        private const val TAG = "GoalSettingsActivity"
    }
}