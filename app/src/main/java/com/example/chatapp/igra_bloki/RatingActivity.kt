package com.example.chatapp.activities

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.chatapp.R
import com.example.chatapp.adapters.RatingAdapter
import com.example.chatapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class RatingActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RatingAdapter
    private lateinit var spinnerSort: Spinner
    private lateinit var btnBack: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyStateLayout: LinearLayout
    private lateinit var tvEmptyState: TextView
    private lateinit var tvEmptySubtitle: TextView
    private lateinit var btnPlayNow: Button

    // Статистика
    private lateinit var tvTotalPlayers: TextView
    private lateinit var tvTopPlayer: TextView
    private lateinit var tvYourPosition: TextView

    private val usersList = mutableListOf<User>()
    private var currentSort = "rating"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rating)

        initViews()
        setupRecyclerView()
        setupSortSpinner()
        loadUsersData()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.rvRating)
        spinnerSort = findViewById(R.id.spinnerSort)
        btnBack = findViewById(R.id.btnBack)
        tvTitle = findViewById(R.id.tvTitle)
        progressBar = findViewById(R.id.progressBar)
        emptyStateLayout = findViewById(R.id.emptyStateLayout)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        tvEmptySubtitle = findViewById(R.id.tvEmptySubtitle)
        btnPlayNow = findViewById(R.id.btnPlayNow)

        // Статистика
        tvTotalPlayers = findViewById(R.id.tvTotalPlayers)
        tvTopPlayer = findViewById(R.id.tvTopPlayer)
        tvYourPosition = findViewById(R.id.tvYourPosition)

        btnBack.setOnClickListener {
            finish()
        }

        btnPlayNow.setOnClickListener {
            finish() // Возврат к игре
        }

        tvTitle.text = "🏆 Единый рейтинг"
    }

    private fun setupRecyclerView() {
        adapter = RatingAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupSortSpinner() {
        val sortOptions = arrayOf(
            "По рейтингу 🏆",
            "По победам 🎯",
            "По уровню ⭐",
            "По играм 🎮"
        )

        val sortAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            sortOptions
        )
        sortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSort.adapter = sortAdapter

        spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentSort = when (position) {
                    1 -> "wins"
                    2 -> "level"
                    3 -> "games"
                    else -> "rating"
                }
                sortUsers()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadUsersData() {
        progressBar.visibility = View.VISIBLE
        emptyStateLayout.visibility = View.GONE
        recyclerView.visibility = View.GONE

        val database = Firebase.database.reference
        database.child("users").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                usersList.clear()

                var totalPlayers = 0
                var maxRating = 0
                var currentUserPosition = -1
                val currentUserId = FirebaseAuth.getInstance().currentUser?.uid

                Log.d("RatingActivity", "=== STARTING DATA LOAD ===")
                Log.d("RatingActivity", "Total users in database: ${snapshot.childrenCount}")

                // Проверяем структуру данных
                for (userSnapshot in snapshot.children) {
                    Log.d("RatingActivity", "User ID: ${userSnapshot.key}")
                    try {
                        val user = userSnapshot.getValue(User::class.java)
                        user?.let {
                            // Убеждаемся, что uid установлен
                            if (it.uid.isEmpty()) {
                                it.uid = userSnapshot.key ?: ""
                            }

                            // Убеждаемся, что все числовые поля инициализированы
                            if (it.rating < 0) it.rating = 0
                            if (it.gamesPlayed < 0) it.gamesPlayed = 0
                            if (it.gamesWon < 0) it.gamesWon = 0
                            if (it.bestLevel < 0) it.bestLevel = 0
                            if (it.bestScore < 0) it.bestScore = 0
                            if (it.totalScore < 0) it.totalScore = 0

                            Log.d("RatingActivity", "User: ${it.name ?: "No name"}, " +
                                    "Rating: ${it.rating}, " +
                                    "Games: ${it.gamesPlayed}, " +
                                    "Wins: ${it.gamesWon}, " +
                                    "Best Level: ${it.bestLevel}")

                            // ДОБАВЛЯЕМ ВСЕХ ПОЛЬЗОВАТЕЛЕЙ В СПИСОК
                            // Проверяем, есть ли игровая статистика
                            val hasGameStats = it.gamesPlayed > 0 || it.rating > 0 || it.bestLevel > 0

                            if (hasGameStats) {
                                totalPlayers++
                                usersList.add(it)

                                // Обновляем максимальный рейтинг
                                if (it.rating > maxRating) {
                                    maxRating = it.rating
                                }

                                Log.d("RatingActivity", "✓ Added to active users: ${it.name ?: it.email}")
                            } else {
                                Log.d("RatingActivity", "✗ Skipped (no game stats): ${it.name ?: it.email}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("RatingActivity", "Error parsing user data for ${userSnapshot.key}: ${e.message}")
                        e.printStackTrace()
                    }
                }

                Log.d("RatingActivity", "=== DATA LOAD COMPLETE ===")
                Log.d("RatingActivity", "Total active players: $totalPlayers")
                Log.d("RatingActivity", "Max rating: $maxRating")
                Log.d("RatingActivity", "Users list size: ${usersList.size}")

                if (usersList.isEmpty()) {
                    Log.d("RatingActivity", "No users with game statistics found!")
                    // Показываем всех пользователей для отладки
                    loadAllUsersForDebug(snapshot)
                    return
                }

                // Сортируем и обновляем позиции
                sortUsers()

                // Находим позицию текущего пользователя
                usersList.forEachIndexed { index, user ->
                    if (user.uid == currentUserId) {
                        currentUserPosition = index + 1
                        Log.d("RatingActivity", "Current user found at position: $currentUserPosition")
                    }
                }

                // Обновляем статистику
                updateStatistics(totalPlayers, maxRating, currentUserPosition)

                progressBar.visibility = View.GONE
                updateEmptyState()
            }

            override fun onCancelled(error: DatabaseError) {
                progressBar.visibility = View.GONE
                Log.e("RatingActivity", "Database error: ${error.message} - ${error.details}")
                Toast.makeText(this@RatingActivity, "Ошибка загрузки рейтинга", Toast.LENGTH_SHORT).show()
                updateEmptyState()
            }
        })
    }

    /**
     * Метод для отладки - загружает всех пользователей без фильтрации
     */
    private fun loadAllUsersForDebug(snapshot: DataSnapshot) {
        Log.d("RatingActivity", "=== DEBUG MODE: Loading all users ===")

        var totalPlayers = 0
        var maxRating = 0
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid

        for (userSnapshot in snapshot.children) {
            try {
                val user = userSnapshot.getValue(User::class.java)
                user?.let {
                    if (it.uid.isEmpty()) {
                        it.uid = userSnapshot.key ?: ""
                    }

                    // Сбрасываем значения по умолчанию
                    if (it.rating < 0) it.rating = 0
                    if (it.gamesPlayed < 0) it.gamesPlayed = 0
                    if (it.gamesWon < 0) it.gamesWon = 0
                    if (it.bestLevel < 0) it.bestLevel = 0

                    usersList.add(it)
                    totalPlayers++

                    if (it.rating > maxRating) {
                        maxRating = it.rating
                    }

                    Log.d("RatingActivity", "DEBUG - User: ${it.name ?: "No name"}, " +
                            "Rating: ${it.rating}, " +
                            "Games: ${it.gamesPlayed}")
                }
            } catch (e: Exception) {
                Log.e("RatingActivity", "DEBUG - Error parsing user: ${e.message}")
            }
        }

        Log.d("RatingActivity", "DEBUG - Total users loaded: $totalPlayers")

        if (usersList.isNotEmpty()) {
            sortUsers()
            updateStatistics(totalPlayers, maxRating, -1)
        }

        progressBar.visibility = View.GONE
        updateEmptyState()
    }

    private fun sortUsers() {
        Log.d("RatingActivity", "Sorting users by: $currentSort")

        // Фильтруем пользователей с игровой активностью
        val activeUsers = if (usersList.any { it.gamesPlayed > 0 || it.rating > 0 }) {
            usersList.filter { it.gamesPlayed > 0 || it.rating > 0 || it.bestLevel > 0 }
        } else {
            // Если нет пользователей с игровой статистикой, показываем всех
            usersList
        }

        Log.d("RatingActivity", "Active users for sorting: ${activeUsers.size}")

        val sortedList = when (currentSort) {
            "wins" -> activeUsers.sortedByDescending { it.gamesWon }
            "level" -> activeUsers.sortedByDescending { it.bestLevel }
            "games" -> activeUsers.sortedByDescending { it.gamesPlayed }
            else -> activeUsers.sortedByDescending { it.rating }
        }

        // Обновляем позиции
        sortedList.forEachIndexed { index, user ->
            user.position = index + 1
        }

        Log.d("RatingActivity", "Sorted list size: ${sortedList.size}")

        // Передаем отсортированный список в адаптер
        adapter.submitList(sortedList.toList()) {
            // Колбэк, который вызывается после обновления адаптера
            Log.d("RatingActivity", "Adapter updated with ${adapter.itemCount} items")
            runOnUiThread {
                updateEmptyState()
            }
        }
    }

    private fun updateStatistics(totalPlayers: Int, maxRating: Int, userPosition: Int) {
        runOnUiThread {
            tvTotalPlayers.text = totalPlayers.toString()
            tvTopPlayer.text = maxRating.toString()
            tvYourPosition.text = if (userPosition > 0) "#$userPosition" else "-"

            Log.d("RatingActivity", "Statistics updated: players=$totalPlayers, maxRating=$maxRating, position=$userPosition")
        }
    }

    private fun updateEmptyState() {
        val isEmpty = adapter.itemCount == 0
        Log.d("RatingActivity", "updateEmptyState: adapter.itemCount = ${adapter.itemCount}, isEmpty = $isEmpty")

        runOnUiThread {
            if (isEmpty) {
                emptyStateLayout.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
                tvEmptyState.text = "Рейтинг пуст"
                tvEmptySubtitle.text = "Сыграйте первую игру и займите первое место!"
                Log.d("RatingActivity", "Showing empty state")
            } else {
                emptyStateLayout.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                Log.d("RatingActivity", "Showing rating list with ${adapter.itemCount} users")

                // Показываем тост с информацией о загрузке
                Toast.makeText(this,
                    "Загружено ${adapter.itemCount} игроков",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("RatingActivity", "onResume: Reloading data")
        // Обновляем данные при возвращении на экран
        loadUsersData()
    }

    override fun onPause() {
        super.onPause()
        Log.d("RatingActivity", "onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("RatingActivity", "onDestroy")
        // Безопасная очистка ресурсов
        try {
            Glide.with(this).pauseRequests()
        } catch (e: Exception) {
            Log.e("RatingActivity", "Error pausing Glide: ${e.message}")
        }
    }
}