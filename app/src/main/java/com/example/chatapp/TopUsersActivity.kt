package com.example.chatapp

import android.content.*
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.chatapp.adapters.TopUsersAdapter
import com.example.chatapp.databinding.ActivityTopUsersBinding
import com.example.chatapp.models.User
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class TopUsersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTopUsersBinding
    private lateinit var topUsersAdapter: TopUsersAdapter
    private val database = FirebaseDatabase.getInstance().reference
    private var currentPeriod = PERIOD_DAY
    private val auth = FirebaseAuth.getInstance()
    private val usersList = ArrayList<User>()
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

        setupToolbar()
        setupRecyclerView()
        setupPeriodTabs()
        initCurrentUserCard()
        syncLocalStepsWithFirebase()
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

    private fun setupPeriodTabs() {
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

    private fun initCurrentUserCard() {
        // Инициализация карточки текущего пользователя через View Binding
        with(binding) {
            tvUserPosition.text = "-"
            tvUserName.text = "Вы"
            tvUserSteps.text = "Нет данных"
            ivUserAvatar.setImageResource(R.drawable.ic_default_profile)
        }
    }

    private fun setupFirebaseListener() {
        removeFirebaseListener()
        valueEventListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                usersList.clear()
                for (userSnapshot in snapshot.children) {
                    userSnapshot.getValue(User::class.java)?.let { user ->
                        user.totalSteps = calculateStepsForPeriod(user, currentPeriod)
                        usersList.add(user)
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

    private fun updateUsersList() {
        val sortedUsers = usersList.sortedByDescending { it.totalSteps }
        topUsersAdapter.updatePeriod(currentPeriod)
        topUsersAdapter.submitList(sortedUsers)
        updateCurrentUserCard(sortedUsers)
    }

    private fun updateCurrentUserCard(sortedUsers: List<User>) {
        val currentUser = auth.currentUser ?: return
        val currentUserId = currentUser.uid
        val currentUserData = usersList.find { it.uid == currentUserId }

        if (currentUserData != null) {
            val position = sortedUsers.indexOfFirst { it.uid == currentUserId } + 1
            with(binding) {
                tvUserPosition.text = position.toString()
                tvUserName.text = currentUserData.name ?: "Без имени"
                tvUserSteps.text = when (currentPeriod) {
                    PERIOD_DAY -> "${currentUserData.totalSteps} шагов сегодня"
                    PERIOD_WEEK -> "${currentUserData.totalSteps} шагов за неделю"
                    PERIOD_MONTH -> "${currentUserData.totalSteps} шагов за месяц"
                    else -> "${currentUserData.totalSteps} шагов"
                }
                Glide.with(this@TopUsersActivity)
                    .load(currentUserData.profileImageUrl)
                    .circleCrop()
                    .placeholder(R.drawable.ic_default_profile)
                    .into(ivUserAvatar)
            }
        } else {
            with(binding) {
                tvUserPosition.text = "-"
                tvUserName.text = currentUser.displayName ?: "Вы"
                tvUserSteps.text = "Нет данных"
                ivUserAvatar.setImageResource(R.drawable.ic_default_profile)
            }
        }
    }

    private fun syncLocalStepsWithFirebase() {
        val currentUser = auth.currentUser ?: return
        val userId = currentUser.uid
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val todaySteps = getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
            .getInt(todayKey, 0)

        database.child("users").child(userId).child("stepsData").child(todayKey)
            .setValue(todaySteps)
            .addOnFailureListener {
                Toast.makeText(this, "Ошибка синхронизации: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun calculateStepsForPeriod(user: User, period: Int): Int {
        return when (period) {
            PERIOD_DAY -> getStepsForDate(user, getCurrentDateKey())
            PERIOD_WEEK -> calculateWeeklySteps(user)
            PERIOD_MONTH -> calculateMonthlySteps(user)
            else -> 0
        }
    }

    private fun getStepsForDate(user: User, dateKey: String): Int {
        return user.stepsData?.get(dateKey)?.let { convertToSteps(it) } ?: 0
    }

    private fun calculateWeeklySteps(user: User): Int {
        val stepsData = user.stepsData ?: return 0
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
            .sumOf { (_, value) -> convertToSteps(value) }
    }

    private fun calculateMonthlySteps(user: User): Int {
        val stepsData = user.stepsData ?: return 0
        val currentMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())

        return stepsData.entries
            .filter { (key, _) -> key.startsWith(currentMonth) }
            .sumOf { (_, value) -> convertToSteps(value) }
    }

    private fun convertToSteps(value: Any?): Int {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Map<*, *> -> (value["steps"] as? Int) ?: 0
            else -> 0
        }
    }

    private fun getCurrentDateKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    override fun onResume() {
        super.onResume()
        setupFirebaseListener()
    }

    override fun onPause() {
        super.onPause()
        removeFirebaseListener()
    }

    private fun removeFirebaseListener() {
        valueEventListener?.let {
            database.child("users").removeEventListener(it)
            valueEventListener = null
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}