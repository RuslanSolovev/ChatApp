package com.example.chatapp.igra_bloki

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.R
import com.example.chatapp.activities.RatingActivity
import com.example.chatapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlin.math.*
import kotlin.random.Random

class BlockGameActivity : AppCompatActivity() {

    private lateinit var gameView: BlockGameView
    private lateinit var tvScore: TextView
    private lateinit var tvLives: TextView
    private lateinit var tvLevel: TextView
    private lateinit var tvDifficulty: TextView
    private lateinit var btnPause: Button
    private lateinit var messageLayout: LinearLayout
    private lateinit var tvMessageTitle: TextView
    private lateinit var tvMessageSubtitle: TextView
    private lateinit var btnRestart: Button
    private lateinit var btnResume: Button
    private lateinit var btnMenuRating: Button
    private lateinit var btnMenuDifficulty: Button
    private lateinit var btnMenuExit: Button

    private var currentDifficulty = "medium"
    private var isMenuVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        setContentView(R.layout.activity_block_game)

        initViews()
        setupClickListeners()
        updateDifficultyDisplay()
    }

    private fun initViews() {
        gameView = findViewById(R.id.game_view)
        tvScore = findViewById(R.id.tv_score)
        tvLives = findViewById(R.id.tv_lives)
        tvLevel = findViewById(R.id.tv_level)
        tvDifficulty = findViewById(R.id.tv_difficulty)
        btnPause = findViewById(R.id.btn_pause)
        messageLayout = findViewById(R.id.message_layout)
        tvMessageTitle = findViewById(R.id.tv_message_title)
        tvMessageSubtitle = findViewById(R.id.tv_message_subtitle)
        btnRestart = findViewById(R.id.btn_restart)
        btnResume = findViewById(R.id.btn_resume)
        btnMenuRating = findViewById(R.id.btn_menu_rating)
        btnMenuDifficulty = findViewById(R.id.btn_menu_difficulty)
        btnMenuExit = findViewById(R.id.btn_menu_exit)

        gameView.setDifficulty(currentDifficulty)

        gameView.setGameCallback(object : BlockGameView.GameCallback {
            override fun onScoreUpdate(score: Int) {
                runOnUiThread {
                    tvScore.text = "Счет: $score"
                }
            }

            override fun onLivesUpdate(lives: Int) {
                runOnUiThread {
                    tvLives.text = "Жизни: $lives"
                }
            }

            override fun onLevelUpdate(level: Int) {
                runOnUiThread {
                    tvLevel.text = "Уровень: $level"
                }
            }

            override fun onGameStart() {
                runOnUiThread {
                    hideMenu()
                    btnPause.visibility = View.VISIBLE
                }
            }

            override fun onGameOver(isWin: Boolean, score: Int, level: Int) {
                runOnUiThread {
                    if (isWin) {
                        tvMessageTitle.text = "🎉 ПОБЕДА!"
                        tvMessageSubtitle.text = "Вы прошли все 30 уровней!\nФинальный счет: $score"
                    } else {
                        tvMessageTitle.text = "💀 ИГРА ОКОНЧЕНА"
                        tvMessageSubtitle.text = "Достигнут уровень: $level\nСчет: $score"
                    }
                    showMenu(false)
                    btnPause.visibility = View.GONE
                }
                saveGameResult(score, level, isWin)
            }

            override fun onLevelComplete(level: Int, score: Int) {
                runOnUiThread {
                    tvMessageTitle.text = "🎊 УРОВЕНЬ $level ПРОЙДЕН!"
                    tvMessageSubtitle.text = "Счет: $score\nГотовы к следующему уровню?"
                    showMenu(false)
                    btnPause.visibility = View.GONE
                }
            }

            override fun onPauseGame() {
                runOnUiThread {
                    tvMessageTitle.text = "⏸️ ПАУЗА"
                    tvMessageSubtitle.text = "Игра на паузе"
                    showMenu(true)
                }
            }
        })
    }

    private fun saveGameResult(score: Int, level: Int, isWin: Boolean) {
        Log.d("BlockGame", "Game result - Score: $score, Level: $level, Win: $isWin, Difficulty: $currentDifficulty")

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            val database = Firebase.database.reference
            val userId = currentUser.uid

            database.child("users").child(userId).get().addOnSuccessListener { snapshot ->
                try {
                    val user = snapshot.getValue(User::class.java)
                    if (user != null) {
                        val updatedGamesPlayed = user.gamesPlayed + 1
                        val updatedGamesWon = if (isWin) user.gamesWon + 1 else user.gamesWon
                        val updatedTotalScore = user.totalScore + score

                        val isNewBest = user.isNewBestScore(score, level)
                        val updatedBestScore = if (isNewBest) score else user.bestScore
                        val updatedBestLevel = if (isNewBest) level else user.bestLevel

                        val baseRating = updatedBestScore
                        val winBonus = if (isWin) 200 else 0
                        val levelBonus = updatedBestLevel * 10
                        val difficultyBonus = when (currentDifficulty) {
                            "hard" -> 500
                            "medium" -> 200
                            else -> 0
                        }

                        val updatedRating = baseRating + winBonus + levelBonus + difficultyBonus

                        val updates = hashMapOf<String, Any>(
                            "gamesPlayed" to updatedGamesPlayed,
                            "gamesWon" to updatedGamesWon,
                            "totalScore" to updatedTotalScore,
                            "bestScore" to updatedBestScore,
                            "bestLevel" to updatedBestLevel,
                            "rating" to updatedRating,
                            "preferredDifficulty" to currentDifficulty,
                            "lastGameScore" to score,
                            "lastGameLevel" to level,
                            "lastGameDate" to System.currentTimeMillis()
                        )

                        database.child("users").child(userId).updateChildren(updates)
                            .addOnSuccessListener {
                                Log.d("BlockGame", "Game results saved successfully")
                                val ratingChange = updatedRating - user.rating
                                showGameResultDialog(score, level, isWin, ratingChange, isNewBest)
                            }
                            .addOnFailureListener { e ->
                                Log.e("BlockGame", "Failed to save game results", e)
                            }
                    } else {
                        val rating = score + (if (isWin) 200 else 0) + (level * 10) +
                                when (currentDifficulty) {
                                    "hard" -> 500
                                    "medium" -> 200
                                    else -> 0
                                }

                        val newUserStats = hashMapOf<String, Any>(
                            "gamesPlayed" to 1,
                            "gamesWon" to (if (isWin) 1 else 0),
                            "totalScore" to score,
                            "bestScore" to score,
                            "bestLevel" to level,
                            "rating" to rating,
                            "preferredDifficulty" to currentDifficulty,
                            "lastGameScore" to score,
                            "lastGameLevel" to level,
                            "lastGameDate" to System.currentTimeMillis()
                        )

                        database.child("users").child(userId).updateChildren(newUserStats)
                            .addOnSuccessListener {
                                Log.d("BlockGame", "New user game results saved successfully")
                                showGameResultDialog(score, level, isWin, rating, true)
                            }
                            .addOnFailureListener { e ->
                                Log.e("BlockGame", "Failed to save new user game results", e)
                            }
                    }
                } catch (e: Exception) {
                    Log.e("BlockGame", "Error processing user data", e)
                }
            }.addOnFailureListener { e ->
                Log.e("BlockGame", "Failed to get user data", e)
            }
        } else {
            Log.w("BlockGame", "User not authenticated, game results not saved")
        }
    }

    private fun showGameResultDialog(score: Int, level: Int, isWin: Boolean, ratingChange: Int, isNewBest: Boolean) {
        val message = buildString {
            if (isWin) {
                append("🎉 ПОБЕДА!\n")
            } else {
                append("💀 Игра окончена\n")
            }
            append("Уровень: $level\n")
            append("Счет: $score\n")

            if (isNewBest) {
                append("🏆 НОВЫЙ РЕКОРД!\n")
            }

            if (ratingChange > 0) {
                append("Рейтинг: +$ratingChange")
            } else {
                append("Рейтинг: $ratingChange")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Результаты игры")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun setupClickListeners() {
        btnPause.setOnClickListener {
            if (gameView.isGameStarted() && !gameView.isGameOver()) {
                gameView.pauseGame()
            }
        }

        btnRestart.setOnClickListener {
            gameView.restartGame()
            hideMenu()
            btnPause.visibility = View.VISIBLE
        }

        btnResume.setOnClickListener {
            gameView.resumeGame()
            hideMenu()
        }

        btnMenuRating.setOnClickListener {
            startActivity(Intent(this, RatingActivity::class.java))
        }

        btnMenuDifficulty.setOnClickListener {
            showDifficultyDialog()
        }

        btnMenuExit.setOnClickListener {
            finish()
        }

        gameView.setOnClickListener {
            if (!gameView.isGameStarted() && !isMenuVisible) {
                gameView.startGame()
                hideMenu()
                btnPause.visibility = View.VISIBLE
            }
        }

        messageLayout.setOnClickListener {
            if (!gameView.isGameOver() && isMenuVisible && gameView.isGameStarted()) {
                gameView.resumeGame()
                hideMenu()
            }
        }
    }

    private fun showMenu(isPauseMenu: Boolean) {
        isMenuVisible = true
        messageLayout.visibility = View.VISIBLE

        if (isPauseMenu) {
            btnResume.visibility = View.VISIBLE
            btnRestart.visibility = View.VISIBLE
        } else {
            btnResume.visibility = View.GONE
            btnRestart.visibility = View.VISIBLE
        }
    }

    private fun hideMenu() {
        isMenuVisible = false
        messageLayout.visibility = View.GONE
    }

    private fun showDifficultyDialog() {
        val difficulties = arrayOf("Легкий 🟢", "Средний 🟡", "Сложный 🔴")
        val currentIndex = when (currentDifficulty) {
            "easy" -> 0
            "hard" -> 2
            else -> 1
        }

        AlertDialog.Builder(this)
            .setTitle("🎯 Выбор уровня сложности")
            .setSingleChoiceItems(difficulties, currentIndex) { dialog, which ->
                currentDifficulty = when (which) {
                    0 -> "easy"
                    2 -> "hard"
                    else -> "medium"
                }
                updateDifficultyDisplay()
                gameView.setDifficulty(currentDifficulty)
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateDifficultyDisplay() {
        val difficultyText = when (currentDifficulty) {
            "easy" -> "Легкий 🟢"
            "hard" -> "Сложный 🔴"
            else -> "Средний 🟡"
        }
        tvDifficulty.text = difficultyText
    }

    override fun onPause() {
        super.onPause()
        if (gameView.isGameStarted() && !gameView.isGameOver()) {
            gameView.pauseGame()
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        gameView.cleanup()
    }
}

class BlockGameView : SurfaceView, SurfaceHolder.Callback, Runnable {

    constructor(context: Context) : super(context) { init() }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { init() }
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { init() }

    private fun init() {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private var gameThread: Thread? = null
    private var isRunning = false
    private var isPaused = false
    private var isSurfaceCreated = false
    private var shouldResetOnSurfaceChange = true
    private val handler = Handler(Looper.getMainLooper())

    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 1f

    private var platformX = 0f
    private var platformY = 0f
    private var platformWidthDp = 100f
    private var platformWidth = 0f
    private val platformHeightDp = 12f
    private var platformHeight = 0f

    private var ballX = 0f
    private var ballY = 0f
    private var ballRadiusDp = 7f
    private var ballRadius = 0f
    private var originalBallRadius = 0f
    private var ballSpeedX = 0f
    private var ballSpeedY = 0f

    private val blocks = mutableListOf<Block>()
    private val blocksToRemove = mutableListOf<Block>()
    private val blockRows = 5
    private val blockCols = 8
    private val blockWidthDp = 40f
    private var blockWidth = 0f
    private val blockHeightDp = 18f
    private var blockHeight = 0f
    private val blockPaddingDp = 2f
    private var blockPadding = 0f

    private val activeBonuses = mutableListOf<Bonus>()
    private var ballPower: BallPower = BallPower.NORMAL
    private var bonusEffectEndTime = 0L
    private val extraBalls = mutableListOf<ExtraBall>()

    private var score = 0
    private var lives = 3
    private var currentLevel = 1
    private val maxLevels = 30
    private var isGameOver = false
    private var isGameStarted = false

    private var baseSpeedDp = 4f
    private var baseSpeed = 0f

    private var currentDifficulty = "medium"

    private val platformColor = Color.parseColor("#4FC3F7")
    private val ballColor = Color.WHITE
    private val backgroundColor = Color.BLACK

    private val paint = Paint()

    private var gameCallback: GameCallback? = null

    // Новые свойства для расширенных эффектов
    private var ghostBallActive = false
    private var magneticForce = 0f
    private var rainbowHue = 0f
    private var explosionRadius = 0f
    private var ballTrail = mutableListOf<BallTrailPoint>()
    private var isBallOnFire = false
    private var firePenetrationCount = 0
    private var ballPulse = 0f

    enum class BallPower {
        NORMAL, TRIPLE, FIREBALL, LASER, BIG_BALL, SLOW_MOTION,
        GHOST_BALL, MAGNETIC, EXPLOSIVE, MULTIBALL, RAINBOW, SPEED_BALL
    }

    data class Bonus(
        val x: Float,
        var y: Float,
        val width: Float,
        val height: Float,
        val type: BallPower,
        val speed: Float = 2f
    )

    data class ExtraBall(
        var x: Float,
        var y: Float,
        var speedX: Float,
        var speedY: Float,
        var radius: Float
    )

    data class Block(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val color: Int,
        val bonusType: BallPower? = null
    )

    data class BallTrailPoint(
        var x: Float,
        var y: Float,
        var radius: Float,
        var alpha: Int,
        var color: Int
    )

    interface GameCallback {
        fun onScoreUpdate(score: Int)
        fun onLivesUpdate(lives: Int)
        fun onLevelUpdate(level: Int)
        fun onGameStart()
        fun onGameOver(isWin: Boolean, score: Int, level: Int)
        fun onLevelComplete(level: Int, score: Int)
        fun onPauseGame()
    }

    fun setGameCallback(callback: GameCallback) {
        this.gameCallback = callback
    }

    fun setDifficulty(difficulty: String) {
        currentDifficulty = difficulty
        applyDifficultySettings()
    }

    private fun applyDifficultySettings() {
        when (currentDifficulty) {
            "easy" -> {
                baseSpeedDp = 3f
                lives = 5
                platformWidthDp = 125f
                ballRadiusDp = 8f
            }
            "hard" -> {
                baseSpeedDp = 6f
                lives = 2
                platformWidthDp = 75f
                ballRadiusDp = 6f
            }
            else -> {
                baseSpeedDp = 4f
                lives = 3
                platformWidthDp = 100f
                ballRadiusDp = 7f
            }
        }

        convertDpToPixels()

        handler.post {
            gameCallback?.onLivesUpdate(lives)
        }

        if (screenWidth > 0) {
            platformX = (screenWidth - platformWidth) / 2
        }
    }

    private fun convertDpToPixels() {
        val metrics = DisplayMetrics()
        (context as? AppCompatActivity)?.windowManager?.defaultDisplay?.getMetrics(metrics)
        density = metrics.density

        platformWidth = platformWidthDp * density
        platformHeight = platformHeightDp * density
        ballRadius = ballRadiusDp * density
        originalBallRadius = ballRadius
        blockWidth = blockWidthDp * density
        blockHeight = blockHeightDp * density
        blockPadding = blockPaddingDp * density
        baseSpeed = baseSpeedDp * density
    }

    private fun initLevel() {
        synchronized(blocks) {
            blocks.clear()
            blocksToRemove.clear()
            activeBonuses.clear()
            extraBalls.clear()
            resetAllEffects()

            val levelColors = getLevelColors(currentLevel)
            val levelLayout = getLevelLayout(currentLevel)

            val totalBlocksWidth = blockCols * blockWidth + (blockCols - 1) * blockPadding
            val startX = (screenWidth - totalBlocksWidth) / 2
            val startY = 120f * density

            for (row in 0 until blockRows) {
                for (col in 0 until blockCols) {
                    if (levelLayout[row][col] == 1) {
                        val blockX = startX + col * (blockWidth + blockPadding)
                        val blockY = startY + row * (blockHeight + blockPadding)
                        val color = levelColors[row % levelColors.size]

                        val isBonusBlock = Random.nextFloat() < getBonusBlockProbability(currentLevel)
                        val bonusType = if (isBonusBlock) getRandomBonusType() else null

                        blocks.add(Block(blockX, blockY, blockWidth, blockHeight, color, bonusType))
                    }
                }
            }
        }

        val levelSpeedMultiplier = 1f + (currentLevel - 1) * 0.08f
        val currentSpeed = baseSpeed * levelSpeedMultiplier

        ballSpeedX = currentSpeed * if (Random.nextBoolean()) 1 else -1
        ballSpeedY = currentSpeed

        handler.post {
            gameCallback?.onLevelUpdate(currentLevel)
        }
    }

    private fun getLevelColors(level: Int): IntArray {
        return when {
            level <= 5 -> intArrayOf(
                Color.RED, Color.GREEN, Color.YELLOW, Color.CYAN, Color.MAGENTA
            )
            level <= 10 -> intArrayOf(
                Color.parseColor("#FF6B6B"),
                Color.parseColor("#4ECDC4"),
                Color.parseColor("#FFE66D"),
                Color.parseColor("#6A0572"),
                Color.parseColor("#1A535C")
            )
            level <= 15 -> intArrayOf(
                Color.parseColor("#2EC4B6"),
                Color.parseColor("#E71D36"),
                Color.parseColor("#FF9F1C"),
                Color.parseColor("#011627"),
                Color.parseColor("#FDFFFC")
            )
            level <= 20 -> intArrayOf(
                Color.parseColor("#540D6E"),
                Color.parseColor("#EE4266"),
                Color.parseColor("#FFD23F"),
                Color.parseColor("#3BCEAC"),
                Color.parseColor("#0EAD69")
            )
            else -> intArrayOf(
                Color.parseColor("#FF1654"),
                Color.parseColor("#247BA0"),
                Color.parseColor("#70C1B3"),
                Color.parseColor("#B2DBBF"),
                Color.parseColor("#F3FFBD")
            )
        }
    }

    private fun getLevelLayout(level: Int): Array<IntArray> {
        return when (level) {
            1 -> arrayOf(
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(1,1,1,1,1,1,1,1)
            )
            2 -> arrayOf(
                intArrayOf(1,0,1,0,1,0,1,0),
                intArrayOf(0,1,0,1,0,1,0,1),
                intArrayOf(1,0,1,0,1,0,1,0),
                intArrayOf(0,1,0,1,0,1,0,1),
                intArrayOf(1,0,1,0,1,0,1,0)
            )
            3 -> arrayOf(
                intArrayOf(0,0,1,1,1,1,0,0),
                intArrayOf(0,1,1,1,1,1,1,0),
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(0,1,1,1,1,1,1,0),
                intArrayOf(0,0,1,1,1,1,0,0)
            )
            4 -> arrayOf(
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(0,1,0,0,0,0,1,0),
                intArrayOf(0,0,1,1,1,1,0,0),
                intArrayOf(0,1,0,0,0,0,1,0),
                intArrayOf(1,0,0,0,0,0,0,1)
            )
            5 -> arrayOf(
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(1,1,1,1,1,1,1,1)
            )
            6 -> arrayOf(
                intArrayOf(1,1,0,0,0,0,1,1),
                intArrayOf(1,1,0,0,0,0,1,1),
                intArrayOf(1,1,0,0,0,0,1,1),
                intArrayOf(1,1,0,0,0,0,1,1),
                intArrayOf(1,1,0,0,0,0,1,1)
            )
            7 -> arrayOf(
                intArrayOf(0,0,0,1,1,0,0,0),
                intArrayOf(0,0,0,1,1,0,0,0),
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(0,0,0,1,1,0,0,0),
                intArrayOf(0,0,0,1,1,0,0,0)
            )
            8 -> arrayOf(
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(0,0,0,0,0,0,0,0),
                intArrayOf(0,0,1,0,0,1,0,0),
                intArrayOf(0,0,0,0,0,0,0,0),
                intArrayOf(1,0,0,0,0,0,0,1)
            )
            9 -> arrayOf(
                intArrayOf(0,0,1,1,1,1,0,0),
                intArrayOf(0,1,1,1,1,1,1,0),
                intArrayOf(1,1,0,0,0,0,1,1),
                intArrayOf(0,1,1,1,1,1,1,0),
                intArrayOf(0,0,1,1,1,1,0,0)
            )
            10 -> arrayOf(
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(1,0,1,1,1,1,0,1),
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(1,1,1,1,1,1,1,1)
            )
            11 -> arrayOf(
                intArrayOf(1,0,0,0,0,0,0,0),
                intArrayOf(1,1,0,0,0,0,0,0),
                intArrayOf(1,1,1,0,0,0,0,0),
                intArrayOf(1,1,1,1,0,0,0,0),
                intArrayOf(1,1,1,1,1,0,0,0)
            )
            12 -> arrayOf(
                intArrayOf(0,0,0,0,0,0,0,1),
                intArrayOf(0,0,0,0,0,0,1,1),
                intArrayOf(0,0,0,0,0,1,1,1),
                intArrayOf(0,0,0,0,1,1,1,1),
                intArrayOf(0,0,0,1,1,1,1,1)
            )
            13 -> arrayOf(
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(1,0,0,0,0,0,0,1)
            )
            14 -> arrayOf(
                intArrayOf(0,1,1,1,1,1,1,0),
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(0,1,1,1,1,1,1,0)
            )
            15 -> arrayOf(
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(0,0,0,1,1,0,0,0),
                intArrayOf(0,0,0,1,1,0,0,0),
                intArrayOf(0,0,0,1,1,0,0,0),
                intArrayOf(0,0,0,1,1,0,0,0)
            )
            16 -> arrayOf(
                intArrayOf(1,1,0,0,1,1,0,0),
                intArrayOf(1,1,0,0,1,1,0,0),
                intArrayOf(0,0,1,1,0,0,1,1),
                intArrayOf(0,0,1,1,0,0,1,1),
                intArrayOf(1,1,0,0,1,1,0,0)
            )
            17 -> arrayOf(
                intArrayOf(0,0,0,1,1,0,0,0),
                intArrayOf(0,0,0,1,1,0,0,0),
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(0,0,0,1,1,0,0,0),
                intArrayOf(0,0,0,1,1,0,0,0)
            )
            18 -> arrayOf(
                intArrayOf(0,0,0,1,1,0,0,0),
                intArrayOf(0,0,1,1,1,1,0,0),
                intArrayOf(0,1,1,1,1,1,1,0),
                intArrayOf(0,0,1,1,1,1,0,0),
                intArrayOf(0,0,0,1,1,0,0,0)
            )
            19 -> arrayOf(
                intArrayOf(0,0,1,0,0,1,0,0),
                intArrayOf(0,1,1,1,1,1,1,0),
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(0,0,1,1,1,1,0,0),
                intArrayOf(0,0,0,1,1,0,0,0)
            )
            20 -> arrayOf(
                intArrayOf(1,1,0,0,0,0,1,1),
                intArrayOf(0,1,1,0,0,1,1,0),
                intArrayOf(0,0,1,1,1,1,0,0),
                intArrayOf(0,1,1,0,0,1,1,0),
                intArrayOf(1,1,0,0,0,0,1,1)
            )
            21 -> arrayOf(
                intArrayOf(0,0,1,1,1,1,0,0),
                intArrayOf(0,1,0,0,0,0,1,0),
                intArrayOf(1,0,1,0,0,1,0,1),
                intArrayOf(0,1,0,0,0,0,1,0),
                intArrayOf(0,0,1,1,1,1,0,0)
            )
            22 -> arrayOf(
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(1,0,1,1,1,1,0,1),
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(1,1,1,1,1,1,1,1)
            )
            23 -> arrayOf(
                intArrayOf(1,1,1,1,1,1,1,0),
                intArrayOf(1,0,0,0,0,0,1,0),
                intArrayOf(1,0,1,1,1,0,1,0),
                intArrayOf(1,0,0,0,1,0,1,1),
                intArrayOf(1,1,1,0,0,0,0,1)
            )
            24 -> arrayOf(
                intArrayOf(0,1,1,1,1,1,1,0),
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(1,1,0,0,0,0,1,1),
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(0,1,1,1,1,1,1,0)
            )
            25 -> arrayOf(
                intArrayOf(0,1,1,0,1,1,0,1),
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(0,1,1,1,1,1,0,1),
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(0,1,1,0,1,1,0,1)
            )
            26 -> arrayOf(
                intArrayOf(0,0,1,1,1,1,0,0),
                intArrayOf(0,1,0,0,0,0,1,0),
                intArrayOf(1,0,1,1,1,1,0,1),
                intArrayOf(0,1,0,0,0,0,1,0),
                intArrayOf(0,0,1,1,1,1,0,0)
            )
            27 -> arrayOf(
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(1,0,0,0,0,0,0,1),
                intArrayOf(1,0,1,1,1,1,0,1),
                intArrayOf(1,0,1,0,0,1,0,1),
                intArrayOf(1,1,1,1,1,1,1,1)
            )
            28 -> arrayOf(
                intArrayOf(1,1,0,0,0,0,1,1),
                intArrayOf(1,1,0,0,0,0,1,1),
                intArrayOf(0,0,0,0,0,0,0,0),
                intArrayOf(1,1,0,0,0,0,1,1),
                intArrayOf(1,1,0,0,0,0,1,1)
            )
            29 -> arrayOf(
                intArrayOf(1,0,0,0,0,0,0,0),
                intArrayOf(0,1,0,0,0,0,0,0),
                intArrayOf(0,0,1,0,0,0,0,0),
                intArrayOf(0,0,0,1,0,0,0,0),
                intArrayOf(0,0,0,0,1,0,0,0)
            )
            30 -> arrayOf(
                intArrayOf(1,1,1,1,1,1,1,1),
                intArrayOf(1,1,0,1,1,0,1,1),
                intArrayOf(1,0,1,0,0,1,0,1),
                intArrayOf(1,1,0,1,1,0,1,1),
                intArrayOf(1,1,1,1,1,1,1,1)
            )
            else -> {
                Array(blockRows) { row ->
                    IntArray(blockCols) { col ->
                        if (Random.nextFloat() < (0.7f - (level - 30) * 0.01f)) 1 else 0
                    }
                }
            }
        }
    }

    private fun getBonusBlockProbability(level: Int): Float {
        return when {
            level <= 5 -> 0.1f
            level <= 10 -> 0.15f
            level <= 20 -> 0.2f
            else -> 0.25f
        }
    }

    private fun getRandomBonusType(): BallPower {
        val types = listOf(
            BallPower.TRIPLE,
            BallPower.FIREBALL,
            BallPower.LASER,
            BallPower.BIG_BALL,
            BallPower.SLOW_MOTION,
            BallPower.GHOST_BALL,
            BallPower.MAGNETIC,
            BallPower.EXPLOSIVE,
            BallPower.MULTIBALL,
            BallPower.RAINBOW,
            BallPower.SPEED_BALL
        )
        return types.random()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceCreated = true
        screenWidth = width
        screenHeight = height
        convertDpToPixels()

        if (shouldResetOnSurfaceChange) {
            resetGame()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenWidth = width
        screenHeight = height

        if (isGameStarted && !isGameOver) {
            platformX = (screenWidth - platformWidth) / 2
            platformY = screenHeight - 150f * density
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceCreated = false
        pauseGame()
    }

    override fun run() {
        while (isRunning) {
            if (!isSurfaceCreated) continue
            if (!isPaused) {
                update()
            }
            draw()
            try {
                Thread.sleep(16)
            } catch (e: InterruptedException) {
                Log.d("BlockGame", "Game thread interrupted")
                break
            }
        }
    }

    private fun update() {
        if (!isGameStarted || isGameOver) return

        // Обновление пульсации
        ballPulse = (ballPulse + 0.1f) % (2 * PI.toFloat())

        // Обновление магнитного эффекта
        if (magneticForce > 0) {
            val platformCenter = platformX + platformWidth / 2
            val distanceToPlatform = platformCenter - ballX
            ballSpeedX += distanceToPlatform * 0.01f * magneticForce
        }

        // Обновление радужного эффекта
        if (ballPower == BallPower.RAINBOW) {
            rainbowHue = (rainbowHue + 5f) % 360f
        }

        // Добавление следа мяча
        if (ballPower == BallPower.FIREBALL || ballPower == BallPower.RAINBOW ||
            ballPower == BallPower.SPEED_BALL) {
            addBallTrail()
        }

        // Обновление следа мяча
        updateBallTrail()

        // Обновление основного мяча
        ballX += ballSpeedX
        ballY += ballSpeedY

        // Обновление дополнительных мячей
        val extraBallIterator = extraBalls.iterator()
        while (extraBallIterator.hasNext()) {
            val ball = extraBallIterator.next()
            ball.x += ball.speedX
            ball.y += ball.speedY

            if (checkExtraBallCollisions(ball)) {
                extraBallIterator.remove()
            }
        }

        // Столкновение со стенами
        if (ballX - ballRadius < 0) {
            ballX = ballRadius
            ballSpeedX = -ballSpeedX
        } else if (ballX + ballRadius > screenWidth) {
            ballX = screenWidth - ballRadius
            ballSpeedX = -ballSpeedX
        }

        if (ballY - ballRadius < 0) {
            ballY = ballRadius
            ballSpeedY = -ballSpeedY
        }

        // Столкновение с платформой
        if (checkPlatformCollision(ballX, ballY, ballRadius)) {
            val hitPos = (ballX - (platformX + platformWidth / 2)) / (platformWidth / 2)
            ballSpeedX = hitPos * 12f * density
            ballSpeedY = -abs(ballSpeedY)
            ballY = platformY - ballRadius
        }

        // Столкновение с блоками
        var blockHit = false
        synchronized(blocks) {
            val iterator = blocks.iterator()
            while (iterator.hasNext()) {
                val block = iterator.next()
                if (checkBallBlockCollision(ballX, ballY, ballRadius, block)) {
                    handleBlockCollision(block)
                    if (ballPower != BallPower.FIREBALL) {
                        blockHit = true
                    }
                    break
                }
            }
            blocks.removeAll(blocksToRemove)
            blocksToRemove.clear()
        }

        // Обновление бонусов
        val bonusIterator = activeBonuses.iterator()
        while (bonusIterator.hasNext()) {
            val bonus = bonusIterator.next()
            bonus.y += bonus.speed * density

            if (bonus.y + bonus.height > platformY &&
                bonus.y < platformY + platformHeight &&
                bonus.x + bonus.width > platformX &&
                bonus.x < platformX + platformWidth) {

                applyBonus(bonus.type)
                bonusIterator.remove()
            }

            if (bonus.y > screenHeight) {
                bonusIterator.remove()
            }
        }

        // Проверка окончания действия бонуса
        if (ballPower != BallPower.NORMAL && System.currentTimeMillis() > bonusEffectEndTime) {
            deactivateBonus()
        }

        // Проверка проигрыша
        if (ballY - ballRadius > screenHeight && extraBalls.isEmpty()) {
            lives--
            handler.post {
                gameCallback?.onLivesUpdate(lives)
            }

            if (lives > 0) {
                resetBall()
            } else {
                isGameOver = true
                handler.post {
                    gameCallback?.onGameOver(false, score, currentLevel)
                }
            }
        }

        // Проверка завершения уровня
        synchronized(blocks) {
            if (blocks.isEmpty()) {
                if (currentLevel < maxLevels) {
                    currentLevel++
                    handler.post {
                        gameCallback?.onLevelComplete(currentLevel - 1, score)
                    }
                    isGameStarted = false
                    initLevel()
                    resetBall()
                } else {
                    isGameOver = true
                    handler.post {
                        gameCallback?.onGameOver(true, score, currentLevel)
                    }
                }
            }
        }
    }

    private fun checkPlatformCollision(x: Float, y: Float, radius: Float): Boolean {
        if (ghostBallActive) return false

        return y + radius > platformY &&
                y - radius < platformY + platformHeight &&
                x + radius > platformX &&
                x - radius < platformX + platformWidth
    }

    private fun checkBallBlockCollision(x: Float, y: Float, radius: Float, block: Block): Boolean {
        return x + radius > block.x &&
                x - radius < block.x + block.width &&
                y + radius > block.y &&
                y - radius < block.y + block.height
    }

    private fun checkExtraBallCollisions(ball: ExtraBall): Boolean {
        if (ball.x - ball.radius < 0 || ball.x + ball.radius > screenWidth) {
            ball.speedX = -ball.speedX
        }
        if (ball.y - ball.radius < 0) {
            ball.speedY = -ball.speedY
        }

        if (checkPlatformCollision(ball.x, ball.y, ball.radius)) {
            val hitPos = (ball.x - (platformX + platformWidth / 2)) / (platformWidth / 2)
            ball.speedX = hitPos * 12f * density
            ball.speedY = -abs(ball.speedY)
            ball.y = platformY - ball.radius
        }

        synchronized(blocks) {
            val iterator = blocks.iterator()
            while (iterator.hasNext()) {
                val block = iterator.next()
                if (checkBallBlockCollision(ball.x, ball.y, ball.radius, block)) {
                    handleBlockCollision(block)
                    return true
                }
            }
        }

        if (ball.y - ball.radius > screenHeight) {
            return true
        }

        return false
    }

    private fun handleBlockCollision(block: Block) {
        val overlapLeft = (ballX + ballRadius) - block.x
        val overlapRight = (block.x + block.width) - (ballX - ballRadius)
        val overlapTop = (ballY + ballRadius) - block.y
        val overlapBottom = (block.y + block.height) - (ballY - ballRadius)

        val minOverlap = minOf(overlapLeft, overlapRight, overlapTop, overlapBottom)

        if (ballPower != BallPower.FIREBALL) {
            when (minOverlap) {
                overlapLeft, overlapRight -> {
                    ballSpeedX = -ballSpeedX
                    if (minOverlap == overlapLeft) ballX = block.x - ballRadius
                    else ballX = block.x + block.width + ballRadius
                }
                overlapTop, overlapBottom -> {
                    ballSpeedY = -ballSpeedY
                    if (minOverlap == overlapTop) ballY = block.y - ballRadius
                    else ballY = block.y + block.height + ballRadius
                }
            }
        }

        if (ballPower == BallPower.EXPLOSIVE) {
            activateExplosion(block.x + block.width / 2, block.y + block.height / 2)
        }

        if (ballPower == BallPower.MULTIBALL) {
            createMultiBallsOnCollision()
        }

        block.bonusType?.let { bonusType ->
            val bonusWidth = 20f * density
            val bonusHeight = 20f * density
            val bonusX = block.x + (block.width - bonusWidth) / 2
            val bonusY = block.y + (block.height - bonusHeight) / 2

            activeBonuses.add(Bonus(bonusX, bonusY, bonusWidth, bonusHeight, bonusType))
        }

        blocksToRemove.add(block)

        if (ballPower == BallPower.FIREBALL) {
            firePenetrationCount++
            score += 15 * currentLevel
        } else {
            score += 10 * currentLevel
        }

        handler.post {
            gameCallback?.onScoreUpdate(score)
        }
    }

    private fun applyBonus(type: BallPower) {
        ballPower = type
        bonusEffectEndTime = System.currentTimeMillis() + 10000

        resetAllEffects()

        when (type) {
            BallPower.TRIPLE -> {
                createExtraBalls(2)
            }
            BallPower.FIREBALL -> {
                isBallOnFire = true
                firePenetrationCount = 0
            }
            BallPower.LASER -> {
                activateLaser()
            }
            BallPower.BIG_BALL -> {
                ballRadius = originalBallRadius * 1.8f
            }
            BallPower.SLOW_MOTION -> {
                ballSpeedX *= 0.6f
                ballSpeedY *= 0.6f
            }
            BallPower.GHOST_BALL -> {
                ghostBallActive = true
            }
            BallPower.MAGNETIC -> {
                magneticForce = 15f * density
            }
            BallPower.EXPLOSIVE -> {
                explosionRadius = 80f * density
            }
            BallPower.MULTIBALL -> {
                createMultiBalls()
            }
            BallPower.RAINBOW -> {
                // Эффект обрабатывается в draw
            }
            BallPower.SPEED_BALL -> {
                ballSpeedX *= 1.5f
                ballSpeedY *= 1.5f
            }
            else -> {}
        }

        showBonusActivatedMessage(type)
    }

    private fun resetAllEffects() {
        ghostBallActive = false
        magneticForce = 0f
        explosionRadius = 0f
        isBallOnFire = false
        firePenetrationCount = 0
        ballTrail.clear()
    }

    private fun deactivateBonus() {
        when (ballPower) {
            BallPower.BIG_BALL -> ballRadius = originalBallRadius
            BallPower.SLOW_MOTION, BallPower.SPEED_BALL -> {
                val levelSpeedMultiplier = 1f + (currentLevel - 1) * 0.08f
                val currentSpeed = baseSpeed * levelSpeedMultiplier
                ballSpeedX = currentSpeed * (ballSpeedX / abs(ballSpeedX))
                ballSpeedY = currentSpeed * (ballSpeedY / abs(ballSpeedY))
            }
            else -> {}
        }

        resetAllEffects()
        ballPower = BallPower.NORMAL
        extraBalls.clear()
    }

    private fun addBallTrail() {
        if (ballTrail.size > 10) {
            ballTrail.removeAt(0)
        }

        val trailColor = when (ballPower) {
            BallPower.FIREBALL -> Color.argb(150, 255, 100, 0)
            BallPower.RAINBOW -> Color.HSVToColor(150, floatArrayOf(rainbowHue, 1f, 1f))
            BallPower.SPEED_BALL -> Color.argb(150, 100, 200, 255)
            else -> Color.argb(100, 255, 255, 255)
        }

        ballTrail.add(BallTrailPoint(ballX, ballY, ballRadius * 0.7f, 150, trailColor))
    }

    private fun updateBallTrail() {
        val iterator = ballTrail.iterator()
        while (iterator.hasNext()) {
            val point = iterator.next()
            if (point.alpha <= 0) {
                iterator.remove()
            }
        }
        ballTrail.forEach { point ->
            point.alpha -= 15
        }
    }

    private fun activateExplosion(centerX: Float, centerY: Float) {
        synchronized(blocks) {
            val blocksToRemoveByExplosion = mutableListOf<Block>()
            val iterator = blocks.iterator()
            while (iterator.hasNext()) {
                val block = iterator.next()
                val distance = sqrt(
                    (block.x + block.width / 2 - centerX).pow(2) +
                            (block.y + block.height / 2 - centerY).pow(2)
                )

                if (distance < explosionRadius) {
                    blocksToRemoveByExplosion.add(block)
                    score += 8 * currentLevel
                }
            }
            blocks.removeAll(blocksToRemoveByExplosion)
        }
    }

    private fun createMultiBalls() {
        for (i in 0 until 3) {
            val angle = (2 * PI * i / 3).toFloat()
            val extraBallSpeedX = baseSpeed * cos(angle)
            val extraBallSpeedY = baseSpeed * sin(angle)

            extraBalls.add(ExtraBall(
                x = ballX,
                y = ballY,
                speedX = extraBallSpeedX,
                speedY = extraBallSpeedY,
                radius = originalBallRadius * 0.7f
            ))
        }
    }

    private fun createMultiBallsOnCollision() {
        if (Random.nextFloat() < 0.3f) {
            val angle = Random.nextFloat() * (2 * PI).toFloat()
            extraBalls.add(ExtraBall(
                x = ballX,
                y = ballY,
                speedX = baseSpeed * cos(angle),
                speedY = baseSpeed * sin(angle),
                radius = originalBallRadius * 0.6f
            ))
        }
    }

    private fun showBonusActivatedMessage(type: BallPower) {
        val message = when (type) {
            BallPower.FIREBALL -> "🔥 ОГНЕННЫЙ ШАР! Пробивает блоки!"
            BallPower.GHOST_BALL -> "👻 ПРИЗРАЧНЫЙ МЯЧ! Проходит сквозь платформу!"
            BallPower.MAGNETIC -> "🧲 МАГНИТНЫЙ МЯЧ! Притягивается к платформе!"
            BallPower.EXPLOSIVE -> "💣 ВЗРЫВНОЙ МЯЧ! Уничтожает блоки вокруг!"
            BallPower.MULTIBALL -> "🔮 МУЛЬТИМЯЧ! Создает дополнительные мячи!"
            BallPower.RAINBOW -> "🌈 РАДУЖНЫЙ МЯЧ! Меняет цвет!"
            BallPower.SPEED_BALL -> "⚡ УСКОРЕННЫЙ МЯЧ! Выше скорость!"
            else -> ""
        }

        if (message.isNotEmpty()) {
            handler.post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createExtraBalls(count: Int) {
        for (i in 0 until count) {
            val angle = (PI / 4 + (PI / 2) * i / (count + 1)).toFloat()
            val extraBallSpeedX = baseSpeed * cos(angle)
            val extraBallSpeedY = -baseSpeed * sin(angle)

            extraBalls.add(ExtraBall(
                x = ballX,
                y = ballY,
                speedX = extraBallSpeedX,
                speedY = extraBallSpeedY,
                radius = originalBallRadius
            ))
        }
    }

    private fun activateLaser() {
        synchronized(blocks) {
            val blocksToRemoveByLaser = mutableListOf<Block>()
            val iterator = blocks.iterator()
            while (iterator.hasNext()) {
                val block = iterator.next()
                if (block.y + block.height > ballY && block.y < ballY + ballRadius * 2) {
                    blocksToRemoveByLaser.add(block)
                    score += 5 * currentLevel
                }
            }
            blocks.removeAll(blocksToRemoveByLaser)

            handler.post {
                gameCallback?.onScoreUpdate(score)
            }
        }
    }

    private fun draw() {
        if (!isSurfaceCreated || !holder.surface.isValid) return

        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            canvas?.let {
                drawGame(it)
            }
        } catch (e: Exception) {
            Log.e("BlockGame", "Error in draw: ${e.message}")
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    Log.e("BlockGame", "Error unlocking canvas: ${e.message}")
                }
            }
        }
    }

    private fun drawGame(canvas: Canvas) {
        canvas.drawColor(backgroundColor)

        drawBallTrail(canvas)

        paint.color = platformColor
        canvas.drawRoundRect(
            platformX, platformY,
            platformX + platformWidth, platformY + platformHeight,
            12f, 12f, paint
        )

        for (bonus in activeBonuses) {
            paint.color = getBonusColor(bonus.type)
            canvas.drawRect(bonus.x, bonus.y, bonus.x + bonus.width, bonus.y + bonus.height, paint)

            paint.color = Color.WHITE
            paint.textSize = bonus.height * 0.6f
            val text = getBonusSymbol(bonus.type)
            val textBounds = Rect()
            paint.getTextBounds(text, 0, text.length, textBounds)
            canvas.drawText(
                text,
                bonus.x + (bonus.width - textBounds.width()) / 2,
                bonus.y + (bonus.height + textBounds.height()) / 2,
                paint
            )
        }

        when (ballPower) {
            BallPower.FIREBALL -> drawFireBall(canvas)
            BallPower.RAINBOW -> drawRainbowBall(canvas)
            BallPower.GHOST_BALL -> drawGhostBall(canvas)
            BallPower.EXPLOSIVE -> drawExplosiveBall(canvas)
            else -> {
                paint.color = when (ballPower) {
                    BallPower.BIG_BALL -> Color.MAGENTA
                    BallPower.SLOW_MOTION -> Color.CYAN
                    BallPower.MAGNETIC -> Color.BLUE
                    BallPower.MULTIBALL -> Color.GREEN
                    BallPower.SPEED_BALL -> Color.YELLOW
                    else -> ballColor
                }
                canvas.drawCircle(ballX, ballY, ballRadius, paint)
            }
        }

        paint.color = Color.BLUE
        for (ball in extraBalls) {
            canvas.drawCircle(ball.x, ball.y, ball.radius, paint)
        }

        synchronized(blocks) {
            for (block in blocks) {
                paint.color = block.color
                canvas.drawRoundRect(
                    block.x, block.y,
                    block.x + block.width, block.y + block.height,
                    8f, 8f, paint
                )

                if (block.bonusType != null) {
                    paint.color = Color.WHITE
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 3f
                    canvas.drawRoundRect(
                        block.x, block.y,
                        block.x + block.width, block.y + block.height,
                        8f, 8f, paint
                    )
                    paint.style = Paint.Style.FILL

                    paint.color = Color.WHITE
                    paint.textSize = block.height * 0.6f
                    val text = getBonusSymbol(block.bonusType!!)
                    val textBounds = Rect()
                    paint.getTextBounds(text, 0, text.length, textBounds)
                    canvas.drawText(
                        text,
                        block.x + (block.width - textBounds.width()) / 2,
                        block.y + (block.height + textBounds.height()) / 2,
                        paint
                    )
                } else {
                    paint.color = Color.WHITE
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.5f
                    canvas.drawRoundRect(
                        block.x, block.y,
                        block.x + block.width, block.y + block.height,
                        8f, 8f, paint
                    )
                    paint.style = Paint.Style.FILL
                }
            }
        }
    }

    private fun drawBallTrail(canvas: Canvas) {
        for (point in ballTrail) {
            paint.color = point.color
            paint.alpha = point.alpha
            canvas.drawCircle(point.x, point.y, point.radius, paint)
        }
        paint.alpha = 255
    }

    private fun drawFireBall(canvas: Canvas) {
        val firePaint = Paint().apply {
            shader = RadialGradient(
                ballX, ballY, ballRadius * 2f,
                Color.YELLOW, Color.RED, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(ballX, ballY, ballRadius * 1.5f, firePaint)

        paint.color = Color.YELLOW
        canvas.drawCircle(ballX, ballY, ballRadius, paint)

        paint.color = Color.WHITE
        for (i in 0 until 5) {
            val sparkAngle = Random.nextFloat() * (2 * PI).toFloat()
            val sparkDist = ballRadius * 1.2f
            val sparkX = ballX + cos(sparkAngle) * sparkDist
            val sparkY = ballY + sin(sparkAngle) * sparkDist
            canvas.drawCircle(sparkX, sparkY, ballRadius * 0.2f, paint)
        }
    }

    private fun drawRainbowBall(canvas: Canvas) {
        val colors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA
        )
        val rainbowPaint = Paint().apply {
            shader = SweepGradient(ballX, ballY, colors, null)
        }
        canvas.drawCircle(ballX, ballY, ballRadius, rainbowPaint)
    }

    private fun drawGhostBall(canvas: Canvas) {
        paint.color = Color.argb(150, 200, 200, 255)
        canvas.drawCircle(ballX, ballY, ballRadius, paint)

        paint.color = Color.argb(80, 150, 150, 255)
        canvas.drawCircle(ballX, ballY, ballRadius * 1.3f, paint)
    }

    private fun drawExplosiveBall(canvas: Canvas) {
        val pulse = (System.currentTimeMillis() % 1000) / 1000f
        val pulseRadius = ballRadius * (1 + 0.2f * sin(pulse * (2 * PI).toFloat()))

        paint.color = Color.argb(200, 255, 100, 0)
        canvas.drawCircle(ballX, ballY, pulseRadius, paint)

        paint.color = Color.YELLOW
        for (i in 0 until 8) {
            val angle = (2 * PI * i / 8).toFloat()
            val particleX = ballX + cos(angle) * ballRadius * 0.8f
            val particleY = ballY + sin(angle) * ballRadius * 0.8f
            canvas.drawCircle(particleX, particleY, ballRadius * 0.3f, paint)
        }
    }

    private fun getBonusColor(type: BallPower): Int {
        return when (type) {
            BallPower.TRIPLE -> Color.BLUE
            BallPower.FIREBALL -> Color.RED
            BallPower.LASER -> Color.GREEN
            BallPower.BIG_BALL -> Color.MAGENTA
            BallPower.SLOW_MOTION -> Color.CYAN
            BallPower.GHOST_BALL -> Color.argb(200, 150, 150, 255)
            BallPower.MAGNETIC -> Color.BLUE
            BallPower.EXPLOSIVE -> Color.argb(200, 255, 100, 0)
            BallPower.MULTIBALL -> Color.GREEN
            BallPower.RAINBOW -> Color.MAGENTA
            BallPower.SPEED_BALL -> Color.YELLOW
            else -> Color.GRAY
        }
    }

    private fun getBonusSymbol(type: BallPower): String {
        return when (type) {
            BallPower.TRIPLE -> "3x"
            BallPower.FIREBALL -> "🔥"
            BallPower.LASER -> "⚡"
            BallPower.BIG_BALL -> "🔴"
            BallPower.SLOW_MOTION -> "🐌"
            BallPower.GHOST_BALL -> "👻"
            BallPower.MAGNETIC -> "🧲"
            BallPower.EXPLOSIVE -> "💣"
            BallPower.MULTIBALL -> "🔮"
            BallPower.RAINBOW -> "🌈"
            BallPower.SPEED_BALL -> "⚡"
            else -> "?"
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isSurfaceCreated || isPaused) return true

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                platformX = event.x - platformWidth / 2

                if (platformX < 0) platformX = 0f
                if (platformX > screenWidth - platformWidth) platformX = screenWidth - platformWidth

                if (!isGameStarted && !isGameOver) {
                    startGame()
                }
            }
        }
        return true
    }

    fun startGame() {
        isGameStarted = true
        resumeGame()
        handler.post {
            gameCallback?.onGameStart()
        }
    }

    fun restartGame() {
        shouldResetOnSurfaceChange = true
        resetGame()
        resumeGame()
    }

    private fun resetGame() {
        score = 0
        isGameOver = false
        isGameStarted = false
        currentLevel = 1
        activeBonuses.clear()
        extraBalls.clear()
        resetAllEffects()

        applyDifficultySettings()

        platformX = (screenWidth - platformWidth) / 2
        platformY = screenHeight - 150f * density

        resetBall()
        initLevel()

        handler.post {
            gameCallback?.onScoreUpdate(score)
            gameCallback?.onLivesUpdate(lives)
            gameCallback?.onLevelUpdate(currentLevel)
        }
    }

    private fun resetBall() {
        ballX = screenWidth / 2f
        ballY = screenHeight / 2f

        val levelSpeedMultiplier = 1f + (currentLevel - 1) * 0.08f
        val currentSpeed = baseSpeed * levelSpeedMultiplier

        ballSpeedX = currentSpeed * if (Random.nextBoolean()) 1 else -1
        ballSpeedY = currentSpeed

        isGameStarted = false
        ballPower = BallPower.NORMAL
        ballRadius = originalBallRadius
        extraBalls.clear()
    }

    fun pauseGame() {
        isPaused = true
        shouldResetOnSurfaceChange = false
        handler.post {
            gameCallback?.onPauseGame()
        }
    }

    fun resumeGame() {
        if (!isRunning) {
            isRunning = true
            isPaused = false
            gameThread = Thread(this)
            gameThread?.start()
        } else {
            isPaused = false
        }
        shouldResetOnSurfaceChange = false
    }

    fun cleanup() {
        isRunning = false
        isSurfaceCreated = false
        try {
            gameThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.d("BlockGame", "Cleanup interrupted")
        }
        gameThread = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
    }

    fun isGameOver(): Boolean {
        return isGameOver
    }

    fun isGameStarted(): Boolean {
        return isGameStarted
    }
}