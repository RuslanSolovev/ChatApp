package com.example.chatapp.mozgi.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.mozgi.adapter.LeaderboardAdapter
import com.example.chatapp.mozgi.data.CategoriesProvider
import com.example.chatapp.mozgi.data.CategoryQuestionsProvider
import com.example.chatapp.mozgi.data.QuizSession
import com.example.chatapp.mozgi.data.TestCategory
import com.example.chatapp.mozgi.data.UserResult
import com.example.chatapp.mozgi.service.FirebaseService

class StartQuizActivity : AppCompatActivity() {
    private lateinit var adapter: LeaderboardAdapter
    private val firebaseService = FirebaseService()
    private lateinit var progressBar: ProgressBar
    private lateinit var category: TestCategory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_quiz)

        // Получаем категорию из intent
        val categoryId = intent.getStringExtra("category_id") ?: "general"

        // Находим категорию по ID
        category = CategoriesProvider.categories.find { it.id == categoryId }
            ?: CategoriesProvider.categories.first()

        // Очищаем сессию перед началом
        QuizSession.clear()
        QuizSession.currentCategory = category.id

        setupViews()
        loadLeaderboard()
    }

    private fun setupViews() {
        progressBar = findViewById(R.id.progressBar)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewTopPlayers)
        val btnStartQuiz = findViewById<Button>(R.id.btnStartQuiz)
        val btnBack = findViewById<Button>(R.id.btnBackToMain)
        val tvCategoryName = findViewById<TextView>(R.id.tvCategoryName)
        val tvCategoryDescription = findViewById<TextView>(R.id.tvCategoryDescription)

        // Устанавливаем информацию о категории
        tvCategoryName.text = category.name
        tvCategoryDescription.text = "${category.questionCount} вопросов • ${category.timePerQuestion} сек на вопрос"

        // Настройка RecyclerView
        adapter = LeaderboardAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Кнопка начала теста
        btnStartQuiz.setOnClickListener {
            startQuiz()
        }

        // Кнопка возврата
        btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadLeaderboard() {
        progressBar.visibility = ProgressBar.VISIBLE

        // Загружаем лидеров для конкретной категории
        firebaseService.getLeaderboard(10, category.id) { results ->
            runOnUiThread {
                progressBar.visibility = ProgressBar.GONE
                if (results.isNotEmpty()) {
                    adapter.submitList(results)
                } else {
                    Toast.makeText(this, "Пока нет результатов в этой категории", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startQuiz() {
        // Проверяем, есть ли вопросы для этой категории
        val questions = CategoryQuestionsProvider.getQuestionsByCategory(
            category.id,
            category.questionCount
        )

        if (questions.isEmpty()) {
            Toast.makeText(this, "Нет вопросов для этой категории", Toast.LENGTH_SHORT).show()
            return
        }

        // Устанавливаем категорию в сессию
        QuizSession.currentCategory = category.id
        QuizSession.clear() // Очищаем перед началом нового теста

        val intent = Intent(this, QuizActivity::class.java)
        startActivity(intent)
        finish()
    }
}