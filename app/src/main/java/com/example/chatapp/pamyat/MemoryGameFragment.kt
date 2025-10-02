package com.example.chatapp.pamyat

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.pow
import kotlin.math.sqrt

class MemoryGameFragment : Fragment() {

    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference

    // UI элементы меню
    private lateinit var menuContainer: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var btnDifficulty: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnStart: Button

    // UI элементы игры
    private lateinit var gameContainer: LinearLayout
    private lateinit var timerContainer: CardView
    private lateinit var readyContainer: LinearLayout
    private lateinit var btnReady: Button
    private lateinit var tvReadyText: TextView
    private lateinit var gameBoard: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTimer: TextView

    // Данные игры
    private var currentLevel = 1
    private var currentScore = 0
    private var selectedDifficulty = 2 // Начальный уровень сложности
    private var maxNumbers = 2 // Текущее количество чисел
    private var playerSequence = mutableListOf<Int>()
    private var isShowingSequence = false
    private val handler = Handler(Looper.getMainLooper())
    private var circleViews = mutableMapOf<Int, Button>() // Ключ - число, значение - кнопка
    private var timeLeft = 10 // Таймер в секундах
    private var timerRunnable: Runnable? = null

    // Адаптер для топа игроков
    private lateinit var leaderboardAdapter: MemoryLeaderboardAdapter
    private val leaderboardUsers = mutableListOf<MemoryLeaderboardAdapter.UserWithScore>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_memory_game, container, false)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().reference

        initViews(view)
        setupClickListeners()
        loadLeaderboard()

        return view
    }

    private fun initViews(view: View) {
        // Меню элементы
        menuContainer = view.findViewById(R.id.menuContainer)
        tvTitle = view.findViewById(R.id.tvGameTitle)
        btnDifficulty = view.findViewById(R.id.btnDifficulty)
        recyclerView = view.findViewById(R.id.recyclerViewLeaderboard)
        btnStart = view.findViewById(R.id.btnStartGame)

        // Игровые элементы
        gameContainer = view.findViewById(R.id.gameContainer)
        timerContainer = view.findViewById(R.id.timerContainer)
        readyContainer = view.findViewById(R.id.readyContainer)
        btnReady = view.findViewById(R.id.btnReady)
        tvReadyText = view.findViewById(R.id.tvReadyText)
        gameBoard = view.findViewById(R.id.gameBoard)
        progressBar = view.findViewById(R.id.progressBar)
        tvTimer = view.findViewById(R.id.tvTimer)

        // Настройка RecyclerView
        leaderboardAdapter = MemoryLeaderboardAdapter(leaderboardUsers)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = leaderboardAdapter

        // Установка начального текста сложности
        updateDifficultyText()

        // Таймер изначально виден
        timerContainer.visibility = View.VISIBLE
        progressBar.max = 100
        progressBar.progress = 100
        tvTimer.text = "10"
    }

    private fun setupClickListeners() {
        btnDifficulty.setOnClickListener {
            showDifficultyMenu()
        }

        btnStart.setOnClickListener {
            startGame()
        }

        btnReady.setOnClickListener {
            showSequence()
        }
    }

    private fun showDifficultyMenu() {
        val popup = PopupMenu(context, btnDifficulty)
        popup.menu.add(0, 2, 0, "Очень легко (2 числа)")
        popup.menu.add(0, 4, 1, "Легко (4 числа)")
        popup.menu.add(0, 8, 2, "Средне (8 чисел)")
        popup.menu.add(0, 12, 3, "Сложно (12 чисел)")
        popup.menu.add(0, 15, 4, "Эксперт (15 чисел)")

        popup.setOnMenuItemClickListener { item ->
            selectedDifficulty = item.itemId
            updateDifficultyText()
            true
        }
        popup.show()
    }

    private fun updateDifficultyText() {
        val difficultyText = when (selectedDifficulty) {
            2 -> "Очень легко (2 числа)"
            4 -> "Легко (4 числа)"
            8 -> "Средне (8 чисел)"
            12 -> "Сложно (12 чисел)"
            15 -> "Эксперт (15 чисел)"
            else -> "Средне (8 чисел)"
        }
        btnDifficulty.text = difficultyText
    }

    private fun startGame() {
        currentLevel = 1
        currentScore = 0
        maxNumbers = selectedDifficulty // Начинаем с выбранного уровня сложности
        playerSequence.clear()
        circleViews.clear()

        // Показываем игровой экран
        menuContainer.visibility = View.GONE
        gameContainer.visibility = View.VISIBLE
        readyContainer.visibility = View.VISIBLE
        gameBoard.visibility = View.GONE
        // Таймер остается видимым

        updateReadyText()
        btnReady.text = "Готов"
    }

    private fun updateReadyText() {
        tvReadyText.text = "Уровень $currentLevel\nЗапомни расположение чисел!\nНажимай по возрастанию: ${getListString()}"
    }

    private fun getListString(): String {
        return (1..maxNumbers).joinToString(", ")
    }

    private fun showSequence() {
        playerSequence.clear()
        circleViews.clear()
        isShowingSequence = true

        readyContainer.visibility = View.GONE
        gameBoard.visibility = View.VISIBLE
        // Таймер остается видимым

        // Очищаем игровое поле
        gameBoard.removeAllViews()

        // Создаем круги с задержкой для правильного размера
        gameBoard.post {
            createCircles()

            // Показываем числа на 3 секунды
            handler.postDelayed({
                if (isAdded) {
                    hideSequence()
                }
            }, 3000)
        }
    }

    private fun createCircles() {
        gameBoard.removeAllViews()
        circleViews.clear()

        val circleSize = 80.dpToPx()
        val minDistance = circleSize + 20 // Минимальное расстояние между центрами кругов

        // Создаем список позиций для кругов
        val positions = mutableListOf<Pair<Int, Int>>()
        val maxAttempts = 100 // Максимальное количество попыток размещения

        // Создаем круги с числами от 1 до maxNumbers
        for (number in 1..maxNumbers) {
            var positionFound = false
            var attempts = 0

            while (!positionFound && attempts < maxAttempts) {
                // Генерируем случайные координаты
                val maxX = (gameBoard.width - circleSize - 20).coerceAtLeast(20)
                val maxY = (gameBoard.height - circleSize - 20).coerceAtLeast(20)

                if (maxX > 20 && maxY > 20) {
                    val x = (20..maxX).random()
                    val y = (20..maxY).random()

                    // Проверяем, что новая позиция не слишком близка к существующим
                    var isValidPosition = true
                    for ((existingX, existingY) in positions) {
                        val distance = sqrt(((x - existingX).toDouble().pow(2) + (y - existingY).toDouble().pow(2)).toDouble())
                        if (distance < minDistance) {
                            isValidPosition = false
                            break
                        }
                    }

                    if (isValidPosition || positions.isEmpty()) {
                        positions.add(Pair(x, y))
                        positionFound = true

                        val circle = createCircleView(number)
                        val params = FrameLayout.LayoutParams(circleSize, circleSize)
                        params.leftMargin = x
                        params.topMargin = y

                        circle.tag = number // Сохраняем значение числа
                        circle.isEnabled = false // Делаем неактивными во время показа
                        circleViews[number] = circle
                        gameBoard.addView(circle, params)
                    }
                } else {
                    // Если игровое поле слишком маленькое, просто размещаем круги
                    val x = 20 + (number * 100) % (gameBoard.width - circleSize - 40)
                    val y = 20 + (number * 80) % (gameBoard.height - circleSize - 40)
                    positions.add(Pair(x, y))
                    positionFound = true

                    val circle = createCircleView(number)
                    val params = FrameLayout.LayoutParams(circleSize, circleSize)
                    params.leftMargin = x
                    params.topMargin = y

                    circle.tag = number // Сохраняем значение числа
                    circle.isEnabled = false // Делаем неактивными во время показа
                    circleViews[number] = circle
                    gameBoard.addView(circle, params)
                }

                attempts++
            }

            // Если не удалось найти хорошую позицию, используем любую
            if (!positionFound && positions.isNotEmpty()) {
                val lastPos = positions.last()
                val x = (lastPos.first + minDistance).coerceAtMost(gameBoard.width - circleSize - 20)
                val y = lastPos.second
                positions.add(Pair(x, y))

                val circle = createCircleView(number)
                val params = FrameLayout.LayoutParams(circleSize, circleSize)
                params.leftMargin = x
                params.topMargin = y

                circle.tag = number // Сохраняем значение числа
                circle.isEnabled = false // Делаем неактивными во время показа
                circleViews[number] = circle
                gameBoard.addView(circle, params)
            }
        }
    }

    private fun createCircleView(number: Int): Button {
        val circle = Button(context)
        circle.text = number.toString()
        circle.setTextColor(Color.WHITE)
        circle.setBackgroundColor(Color.parseColor("#2196F3"))
        circle.textSize = 18f // Меньший размер текста
        circle.setBackgroundResource(R.drawable.circle_background_small) // Меньший круг

        return circle
    }

    private fun hideSequence() {
        // Скрываем числа в кругах (делаем их прозрачными)
        circleViews.values.forEach { circle ->
            circle.text = ""
            circle.setBackgroundColor(Color.TRANSPARENT)
            circle.setBackgroundResource(R.drawable.circle_background_small) // Сохраняем форму круга
        }

        // Закончили показ последовательности
        isShowingSequence = false
        makeCirclesClickable()

        // Таймер уже виден, просто запускаем его
        startTimer()
    }

    private fun startTimer() {
        timeLeft = 10
        progressBar.progress = 100
        tvTimer.text = "10"

        timerRunnable = object : Runnable {
            override fun run() {
                timeLeft--
                if (timeLeft >= 0) {
                    val progress = (timeLeft * 10)
                    progressBar.progress = progress
                    tvTimer.text = timeLeft.toString()
                    handler.postDelayed(this, 1000)
                } else {
                    // Время вышло
                    gameOver()
                }
            }
        }
        handler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let {
            handler.removeCallbacks(it)
            timerRunnable = null
        }
        // Таймер остается видимым, просто сбрасываем его
        progressBar.progress = 100
        tvTimer.text = "10"
    }

    private fun makeCirclesClickable() {
        circleViews.values.forEach { circle ->
            circle.isEnabled = true
            circle.setOnClickListener {
                if (!isShowingSequence) {
                    val number = circle.tag as Int
                    handleCircleClick(number)
                }
            }
        }
    }

    private fun handleCircleClick(number: Int) {
        playerSequence.add(number)

        // Открываем цифру в круге (восстанавливаем цвет и текст)
        circleViews[number]?.let { circle ->
            circle.text = number.toString()
            circle.setBackgroundColor(Color.parseColor("#2196F3"))
            circle.setBackgroundResource(R.drawable.circle_background_small)

            // Делаем этот круг неактивным, чтобы нельзя было нажать повторно
            circle.isEnabled = false
        }

        // Проверяем правильность - игрок должен нажимать по возрастанию
        if (playerSequence[playerSequence.size - 1] != playerSequence.size) {
            // Ошибка - неправильный порядок
            stopTimer()
            gameOver()
            return
        }

        if (playerSequence.size == maxNumbers) {
            // Уровень пройден
            stopTimer()
            handler.postDelayed({
                if (isAdded) {
                    levelComplete()
                }
            }, 1000) // Немного подождем, чтобы игрок увидел последнюю цифру
        }
    }

    private fun levelComplete() {
        // Система подсчета баллов:
        // Базовые баллы: количество чисел * 10
        // Бонус за сложность: начальный уровень сложности * 5
        // Бонус за время: оставшееся время * 5
        val baseScore = maxNumbers * 10
        val difficultyBonus = selectedDifficulty * 5
        val timeBonus = timeLeft * 5
        val levelScore = baseScore + difficultyBonus + timeBonus

        currentScore += levelScore
        currentLevel++

        // Увеличиваем количество чисел, но не больше 20
        if (maxNumbers < 20) {
            maxNumbers++
        }
        // Если достигли максимальной сложности (20), продолжаем с тем же количеством чисел

        // Показываем диалог продолжения
        showLevelCompleteDialog(levelScore, timeBonus)
    }

    private fun showLevelCompleteDialog(levelScore: Int, timeBonus: Int) {
        if (!isAdded) return

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_game_continue, null)
        val dialog = android.app.AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val tvScore = dialogView.findViewById<TextView>(R.id.tvCurrentScore)
        val btnNextLevel = dialogView.findViewById<Button>(R.id.btnNextLevel)
        val btnExitToMenu = dialogView.findViewById<Button>(R.id.btnExitToMenu)

        tvScore.text = "Счет: $currentScore\nУровень $currentLevel пройден!\n+ $levelScore баллов\n(Бонус времени: $timeBonus)"

        btnNextLevel.setOnClickListener {
            dialog.dismiss()
            startNextLevel()
        }

        btnExitToMenu.setOnClickListener {
            dialog.dismiss()
            exitToMainMenu()
        }

        dialog.show()
    }

    private fun gameOver() {
        stopTimer()
        saveScore()
        showGameOverDialog()
    }

    private fun showGameOverDialog() {
        if (!isAdded) return

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_game_over, null)
        val dialog = android.app.AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val tvFinalScore = dialogView.findViewById<TextView>(R.id.tvFinalScore)
        val btnRestart = dialogView.findViewById<Button>(R.id.btnRestart)
        val btnExitToGameMenu = dialogView.findViewById<Button>(R.id.btnExitToGameMenu)
        val btnExitToMain = dialogView.findViewById<Button>(R.id.btnExitToMain)

        tvFinalScore.text = "Игра окончена!\nВаш счет: $currentScore"

        btnRestart.setOnClickListener {
            dialog.dismiss()
            startGame()
        }

        btnExitToGameMenu.setOnClickListener {
            dialog.dismiss()
            exitToGameMenu()
        }

        btnExitToMain.setOnClickListener {
            dialog.dismiss()
            exitToMainMenu()
        }

        dialog.show()
    }

    private fun startNextLevel() {
        playerSequence.clear()
        circleViews.clear()
        readyContainer.visibility = View.VISIBLE
        gameBoard.visibility = View.GONE
        // Таймер остается видимым
        updateReadyText()
        btnReady.text = "Готов"
        // Сброс таймера
        progressBar.progress = 100
        tvTimer.text = "10"
    }

    private fun saveScore() {
        val userId = auth.currentUser?.uid ?: return
        val scoreRef = database.child("memory_game_scores").child(userId)

        // Получаем текущий лучший счет
        scoreRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val currentBest = snapshot.getValue(Int::class.java) ?: 0
                if (currentScore > currentBest) {
                    scoreRef.setValue(currentScore)
                    loadLeaderboard() // Обновляем таблицу
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun loadLeaderboard() {
        database.child("memory_game_scores")
            .orderByValue()
            .limitToLast(10)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    leaderboardUsers.clear()
                    val userIds = mutableListOf<String>()
                    val scores = mutableMapOf<String, Int>()

                    for (child in snapshot.children) {
                        val userId = child.key ?: continue
                        val score = child.getValue(Int::class.java) ?: 0
                        userIds.add(userId)
                        scores[userId] = score
                    }

                    // Получаем данные пользователей
                    if (userIds.isNotEmpty()) {
                        userIds.forEach { userId ->
                            database.child("users").child(userId)
                                .addListenerForSingleValueEvent(object : ValueEventListener {
                                    override fun onDataChange(userSnapshot: DataSnapshot) {
                                        if (isAdded) { // Проверяем, что фрагмент еще активен
                                            val user = userSnapshot.getValue(User::class.java)
                                            if (user != null) {
                                                leaderboardUsers.add(MemoryLeaderboardAdapter.UserWithScore(user, scores[userId] ?: 0))
                                                leaderboardUsers.sortByDescending { it.score }
                                                leaderboardAdapter.notifyDataSetChanged()
                                            }
                                        }
                                    }

                                    override fun onCancelled(error: DatabaseError) {}
                                })
                        }
                    } else {
                        leaderboardAdapter.notifyDataSetChanged()
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun exitToGameMenu() {
        stopTimer()
        gameContainer.visibility = View.GONE
        menuContainer.visibility = View.VISIBLE
    }

    private fun exitToMainMenu() {
        stopTimer()
        // Закрываем текущую Activity вместо возврата в предыдущий фрагмент
        activity?.finish()
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    // Получаем цвет фона кнопки
    private val Button.backgroundColor: Int
        get() {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                backgroundTintList?.defaultColor ?: Color.parseColor("#2196F3")
            } else {
                Color.parseColor("#2196F3")
            }
        }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        handler.removeCallbacksAndMessages(null) // Очищаем все отложенные задачи
    }
}

// Расширение для возведения в степень
private fun Int.pow(exponent: Int): Int {
    return Math.pow(this.toDouble(), exponent.toDouble()).toInt()
}