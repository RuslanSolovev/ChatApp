package com.example.chatapp.mozgi.data

object QuizSession {
    var selectedAnswers: MutableMap<Int, Int> = mutableMapOf()
    var totalTime: Long = 0
    var currentCategory: String = "general"
    var questionsForCurrentSession: List<Question> = emptyList() // Добавлено

    fun clear() {
        selectedAnswers.clear()
        totalTime = 0
        questionsForCurrentSession = emptyList()
    }

    fun addAnswer(questionId: Int, answerIndex: Int) {
        selectedAnswers[questionId] = answerIndex
    }

    fun getAnswer(questionId: Int): Int? {
        return selectedAnswers[questionId]
    }

    fun calculateScore(): Pair<Int, Int> {
        var correct = 0
        questionsForCurrentSession.forEach { question ->
            val userAnswer = selectedAnswers[question.id]
            if (userAnswer != null && userAnswer >= 0 && userAnswer == question.correctAnswerIndex) {
                correct++
            }
        }
        return Pair(correct, questionsForCurrentSession.size)
    }

    // Метод для отладки
    fun debugPrintAnswers() {
        println("DEBUG: Ответы в сессии для категории $currentCategory:")
        questionsForCurrentSession.forEach { question ->
            val answer = selectedAnswers[question.id]
            val isCorrect = answer != null && answer >= 0 && answer == question.correctAnswerIndex
            println("  Вопрос ${question.id}: ответ=$answer, правильный=${question.correctAnswerIndex}, верно=$isCorrect")
        }
    }
}