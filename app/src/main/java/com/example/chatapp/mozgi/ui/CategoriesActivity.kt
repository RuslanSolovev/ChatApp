package com.example.chatapp.mozgi.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.mozgi.adapter.CategoriesAdapter
import com.example.chatapp.mozgi.data.CategoriesProvider

class CategoriesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_categories)

        setupViews()
        setupRecyclerView()
    }

    private fun setupViews() {
        val btnBack = findViewById<Button>(R.id.btnBackToMain)
        btnBack.setOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewCategories)
        val adapter = CategoriesAdapter { category ->
            startCategoryQuiz(category)
        }

        recyclerView.layoutManager = GridLayoutManager(this, 2)
        recyclerView.adapter = adapter

        adapter.submitList(CategoriesProvider.categories)
    }

    private fun startCategoryQuiz(category: com.example.chatapp.mozgi.data.TestCategory) {
        val intent = Intent(this, StartQuizActivity::class.java)
        intent.putExtra("category_id", category.id)
        intent.putExtra("category_name", category.name)
        startActivity(intent)
    }
}