package com.example.chatapp

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.chatapp.adapters.TopUsersAdapter
import com.example.chatapp.databinding.ActivityTopUsersBinding
import com.example.chatapp.models.User
import com.example.chatapp.viewmodels.StepCounterViewModel
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class TopUsersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTopUsersBinding
    private lateinit var adapter: TopUsersAdapter
    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()
    private var currentPeriod = PERIOD_DAY
    private val usersList = mutableListOf<User>()
    private var valueEventListener: ValueEventListener? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val weeklyCache = mutableMapOf<String, Int>()
    private val monthlyCache = mutableMapOf<String, Int>()

    companion object {
        const val PERIOD_DAY = 0
        const val PERIOD_WEEK = 1
        const val PERIOD_MONTH = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTopUsersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupTabs()
        setupCurrentUserCard()
        syncLocalSteps()
        loadUsersData()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Top Users"
    }

    private fun setupRecyclerView() {
        adapter = TopUsersAdapter()
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@TopUsersActivity)
            adapter = this@TopUsersActivity.adapter
        }
    }

    private fun setupTabs() {
        binding.periodTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentPeriod = when (tab?.position) {
                    0 -> PERIOD_DAY
                    1 -> PERIOD_WEEK
                    2 -> PERIOD_MONTH
                    else -> PERIOD_DAY
                }
                updateUsersList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupCurrentUserCard() {
        with(binding) {
            tvUserPosition.text = "-"
            tvUserName.text = "You"
            tvUserSteps.text = "No data"
            ivUserAvatar.setImageResource(R.drawable.ic_default_profile)
        }
    }

    private fun loadUsersData() {
        removeListener()
        valueEventListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                usersList.clear()
                for (userSnapshot in snapshot.children) {
                    try {
                        val user = User(
                            uid = userSnapshot.key ?: "",
                            name = userSnapshot.child("name").getValue(String::class.java) ?: "",
                            profileImageUrl = userSnapshot.child("profileImageUrl").getValue(String::class.java),
                            stepsData = userSnapshot.child("stepsData").getValue(object :
                                GenericTypeIndicator<Map<String, Any>>() {}) ?: emptyMap()
                        )
                        usersList.add(user)
                    } catch (e: Exception) {
                        Log.e("TopUsersActivity", "Error parsing user data", e)
                    }
                }
                updateUsersList()
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(
                    this@TopUsersActivity,
                    "Load error: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        database.child("users").addValueEventListener(valueEventListener!!)
    }

    private fun updateUsersList() {
        executor.execute {
            val sortedUsers = usersList.map { user ->
                user.totalSteps = calculateSteps(user, currentPeriod)
                user
            }.filter { it.totalSteps > 0 }
                .sortedByDescending { it.totalSteps }
                .mapIndexed { index, user ->
                    user.position = index + 1
                    user
                }

            runOnUiThread {
                adapter.submitList(sortedUsers, currentPeriod)
                updateCurrentUserCard(sortedUsers)
            }
        }
    }

    private fun updateCurrentUserCard(sortedUsers: List<User>) {
        val currentUser = auth.currentUser ?: return
        val currentUserData = sortedUsers.find { it.uid == currentUser.uid }

        with(binding) {
            if (currentUserData != null) {
                tvUserPosition.text = currentUserData.position.toString()
                tvUserName.text = currentUserData.name
                tvUserSteps.text = when (currentPeriod) {
                    PERIOD_DAY -> "${currentUserData.totalSteps} steps today"
                    PERIOD_WEEK -> "${currentUserData.totalSteps} steps this week"
                    PERIOD_MONTH -> "${currentUserData.totalSteps} steps this month"
                    else -> "${currentUserData.totalSteps} steps"
                }
                currentUserData.profileImageUrl?.let { url ->
                    Glide.with(this@TopUsersActivity)
                        .load(url)
                        .circleCrop()
                        .placeholder(R.drawable.ic_default_profile)
                        .into(ivUserAvatar)
                }
            } else {
                tvUserPosition.text = "-"
                tvUserName.text = currentUser.displayName ?: "You"
                tvUserSteps.text = "No data"
                ivUserAvatar.setImageResource(R.drawable.ic_default_profile)
            }
        }
    }

    private fun syncLocalSteps() {
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val viewModel = ViewModelProvider(this).get(StepCounterViewModel::class.java)
        val currentUser = auth.currentUser ?: return // Добавляем проверку на null

        viewModel.todaySteps.value?.let { steps ->
            database.child("users").child(currentUser.uid).child("stepsData").child(todayKey)
                .setValue(steps)
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Sync error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun calculateSteps(user: User, period: Int): Int {
        return when (period) {
            PERIOD_DAY -> getDailySteps(user)
            PERIOD_WEEK -> getWeeklySteps(user)
            PERIOD_MONTH -> getMonthlySteps(user)
            else -> 0
        }
    }

    private fun getDailySteps(user: User): Int {
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return when (val steps = user.stepsData[todayKey]) {
            is Int -> steps
            is Long -> steps.toInt()
            else -> 0
        }
    }

    private fun getWeeklySteps(user: User): Int {
        val cacheKey = "${user.uid}_week"
        weeklyCache[cacheKey]?.let { return it }

        val calendar = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val weekDays = (0 until 7).map {
            calendar.apply { if (it > 0) add(Calendar.DATE, 1) }
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
        }

        val steps = user.stepsData.entries
            .filter { (date, _) -> weekDays.contains(date) }
            .sumBy { (_, steps) -> steps.toString().toIntOrNull() ?: 0 }

        weeklyCache[cacheKey] = steps
        return steps
    }

    private fun getMonthlySteps(user: User): Int {
        val cacheKey = "${user.uid}_month_${SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())}"
        monthlyCache[cacheKey]?.let { return it }

        val calendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val monthPrefix = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(calendar.time)
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        var totalSteps = 0

        // Генерируем все возможные даты месяца
        val monthDays = (1..daysInMonth).map { day ->
            calendar.set(Calendar.DAY_OF_MONTH, day)
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
        }

        // Суммируем шаги за все дни месяца
        totalSteps = user.stepsData.entries
            .filter { (date, _) -> date.startsWith(monthPrefix) && monthDays.contains(date) }
            .sumBy { (_, steps) ->
                when (steps) {
                    is Int -> steps
                    is Long -> steps.toInt()
                    else -> 0
                }
            }

        monthlyCache[cacheKey] = totalSteps
        return totalSteps
    }

    private fun removeListener() {
        valueEventListener?.let {
            database.child("users").removeEventListener(it)
            valueEventListener = null
        }
    }

    override fun onResume() {
        super.onResume()
        loadUsersData()
    }

    override fun onPause() {
        super.onPause()
        removeListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}