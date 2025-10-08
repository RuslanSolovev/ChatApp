package com.example.chatapp.mozgi.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.activities.MainActivity
import com.example.chatapp.mozgi.adapter.LeaderboardAdapter
import com.example.chatapp.mozgi.data.UserResult
import com.example.chatapp.mozgi.service.FirebaseService

class LeaderboardActivity : AppCompatActivity() {
    private lateinit var adapter: LeaderboardAdapter
    private val firebaseService = FirebaseService()
    private var categoryId: String = "general"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leaderboard)

        // Получаем категорию из intent
        categoryId = intent.getStringExtra("category_id") ?: "general"

        setupRecyclerView()
        loadLeaderboard()
        setupButtons()
    }

    private fun setupRecyclerView() {
        adapter = LeaderboardAdapter()
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewLeaderboard)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun loadLeaderboard() {
        firebaseService.getLeaderboard(10, categoryId) { results -> // Топ 10 для конкретной категории
            runOnUiThread {
                adapter.submitList(results)
            }
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnMainMenu).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        findViewById<Button>(R.id.btnRestartQuiz).setOnClickListener {
            val intent = Intent(this, QuizActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }
}