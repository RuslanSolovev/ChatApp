
package com.example.chatapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatapp.adapters.TopUsersAdapter
import com.example.chatapp.databinding.ActivityTopUsersBinding
import com.example.chatapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

class TopUsersActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTopUsersBinding
    private lateinit var topUsersAdapter: TopUsersAdapter
    private val database = FirebaseDatabase.getInstance().reference
    private var currentPeriod = PERIOD_DAY
    private lateinit var prefs: SharedPreferences
    private val auth = FirebaseAuth.getInstance()
    private var usersList = ArrayList<User>()
    private var valueEventListener: ValueEventListener? = null

    companion object {
        const val PERIOD_DAY = 0
        const val PERIOD_WEEK = 1
        const val PERIOD_MONTH = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTopUsersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("step_prefs", Context.MODE_PRIVATE)

        setupToolbar()
        setupRecyclerView()
        setupPeriodButtons()
        syncLocalStepsWithFirebase()
    }

    override fun onResume() {
        super.onResume()
        setupFirebaseListener()
    }

    override fun onPause() {
        super.onPause()
        removeFirebaseListener()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Топ пользователей"
    }

    private fun setupRecyclerView() {
        topUsersAdapter = TopUsersAdapter(currentPeriod)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@TopUsersActivity)
            adapter = topUsersAdapter
        }
    }

    private fun setupPeriodButtons() {
        binding.periodGroup.check(R.id.btn_day)
        binding.periodGroup.setOnCheckedChangeListener { _, checkedId ->
            currentPeriod = when (checkedId) {
                R.id.btn_day -> PERIOD_DAY
                R.id.btn_week -> PERIOD_WEEK
                R.id.btn_month -> PERIOD_MONTH
                else -> PERIOD_DAY
            }
            updateUsersList()
        }
    }

    private fun setupFirebaseListener() {
        removeFirebaseListener()

        valueEventListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                usersList.clear()
                for (userSnapshot in snapshot.children) {
                    val user = userSnapshot.getValue(User::class.java)
                    user?.let {
                        // Пересчет шагов для текущего периода
                        it.totalSteps = calculateStepsForPeriod(it, currentPeriod)
                        usersList.add(it)
                    }
                }
                updateUsersList()
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(
                    this@TopUsersActivity,
                    "Ошибка загрузки: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        database.child("users").addValueEventListener(valueEventListener!!)
    }

    private fun removeFirebaseListener() {
        valueEventListener?.let {
            database.child("users").removeEventListener(it)
            valueEventListener = null
        }
    }

    private fun updateUsersList() {
        // Сортировка по убыванию шагов
        val sortedUsers = usersList.sortedByDescending { it.totalSteps }
        topUsersAdapter.updatePeriod(currentPeriod)
        topUsersAdapter.submitList(sortedUsers)
    }

    private fun syncLocalStepsWithFirebase() {
        val currentUser = auth.currentUser ?: return
        val userId = currentUser.uid
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val todaySteps = prefs.getInt(todayKey, 0)

        // Обновление данных в Firebase
        database.child("users").child(userId).child("stepsData").child(todayKey)
            .setValue(todaySteps)
            .addOnFailureListener { e ->
                Toast.makeText(this, "Ошибка синхронизации: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun calculateStepsForPeriod(user: User, period: Int): Int {
        return when (period) {
            PERIOD_DAY -> user.stepsData?.get(getCurrentDateKey()) ?: 0
            PERIOD_WEEK -> calculateWeeklySteps(user.stepsData)
            PERIOD_MONTH -> calculateMonthlySteps(user.stepsData)
            else -> 0
        }
    }

    private fun calculateWeeklySteps(stepsData: Map<String, Int>?): Int {
        if (stepsData == null) return 0

        val calendar = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
        }
        val currentWeek = calendar.get(Calendar.WEEK_OF_YEAR)
        val currentYear = calendar.get(Calendar.YEAR)

        return stepsData.entries
            .filter { (key, _) ->
                try {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(key)
                    val cal = Calendar.getInstance().apply { time = date ?: Date() }
                    cal.firstDayOfWeek = Calendar.MONDAY
                    cal.get(Calendar.WEEK_OF_YEAR) == currentWeek &&
                            cal.get(Calendar.YEAR) == currentYear
                } catch (e: Exception) {
                    false
                }
            }
            .sumOf { it.value }
    }

    private fun calculateMonthlySteps(stepsData: Map<String, Int>?): Int {
        if (stepsData == null) return 0

        val currentMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        return stepsData.entries
            .filter { (key, _) -> key.startsWith(currentMonth) }
            .sumOf { it.value }
    }

    private fun getCurrentDateKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
/*
 * Экран с рейтингом пользователей по количеству шагов.
 * Функционал:
 *  - Отображает топ пользователей за разные периоды (день, неделя, месяц)
 *  - Синхронизирует локальные шаги с Firebase
 *  - Реализует слушатель Firebase для обновления в реальном времени
 *  - Пересчитывает статистику при смене периода
 * 
 * Улучшения:
 *  - Исправлен расчет недели (единообразие с главным экраном)
 *  - Оптимизирована работа с Firebase (корректное удаление слушателя)
 *  - Автоматическое обновление списка при изменении данных
 */