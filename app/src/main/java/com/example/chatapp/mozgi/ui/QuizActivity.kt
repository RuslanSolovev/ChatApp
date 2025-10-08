package com.example.chatapp.mozgi.ui

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.example.chatapp.R
import com.example.chatapp.mozgi.data.CategoryQuestionsProvider
import com.example.chatapp.mozgi.data.Question
import com.example.chatapp.mozgi.data.QuizSession

class QuizActivity : AppCompatActivity() {
    private lateinit var questions: List<Question>
    private var currentQuestionIndex = 0
    private lateinit var timer: CountDownTimer
    private var startTime: Long = 0
    private lateinit var btnNext: Button
    private var currentCheckedAnswer: Int = -1
    private var questionStartTime: Long = 0
    private val timePerQuestion: Long = 10000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quiz_mozgi)

        // Получаем вопросы для текущей категории
        questions = CategoryQuestionsProvider.getQuestionsByCategory(
            QuizSession.currentCategory,
            getCategoryQuestionCount(QuizSession.currentCategory)
        )

        // Сохраняем вопросы в сессии для правильного подсчета
        QuizSession.questionsForCurrentSession = questions

        startTime = System.currentTimeMillis()
        questionStartTime = startTime

        btnNext = findViewById(R.id.btnNext)
        currentCheckedAnswer = -1

        if (questions.isNotEmpty()) {
            displayQuestion(questions[currentQuestionIndex], currentQuestionIndex + 1)
            startTimer()
        } else {
            Toast.makeText(this, "Нет вопросов для этой категории", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupViews()
    }

    private fun setupViews() {
        btnNext.setOnClickListener {
            saveCurrentAnswer()
            stopTimer()
            moveToNextQuestion()
        }
    }

    private fun displayQuestion(question: Question, questionNumber: Int) {
        findViewById<TextView>(R.id.subjectText).text = "${question.subject} (${questionNumber}/${questions.size})"
        findViewById<TextView>(R.id.questionText).text = question.questionText

        val radioGroup = findViewById<RadioGroup>(R.id.optionsGroup)
        radioGroup.removeAllViews()

        // Создаем радиокнопки
        question.options.forEachIndexed { index, option ->
            val radioButton = RadioButton(this)
            radioButton.text = option
            radioButton.id = index
            radioGroup.addView(radioButton)
        }

        // Устанавливаем обработчик изменений
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            currentCheckedAnswer = checkedId
        }

        // Восстановить предыдущий выбор если есть
        val savedAnswer = QuizSession.getAnswer(question.id)
        if (savedAnswer != null && savedAnswer >= 0) {
            radioGroup.check(savedAnswer)
            currentCheckedAnswer = savedAnswer
        } else {
            radioGroup.clearCheck()
            currentCheckedAnswer = -1
        }

        // Обновляем текст кнопки
        if (currentQuestionIndex == questions.size - 1) {
            btnNext.text = "Завершить"
        } else {
            btnNext.text = "Далее"
        }
    }

    private fun startTimer() {
        timer = object : CountDownTimer(timePerQuestion, 10) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                val millis = millisUntilFinished % 1000 / 10
                findViewById<TextView>(R.id.timerText).text = String.format("00:%02d.%02d", seconds, millis)
            }

            override fun onFinish() {
                // Время вышло - сохраняем ответ и переходим к следующему вопросу
                saveCurrentAnswer()
                moveToNextQuestion()
            }
        }
        timer.start()
    }

    private fun stopTimer() {
        try {
            timer.cancel()
        } catch (e: Exception) {
            // Игнорируем ошибки
        }
    }

    private fun saveCurrentAnswer() {
        if (questions.isNotEmpty() && currentQuestionIndex < questions.size) {
            val currentQuestion = questions[currentQuestionIndex]
            val answerToSave = if (currentCheckedAnswer >= 0) currentCheckedAnswer else -1
            QuizSession.addAnswer(currentQuestion.id, answerToSave)
        }
    }

    private fun moveToNextQuestion() {
        if (currentQuestionIndex < questions.size - 1) {
            currentQuestionIndex++
            currentCheckedAnswer = -1
            displayQuestion(questions[currentQuestionIndex], currentQuestionIndex + 1)
            startTimer()
        } else {
            finishQuiz()
        }
    }

    private fun finishQuiz() {
        saveCurrentAnswer() // Сохраняем последний ответ
        QuizSession.totalTime = System.currentTimeMillis() - startTime

        val intent = Intent(this, ResultActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun getCategoryQuestionCount(categoryId: String): Int {
        return com.example.chatapp.mozgi.data.CategoriesProvider.categories
            .find { it.id == categoryId }?.questionCount ?: 50
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
    }

    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setTitle("Завершить тест?")
            .setMessage("Ваш прогресс будет потерян")
            .setPositiveButton("Да") { _, _ ->
                stopTimer()
                super.onBackPressed()
            }
            .setNegativeButton("Нет", null)
            .show()
    }
}