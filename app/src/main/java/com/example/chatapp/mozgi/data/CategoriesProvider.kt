package com.example.chatapp.mozgi.data

import com.example.chatapp.R

object CategoriesProvider {
    val categories = listOf(
        TestCategory(
            id = "general",
            name = "Общий тест",
            description = "50 вопросов из всех областей знаний",
            icon = R.drawable.ic_brain,
            questionCount = 50
        ),
        TestCategory(
            id = "medicine",
            name = "Медицина",
            description = "Анатомия, физиология, заболевания",
            icon = R.drawable.ic_medical,
            questionCount = 40
        ),
        TestCategory(
            id = "dota2",
            name = "Dota 2",
            description = "Герои, предметы, стратегии",
            icon = R.drawable.ic_game,
            questionCount = 45
        ),
        TestCategory(
            id = "history",
            name = "История",
            description = "Мировая и отечественная история",
            icon = R.drawable.ic_history,
            questionCount = 50
        ),
        TestCategory(
            id = "science",
            name = "Наука",
            description = "Физика, химия, биология",
            icon = R.drawable.ic_science,
            questionCount = 45
        )
    )
}