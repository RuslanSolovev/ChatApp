package com.example.chatapp.pvo

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.random.Random

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private lateinit var player: Player
    private val missiles = mutableListOf<Missile>()
    private var lastUpdateTime = 0L
    private var gameOver = false
    private var gameTime = 0f

    // Система волн
    private var currentWave = 1
    private var missilesInWave = 1
    private var missilesSpawnedInWave = 0
    private var waveStartTime = 0f
    private var waveCompleted = true
    private var nextMissileSpawnTime = 0f
    private var score = 0
    private var bestScore = 0

    // Состояния игры
    private var inMenu = true
    private var inGame = false
    private var inInstructions = false
    private var gamePaused = false

    // Режим миссии
    private var inMissionMode = false
    private lateinit var missionPlane: MissionPlane
    private lateinit var worldMap: RealWorldMap
    private var missionScore = 0
    private var missionTime = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var combo = 0
    private var comboMultiplier = 1f
    private var lastHitTime = 0L
    private var missilesDestroyed = 0
    private var missionVideoCompleted = false

    // Отступ для игрового поля в dp
    private val gamePanelTopDp = 60f
    private var gamePanelTopPx = 0f
    private var gameAreaHeight = 0f

    // Колбэки
    private var onGameOverListener: ((Int) -> Unit)? = null
    private var onMissionSuccessListener: ((MissionData) -> Unit)? = null

    // Кисти
    private val backgroundPaint = Paint().apply {
        color = Color.BLACK
    }

    private val gameAreaPaint = Paint().apply {
        color = Color.BLACK
    }

    private val titlePaint = Paint().apply {
        color = Color.YELLOW
        textSize = dpToPx(24f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = dpToPx(14f)
        textAlign = Paint.Align.LEFT
    }

    private val smallTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = dpToPx(12f)
        textAlign = Paint.Align.LEFT
    }

    private val infoBackgroundPaint = Paint().apply {
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val buttonPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.FILL
    }

    private val missionButtonPaint = Paint().apply {
        color = Color.parseColor("#FF9800")
        style = Paint.Style.FILL
    }

    private val pauseButtonPaint = Paint().apply {
        color = Color.parseColor("#FF9800")
        style = Paint.Style.FILL
    }

    private val exitButtonPaint = Paint().apply {
        color = Color.parseColor("#F44336")
        style = Paint.Style.FILL
    }

    private val buttonTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = dpToPx(16f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val waveTextPaint = Paint().apply {
        color = Color.YELLOW
        textSize = dpToPx(20f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val pauseOverlayPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val explosions = mutableListOf<Explosion>()

    // Кнопки меню
    private val startButton = Rect()
    private val missionButton = Rect()
    private val instructionsButton = Rect()
    private val backButton = Rect()

    // Кнопки паузы
    private val pauseButton = Rect()
    private val resumeButton = Rect()
    private val exitButton = Rect()

    // Интерфейс для передачи данных миссии
    data class MissionData(
        val score: Int,
        val distance: Int,
        val missilesDestroyed: Int,
        val time: Float,
        val isSuccess: Boolean
    )

    init {
        startNewGame()
    }

    // Функция для конвертации dp в пиксели
    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        )
    }

    // Функция для получения размера в пикселях из dp
    private fun getPixelSize(dp: Float): Int {
        return dpToPx(dp).toInt()
    }

    fun setBestScore(score: Int) {
        bestScore = score
    }

    fun setOnGameOverListener(listener: (Int) -> Unit) {
        onGameOverListener = listener
    }

    fun setOnMissionSuccessListener(listener: (MissionData) -> Unit) {
        onMissionSuccessListener = listener
    }

    private fun centerPlayer() {
        if (width > 0 && height > 0) {
            val centerX = width / 2f
            val centerY = gamePanelTopPx + (height - gamePanelTopPx) / 2f
            player.position = Vector2D(centerX, centerY)
            player.targetPosition = Vector2D(centerX, centerY)
        }
    }

    private fun startNewGame() {
        player = Player(Vector2D(0f, 0f))
        missiles.clear()
        explosions.clear()
        gameOver = false
        gameTime = 0f
        currentWave = 1
        missilesInWave = 1
        missilesSpawnedInWave = 0
        waveStartTime = 0f
        waveCompleted = true
        nextMissileSpawnTime = 0f
        score = 0
        gamePaused = false
        lastUpdateTime = System.currentTimeMillis()

        centerPlayer()
    }

    private fun startNewMission() {
        missionPlane = MissionPlane(Vector2D(width / 2f, height * 0.7f), width, height)
        worldMap = RealWorldMap(context, width, height)
        missiles.clear()
        explosions.clear()
        gameOver = false
        missionScore = 0
        missionTime = 0f
        combo = 0
        comboMultiplier = 1f
        missilesDestroyed = 0
        lastHitTime = 0L
        missionVideoCompleted = false
        lastUpdateTime = System.currentTimeMillis()
        touchX = width / 2f
        touchY = height * 0.7f
    }

    private fun startGame() {
        inMenu = false
        inGame = true
        inMissionMode = false
        inInstructions = false
        gamePaused = false
        missionVideoCompleted = false
        startNewGame()
        centerPlayer()
        invalidate()
    }

    private fun startMission() {
        inMenu = false
        inGame = false
        inMissionMode = true
        inInstructions = false
        gamePaused = false
        missionVideoCompleted = false
        startNewMission()
        invalidate()
    }

    private fun showInstructions() {
        inMenu = false
        inGame = false
        inMissionMode = false
        inInstructions = true
        invalidate()
    }

    private fun backToMenu() {
        inMenu = true
        inGame = false
        inMissionMode = false
        inInstructions = false
        gamePaused = false
        missionVideoCompleted = false
        invalidate()
    }

    private fun togglePause() {
        if ((inGame || inMissionMode) && !gameOver) {
            gamePaused = !gamePaused
            if (!gamePaused) {
                lastUpdateTime = System.currentTimeMillis()
            }
            invalidate()
        }
    }

    private fun exitToMenu() {
        backToMenu()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        gamePanelTopPx = dpToPx(gamePanelTopDp)
        gameAreaHeight = h - gamePanelTopPx

        if (!::player.isInitialized) {
            startNewGame()
        }

        centerPlayer()

        // Инициализация кнопок меню
        val buttonWidth = getPixelSize(200f)
        val buttonHeight = getPixelSize(50f)
        val centerX = w / 2

        startButton.set(
            centerX - buttonWidth / 2,
            h / 2 + getPixelSize(20f),
            centerX + buttonWidth / 2,
            h / 2 + getPixelSize(20f) + buttonHeight
        )

        missionButton.set(
            centerX - buttonWidth / 2,
            h / 2 + getPixelSize(90f),
            centerX + buttonWidth / 2,
            h / 2 + getPixelSize(90f) + buttonHeight
        )

        instructionsButton.set(
            centerX - buttonWidth / 2,
            h / 2 + getPixelSize(160f),
            centerX + buttonWidth / 2,
            h / 2 + getPixelSize(160f) + buttonHeight
        )

        backButton.set(
            getPixelSize(20f),
            h - getPixelSize(70f),
            getPixelSize(120f),
            h - getPixelSize(20f)
        )

        // Инициализация кнопки паузы
        val pauseButtonSize = getPixelSize(40f)
        pauseButton.set(
            w - pauseButtonSize - getPixelSize(10f),
            getPixelSize(10f),
            w - getPixelSize(10f),
            getPixelSize(10f) + pauseButtonSize
        )

        // Инициализация кнопок меню паузы
        val pauseMenuButtonWidth = getPixelSize(180f)
        val pauseMenuButtonHeight = getPixelSize(45f)
        resumeButton.set(
            centerX - pauseMenuButtonWidth / 2,
            h / 2 - getPixelSize(40f),
            centerX + pauseMenuButtonWidth / 2,
            h / 2 - getPixelSize(40f) + pauseMenuButtonHeight
        )

        exitButton.set(
            centerX - pauseMenuButtonWidth / 2,
            h / 2 + getPixelSize(20f),
            centerX + pauseMenuButtonWidth / 2,
            h / 2 + getPixelSize(20f) + pauseMenuButtonHeight
        )

        // Инициализация миссии если нужно
        if (inMissionMode && ::missionPlane.isInitialized) {
            missionPlane.position = Vector2D(w / 2f, h * 0.7f)
            worldMap = RealWorldMap(context, w, h)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x.toInt()
                val y = event.y.toInt()

                if (inMenu) {
                    if (startButton.contains(x, y)) {
                        startGame()
                        return true
                    }
                    if (missionButton.contains(x, y)) {
                        startMission()
                        return true
                    }
                    if (instructionsButton.contains(x, y)) {
                        showInstructions()
                        return true
                    }
                } else if (inInstructions) {
                    if (backButton.contains(x, y)) {
                        backToMenu()
                        return true
                    }
                } else if (inGame) {
                    if (gameOver) {
                        backToMenu()
                        return true
                    } else if (gamePaused) {
                        if (resumeButton.contains(x, y)) {
                            togglePause()
                            return true
                        }
                        if (exitButton.contains(x, y)) {
                            exitToMenu()
                            return true
                        }
                    } else {
                        if (pauseButton.contains(x, y)) {
                            togglePause()
                            return true
                        }
                        val gameY = event.y.coerceIn(gamePanelTopPx, height.toFloat())
                        player.targetPosition = Vector2D(event.x, gameY)
                        return true
                    }
                } else if (inMissionMode) {
                    if (gameOver) {
                        // Если видео еще не показано, не обрабатываем нажатия
                        if (worldMap.isMissionComplete() && !missionVideoCompleted) {
                            return true
                        }
                        backToMenu()
                        return true
                    } else if (gamePaused) {
                        if (resumeButton.contains(x, y)) {
                            togglePause()
                            return true
                        }
                        if (exitButton.contains(x, y)) {
                            exitToMenu()
                            return true
                        }
                    } else {
                        if (pauseButton.contains(x, y)) {
                            togglePause()
                            return true
                        }
                        touchX = event.x
                        touchY = event.y
                        // Определяем направление движения
                        val directionX = when {
                            event.x < missionPlane.position.x - 40f -> -1f
                            event.x > missionPlane.position.x + 40f -> 1f
                            else -> 0f
                        }
                        val directionY = when {
                            event.y < missionPlane.position.y - 40f -> -1f
                            event.y > missionPlane.position.y + 40f -> 1f
                            else -> 0f
                        }
                        missionPlane.setMovement(directionX, directionY)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (inGame && !gameOver && !gamePaused) {
                    val gameY = event.y.coerceIn(gamePanelTopPx, height.toFloat())
                    player.targetPosition = Vector2D(event.x, gameY)
                    return true
                } else if (inMissionMode && !gameOver && !gamePaused) {
                    touchX = event.x
                    touchY = event.y
                    // Определяем направление движения
                    val directionX = when {
                        event.x < missionPlane.position.x - 40f -> -1f
                        event.x > missionPlane.position.x + 40f -> 1f
                        else -> 0f
                    }
                    val directionY = when {
                        event.y < missionPlane.position.y - 40f -> -1f
                        event.y > missionPlane.position.y + 40f -> 1f
                        else -> 0f
                    }
                    missionPlane.setMovement(directionX, directionY)
                    return true
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        if (inMenu) {
            drawMenu(canvas)
        } else if (inInstructions) {
            drawInstructions(canvas)
        } else if (inGame) {
            if (gameOver) {
                drawGameOver(canvas)
            } else {
                drawGame(canvas)
            }
        } else if (inMissionMode) {
            if (gameOver) {
                drawMissionGameOver(canvas)
            } else {
                drawMission(canvas)
            }
        }

        if ((inGame || inMissionMode) && !gameOver && !gamePaused) {
            if (inGame) {
                updateGame()
            } else if (inMissionMode) {
                updateMission()
            }
        } else {
            postInvalidateDelayed(16)
        }
    }

    private fun drawMenu(canvas: Canvas) {
        canvas.drawText("ПВО ЗАЩИТА", width / 2f, height / 4f, titlePaint)
        canvas.drawText("Лучший результат: $bestScore", width / 2f, height / 4f + dpToPx(30f), textPaint.apply { textAlign = Paint.Align.CENTER })

        // Кнопка "Начать игру"
        canvas.drawRoundRect(
            startButton.left.toFloat(), startButton.top.toFloat(),
            startButton.right.toFloat(), startButton.bottom.toFloat(),
            dpToPx(8f), dpToPx(8f), buttonPaint
        )
        canvas.drawText("НАЧАТЬ ИГРУ", startButton.centerX().toFloat(), startButton.centerY().toFloat() + dpToPx(5f), buttonTextPaint)

        // Кнопка "Миссия"
        canvas.drawRoundRect(
            missionButton.left.toFloat(), missionButton.top.toFloat(),
            missionButton.right.toFloat(), missionButton.bottom.toFloat(),
            dpToPx(8f), dpToPx(8f), missionButtonPaint
        )
        canvas.drawText("ДЛИННАЯ МИССИЯ", missionButton.centerX().toFloat(), missionButton.centerY().toFloat() + dpToPx(5f), buttonTextPaint)

        // Кнопка "Инструкция"
        canvas.drawRoundRect(
            instructionsButton.left.toFloat(), instructionsButton.top.toFloat(),
            instructionsButton.right.toFloat(), instructionsButton.bottom.toFloat(),
            dpToPx(8f), dpToPx(8f), buttonPaint
        )
        canvas.drawText("ИНСТРУКЦИЯ", instructionsButton.centerX().toFloat(), instructionsButton.centerY().toFloat() + dpToPx(5f), buttonTextPaint)

        canvas.drawText("Коснитесь экрана для управления", width / 2f, height - dpToPx(40f), smallTextPaint.apply { textAlign = Paint.Align.CENTER })
    }

    private fun updateMission() {
        val currentTime = System.currentTimeMillis()
        val deltaTime = ((currentTime - lastUpdateTime) / 1000f).coerceAtMost(0.1f)
        lastUpdateTime = currentTime

        if (gameOver || gamePaused) return

        missionTime += deltaTime

        // Обновляем карту
        worldMap.update(deltaTime)

        // Обновляем самолет
        missionPlane.update(deltaTime)

        // Обновляем комбо-систему
        updateCombo(deltaTime)

        // Генерация ракет ПВО
        generatePVOMissiles(deltaTime)

        // Создаем копии для безопасной обработки
        val currentMissiles = missiles.toList()
        val missilesToRemove = mutableListOf<Missile>()
        val explosionsToAdd = mutableListOf<Vector2D>()

        // Шаг 1: Обновить все ракеты и проверить границы
        for (missile in currentMissiles) {
            missile.update(deltaTime)

            if (missile.position.y < -150f ||
                missile.position.x < -150f ||
                missile.position.x > width + 150f ||
                missile.position.y > height + 150f ||
                missile.shouldDestroy()) {

                missilesToRemove.add(missile)

                if (missile.shouldDestroy() && missile.position.distanceTo(missionPlane.position) > 100f) {
                    addCombo()
                }
            }
        }

        // Шаг 2: Проверяем столкновения между ракетами
        for (i in currentMissiles.indices) {
            val missile1 = currentMissiles[i]
            if (missilesToRemove.contains(missile1)) continue

            for (j in i + 1 until currentMissiles.size) {
                val missile2 = currentMissiles[j]
                if (missilesToRemove.contains(missile2)) continue

                if (missile1.isCollidingWith(missile2)) {
                    missilesToRemove.add(missile1)
                    missilesToRemove.add(missile2)

                    val explosionPos = Vector2D(
                        (missile1.position.x + missile2.position.x) / 2f,
                        (missile1.position.y + missile2.position.y) / 2f
                    )
                    explosionsToAdd.add(explosionPos)

                    missionScore += 50 * comboMultiplier.toInt()
                    addCombo()
                    break
                }
            }
        }

        // Шаг 3: Проверяем столкновения с самолетом
        for (missile in currentMissiles) {
            if (!missilesToRemove.contains(missile) && missionPlane.isCollidingWith(missile)) {
                val damage = when (missile.type) {
                    MissileType.STANDARD -> 15
                    MissileType.FAST -> 20
                    MissileType.ZIGZAG -> 18
                    MissileType.HOMING -> 25
                    MissileType.HEAVY -> 30
                    MissileType.SPLITTING -> 10
                    MissileType.TELEPORTING -> 22
                    MissileType.SNIPER -> 35
                    MissileType.MIRROR -> 20
                }

                missionPlane.takeDamage(damage)
                missilesToRemove.add(missile)

                combo = 0
                comboMultiplier = 1f
                missilesDestroyed++

                if (!missionPlane.isAlive()) {
                    missionPlane.isDestroyed = true
                    gameOver = true

                    // СОЗДАЕМ ДАННЫЕ ДЛЯ ПРОВАЛА МИССИИ
                    val missionData = MissionData(
                        score = missionScore,
                        distance = worldMap.getTotalDistance(),
                        missilesDestroyed = missilesDestroyed,
                        time = missionTime,
                        isSuccess = false // ПРОВАЛ
                    )

                    // ЗАПУСКАЕМ ВИДЕО ПРОВАЛА
                    onMissionSuccessListener?.invoke(missionData)

                    // НЕ вызываем onGameOverListener здесь
                    invalidate()
                    return
                }
            }
        }

        // Удаляем все помеченные ракеты
        missiles.removeAll(missilesToRemove)

        // Добавляем взрывы
        explosionsToAdd.forEach { createExplosion(it) }

        // Обновляем взрывы
        updateExplosions(deltaTime)

        // Начисляем очки за выживание
        missionScore += (deltaTime * 1.5f * comboMultiplier).toInt()

        // Проверяем завершение миссии
        if (worldMap.isMissionComplete()) {
            missionCompleted()
        }

        invalidate()
    }

    private fun missionCompleted() {
        // Большие бонусы за завершение
        val distanceBonus = worldMap.getTotalDistance() * 10
        val timeBonus = (missionTime * 5).toInt()
        val comboBonus = missilesDestroyed * 50
        val completionBonus = 100000 // Основной бонус за завершение

        missionScore += (distanceBonus + timeBonus + comboBonus + completionBonus).toInt()

        // Создаем данные миссии
        val missionData = MissionData(
            score = missionScore,
            distance = worldMap.getTotalDistance(),
            missilesDestroyed = missilesDestroyed,
            time = missionTime,
            isSuccess = true // УСПЕХ
        )

        // Уведомляем слушателя об успешном прохождении
        onMissionSuccessListener?.invoke(missionData)

        // Устанавливаем gameOver, но НЕ вызываем onGameOverListener пока не покажем видео
        gameOver = true

        // Не вызываем invalidate() здесь - Activity запустит видео
    }

    // Метод для обновления статуса после просмотра видео
    fun onVideoCompleted() {
        missionVideoCompleted = true

        // Перерисовываем экран для показа статистики
        invalidate()
    }

    // Дополнительный метод для обработки провала миссии
    fun onMissionFailed(score: Int) {
        // Вызываем onGameOverListener для сохранения счета провала
        onGameOverListener?.invoke(score)
    }

    private fun createExplosion(position: Vector2D) {
        explosions.add(Explosion(position))
    }

    private fun updateExplosions(deltaTime: Float) {
        val iterator = explosions.iterator()
        while (iterator.hasNext()) {
            val explosion = iterator.next()
            if (explosion.update(deltaTime)) {
                iterator.remove()
            }
        }
    }

    private fun drawExplosions(canvas: Canvas) {
        explosions.forEach { it.draw(canvas) }
    }

    private fun updateCombo(deltaTime: Float) {
        // Комбо сбрасывается если долго не было уклонений
        val timeSinceLastHit = System.currentTimeMillis() - lastHitTime
        if (timeSinceLastHit > 5000) { // 5 секунд
            combo = 0
            comboMultiplier = 1f
        }
    }

    private fun addCombo() {
        combo++
        lastHitTime = System.currentTimeMillis()

        // Множитель растет логарифмически
        comboMultiplier = 1f + (combo.toFloat() / 10f)
        if (comboMultiplier > 5f) comboMultiplier = 5f

        // Бонусные очки за комбо
        if (combo % 5 == 0) {
            val bonus = 100 * combo
            missionScore += bonus
        }
    }

    private fun generatePVOMissiles(deltaTime: Float) {
        val pvoSystem = worldMap.getPVOForCurrentCountry() ?: return

        // ДИНАМИЧЕСКАЯ частота: зависит от прогресса по стране
        val countryProgress = worldMap.getCountryProgress()
        val intensity = when {
            countryProgress < 0.2f -> 0.7f  // Начало страны - мало ракет
            countryProgress < 0.8f -> 1.0f  // Середина - нормально
            else -> 1.3f                     // Конец страны - больше ракет
        }

        val dynamicSpawnRate = pvoSystem.spawnRate * intensity

        if (Random.nextFloat() < dynamicSpawnRate * deltaTime * 20f) {
            // Ракеты появляются со всех сторон
            val side = Random.nextInt(0, 4)
            val startPosition = when (side) {
                0 -> Vector2D(-100f, Random.nextInt(100, height - 100).toFloat()) // слева
                1 -> Vector2D(width + 100f, Random.nextInt(100, height - 100).toFloat()) // справа
                2 -> Vector2D(Random.nextInt(100, width - 100).toFloat(), -100f) // сверху
                else -> Vector2D(Random.nextInt(100, width - 100).toFloat(), height + 100f) // снизу
            }

            // Выбираем случайный тип ракеты
            val missileType = pvoSystem.missileTypes.random()

            val missile = Missile(
                startPosition,
                missionPlane,
                missileType,
                this
            )

            // Направляем ракету с предсказанием движения
            val predictionTime = 0.8f + Random.nextFloat() * 0.4f
            val predictedPosition = Vector2D(
                missionPlane.position.x + missionPlane.velocity.x * predictionTime,
                missionPlane.position.y + missionPlane.velocity.y * predictionTime
            )

            // Добавляем немного случайности
            val randomOffset = Vector2D(
                Random.nextFloat() * 200f - 100f,
                Random.nextFloat() * 200f - 100f
            )

            val direction = (predictedPosition + randomOffset) - missile.position
            val speedMultiplier = 0.6f + (countryProgress * 0.4f) // Быстрее к концу страны
            missile.velocity = direction.normalize() * missileType.speed * speedMultiplier

            missiles.add(missile)
        }
    }

    private fun drawInstructions(canvas: Canvas) {
        canvas.drawText("ДЛИННАЯ МИССИЯ", width / 2f, dpToPx(50f), titlePaint)

        var yOffset = dpToPx(90f)
        val lineHeight = dpToPx(18f)

        val instructionTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = dpToPx(14f)
            textAlign = Paint.Align.LEFT
        }

        val screenPadding = dpToPx(20f)
        val maxWidth = width - 2 * screenPadding

        val instructions = listOf(
            "🎯 ЦЕЛЬ: Пролететь из России в США через системы ПВО",
            "⏱️ ДЛИТЕЛЬНОСТЬ: ~2 минуты на каждую страну",
            "✈️ УПРАВЛЕНИЕ: Двигайте самолет во всех направлениях",
            "💥 СИСТЕМЫ ПВО: Каждая страна имеет свою систему",
            "🇷🇺 Россия - С-400 (тяжелые ракеты)",
            "🇵🇱 Польша - Patriot (быстрые ракеты)",
            "🇩🇪 Германия - IRIS-T (умные ракеты)",
            "🇺🇸 США - THAAD (самые опасные)",
            "❤️ ЗДОРОВЬЕ: Избегайте ракет для выживания",
            "⭐ КОМБО: Уклоняйтесь от ракет для получения множителя"
        )

        instructions.forEach { instruction ->
            val lines = breakTextIntoLines(instruction, instructionTextPaint, maxWidth)
            lines.forEach { line ->
                canvas.drawText(line, screenPadding, yOffset, instructionTextPaint)
                yOffset += lineHeight
            }
            yOffset += dpToPx(4f)
        }

        yOffset += dpToPx(15f)
        val goalPaint = Paint().apply {
            color = Color.YELLOW
            textSize = dpToPx(16f)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        canvas.drawText("Пройдите 8 стран за 15+ минут!", width / 2f, yOffset, goalPaint)
        yOffset += dpToPx(25f)
        canvas.drawText("Награда за успешное завершение: 100,000 очков", width / 2f, yOffset, instructionTextPaint.apply { textAlign = Paint.Align.CENTER })

        canvas.drawRoundRect(
            backButton.left.toFloat(), backButton.top.toFloat(),
            backButton.right.toFloat(), backButton.bottom.toFloat(),
            dpToPx(8f), dpToPx(8f), buttonPaint
        )
        canvas.drawText("НАЗАД", backButton.centerX().toFloat(), backButton.centerY().toFloat() + dpToPx(5f), buttonTextPaint)
    }

    private fun breakTextIntoLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var currentLine = ""

        for (word in text.split(" ")) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) <= maxWidth) {
                currentLine = testLine
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                }
                currentLine = word
            }
        }

        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }

        return lines
    }

    private fun drawGameOver(canvas: Canvas) {
        canvas.drawText("ИГРА ОКОНЧЕНА", width / 2f, height / 2f - dpToPx(40f), titlePaint)
        canvas.drawText("Волна: $currentWave", width / 2f, height / 2f - dpToPx(10f), textPaint.apply { textAlign = Paint.Align.CENTER })
        canvas.drawText("Очки: $score", width / 2f, height / 2f + dpToPx(10f), textPaint.apply { textAlign = Paint.Align.CENTER })
        canvas.drawText("Лучший результат: $bestScore", width / 2f, height / 2f + dpToPx(30f), textPaint.apply { textAlign = Paint.Align.CENTER })
        canvas.drawText("Коснитесь для возврата в меню", width / 2f, height / 2f + dpToPx(60f), smallTextPaint.apply { textAlign = Paint.Align.CENTER })
    }

    private fun drawMissionGameOver(canvas: Canvas) {
        canvas.drawColor(Color.argb(200, 0, 0, 0))

        val titlePaint = Paint().apply {
            color = if (worldMap.isMissionComplete()) Color.GREEN else Color.RED
            textSize = dpToPx(24f)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = dpToPx(16f)
            textAlign = Paint.Align.CENTER
        }

        val centerX = width / 2f
        var yOffset = height / 3f

        if (worldMap.isMissionComplete()) {
            canvas.drawText("МИССИЯ ВЫПОЛНЕНА!", centerX, yOffset, titlePaint)
            yOffset += dpToPx(30f)

            // Проверяем, показано ли уже видео
            if (missionVideoCompleted) {
                canvas.drawText("Поздравляем! Вы успешно достигли США!", centerX, yOffset, textPaint)
            } else {
                canvas.drawText("Загрузка результатов...", centerX, yOffset, textPaint)
            }
        } else {
            canvas.drawText("МИССИЯ ПРОВАЛЕНА", centerX, yOffset, titlePaint)
            yOffset += dpToPx(30f)
            canvas.drawText("Ваш самолет был сбит", centerX, yOffset, textPaint)
        }

        yOffset += dpToPx(40f)
        canvas.drawText("Пролетено: ${worldMap.getTotalDistance()} км", centerX, yOffset, textPaint)
        yOffset += dpToPx(20f)
        canvas.drawText("Достигнуто: ${worldMap.getCurrentCountry()}", centerX, yOffset, textPaint)
        yOffset += dpToPx(20f)
        canvas.drawText("Прогресс: ${worldMap.getProgress().toInt()}%", centerX, yOffset, textPaint)
        yOffset += dpToPx(20f)
        canvas.drawText("Уничтожено ракет: $missilesDestroyed", centerX, yOffset, textPaint)
        yOffset += dpToPx(20f)
        canvas.drawText("Очки: $missionScore", centerX, yOffset, textPaint)

        // Только если видео уже показано, показываем кнопку возврата
        if (missionVideoCompleted || !worldMap.isMissionComplete()) {
            yOffset += dpToPx(40f)
            canvas.drawText("Коснитесь для возврата в меню", centerX, yOffset, textPaint)
        } else if (worldMap.isMissionComplete()) {
            yOffset += dpToPx(40f)
            canvas.drawText("Пожалуйста, подождите...", centerX, yOffset, textPaint)
        }
    }

    private fun drawGame(canvas: Canvas) {
        canvas.drawRect(0f, gamePanelTopPx, width.toFloat(), height.toFloat(), gameAreaPaint)

        if (gameTime - waveStartTime < 2f && !waveCompleted && !gamePaused) {
            val alpha = (1f - (gameTime - waveStartTime) / 2f).coerceIn(0f, 1f)
            waveTextPaint.alpha = (alpha * 255).toInt()
            canvas.drawText("ВОЛНА $currentWave", width / 2f, gamePanelTopPx + dpToPx(40f), waveTextPaint)
        }

        player.draw(canvas)

        // Рисуем ракеты
        missiles.forEach { it.draw(canvas) }

        // Рисуем взрывы
        drawExplosions(canvas)

        drawGameUI(canvas)

        if (!gamePaused) {
            drawPauseButton(canvas)
        }

        if (gamePaused) {
            drawPauseMenu(canvas)
        }

        val linePaint = Paint().apply {
            color = Color.GRAY
            strokeWidth = dpToPx(1f)
        }
        canvas.drawLine(0f, gamePanelTopPx, width.toFloat(), gamePanelTopPx, linePaint)
    }

    private fun drawMission(canvas: Canvas) {
        // Рисуем карту мира
        worldMap.draw(canvas)

        // Рисуем взрывы (под ракетами)
        drawExplosions(canvas)

        // Рисуем ракеты ПВО
        missiles.forEach { it.draw(canvas) }

        // Рисуем самолет игрока поверх всего
        missionPlane.draw(canvas)

        // Рисуем UI миссии
        drawMissionUI(canvas)

        if (!gamePaused) {
            drawPauseButton(canvas)
        }

        if (gamePaused) {
            drawPauseMenu(canvas)
        }
    }

    private fun drawMissionUI(canvas: Canvas) {
        val padding = dpToPx(10f)
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = dpToPx(16f)
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val infoPaint = Paint().apply {
            color = Color.YELLOW
            textSize = dpToPx(18f)
            textAlign = Paint.Align.LEFT
            isFakeBoldText = true
        }

        var yOffset = padding

        canvas.drawText("ДЛИННАЯ МИССИЯ", padding, yOffset, infoPaint)
        yOffset += dpToPx(25f)
        canvas.drawText("${worldMap.getCurrentCountry()}", padding, yOffset, textPaint)
        yOffset += dpToPx(20f)
        canvas.drawText("ПВО: ${worldMap.getCountryPVOSystem()}", padding, yOffset, textPaint)
        yOffset += dpToPx(20f)

        // Прогресс по текущей стране
        val countryProgress = worldMap.getCountryProgress()
        val countryTime = worldMap.getCurrentCountryTime()
        canvas.drawText("По стране: ${(countryProgress * 100).toInt()}% ($countryTime)", padding, yOffset, textPaint)
        yOffset += dpToPx(20f)

        canvas.drawText("Дистанция: ${worldMap.getTotalDistance()} км", padding, yOffset, textPaint)
        yOffset += dpToPx(20f)
        canvas.drawText("Очки: $missionScore", padding, yOffset, textPaint)
        yOffset += dpToPx(20f)
        canvas.drawText("Уничтожено: $missilesDestroyed", padding, yOffset, textPaint)

        // Комбо-система
        if (combo > 0) {
            yOffset += dpToPx(25f)
            val comboPaint = Paint().apply {
                color = Color.parseColor("#FFD700")
                textSize = dpToPx(20f)
                textAlign = Paint.Align.LEFT
                isFakeBoldText = true
            }
            canvas.drawText("КОМБО x$combo! (x${"%.1f".format(comboMultiplier)})", padding, yOffset, comboPaint)
        }

        // Прогресс всей миссии
        val progress = worldMap.getProgress()
        val progressBarWidth = width * 0.8f
        val progressBarHeight = dpToPx(12f)
        val progressBarX = (width - progressBarWidth) / 2
        val progressBarY = height - dpToPx(60f)

        val backgroundPaint = Paint().apply {
            color = Color.GRAY
            style = Paint.Style.FILL
        }

        val progressPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.FILL
        }

        val borderPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        canvas.drawRect(progressBarX, progressBarY, progressBarX + progressBarWidth, progressBarY + progressBarHeight, backgroundPaint)
        canvas.drawRect(progressBarX, progressBarY, progressBarX + progressBarWidth * (progress / 100f), progressBarY + progressBarHeight, progressPaint)
        canvas.drawRect(progressBarX, progressBarY, progressBarX + progressBarWidth, progressBarY + progressBarHeight, borderPaint)

        // Информация о прогрессе
        val progressText = "Прогресс: ${progress.toInt()}%"
        val countryIndex = worldMap.getCountryIndex() + 1
        val countryCount = worldMap.getCountryCount()
        val countriesText = "Страна $countryIndex/$countryCount"

        canvas.drawText(progressText, progressBarX + progressBarWidth / 2, progressBarY - dpToPx(5f), textPaint.apply {
            textAlign = Paint.Align.CENTER
            color = Color.WHITE
        })

        canvas.drawText(countriesText, progressBarX + progressBarWidth / 2, progressBarY - dpToPx(25f), textPaint.apply {
            textAlign = Paint.Align.CENTER
            color = Color.LTGRAY
        })

        // Оставшееся время и общее время
        val timeRemaining = worldMap.getEstimatedTimeRemaining()
        val totalTime = worldMap.getTotalMissionTime()

        canvas.drawText("Осталось: $timeRemaining", width / 2f, progressBarY + progressBarHeight + dpToPx(20f), textPaint.apply {
            textAlign = Paint.Align.CENTER
            color = Color.LTGRAY
        })

        canvas.drawText("Всего: $totalTime", width / 2f, progressBarY + progressBarHeight + dpToPx(40f), textPaint.apply {
            textAlign = Paint.Align.CENTER
            color = Color.LTGRAY
            textSize = dpToPx(14f)
        })

        // Подсказка управления
        val hintPaint = Paint().apply {
            color = Color.LTGRAY
            textSize = dpToPx(12f)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("←→↑↓ Двигайте самолет для уклонения от ракет", width / 2f, height - dpToPx(10f), hintPaint)
    }

    private fun drawPauseButton(canvas: Canvas) {
        canvas.drawRoundRect(
            pauseButton.left.toFloat(), pauseButton.top.toFloat(),
            pauseButton.right.toFloat(), pauseButton.bottom.toFloat(),
            dpToPx(6f), dpToPx(6f), pauseButtonPaint
        )

        val symbolPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        val symbolWidth = dpToPx(4f)
        val symbolHeight = dpToPx(12f)
        val centerX = pauseButton.centerX().toFloat()
        val centerY = pauseButton.centerY().toFloat()

        canvas.drawRect(
            centerX - symbolWidth - dpToPx(2f),
            centerY - symbolHeight / 2,
            centerX - dpToPx(2f),
            centerY + symbolHeight / 2,
            symbolPaint
        )

        canvas.drawRect(
            centerX + dpToPx(2f),
            centerY - symbolHeight / 2,
            centerX + symbolWidth + dpToPx(2f),
            centerY + symbolHeight / 2,
            symbolPaint
        )
    }

    private fun drawPauseMenu(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pauseOverlayPaint)

        val modeText = if (inMissionMode) "МИССИЯ - ПАУЗА" else "ПАУЗА"
        canvas.drawText(modeText, width / 2f, height / 3f, titlePaint)

        canvas.drawRoundRect(
            resumeButton.left.toFloat(), resumeButton.top.toFloat(),
            resumeButton.right.toFloat(), resumeButton.bottom.toFloat(),
            dpToPx(8f), dpToPx(8f), buttonPaint
        )
        canvas.drawText("ПРОДОЛЖИТЬ", resumeButton.centerX().toFloat(), resumeButton.centerY().toFloat() + dpToPx(5f), buttonTextPaint)

        canvas.drawRoundRect(
            exitButton.left.toFloat(), exitButton.top.toFloat(),
            exitButton.right.toFloat(), exitButton.bottom.toFloat(),
            dpToPx(8f), dpToPx(8f), exitButtonPaint
        )
        canvas.drawText("ВЫЙТИ", exitButton.centerX().toFloat(), exitButton.centerY().toFloat() + dpToPx(5f), buttonTextPaint)

        val infoText = if (inMissionMode) {
            "Страна: ${worldMap.getCurrentCountry()} | Прогресс: ${worldMap.getProgress().toInt()}%"
        } else {
            "Волна: $currentWave | Очки: $score"
        }
        canvas.drawText(infoText, width / 2f, height / 3f + dpToPx(30f), textPaint.apply {
            textAlign = Paint.Align.CENTER
            color = Color.LTGRAY
        })
    }

    private fun drawGameUI(canvas: Canvas) {
        val padding = dpToPx(5f)

        canvas.drawRect(0f, 0f, width.toFloat(), gamePanelTopPx, infoBackgroundPaint)

        var yOffset = padding + dpToPx(12f)

        canvas.drawText("Волна $currentWave", padding, yOffset, textPaint)

        canvas.drawText("$score очков", width / 2f, yOffset, textPaint.apply {
            textAlign = Paint.Align.CENTER
        })

        val rightPadding = padding + dpToPx(50f)
        canvas.drawText("${missiles.size} шт", width - rightPadding, yOffset, textPaint.apply {
            textAlign = Paint.Align.RIGHT
        })

        yOffset += dpToPx(18f)

        val progress = if (missilesInWave > 0) {
            (missilesSpawnedInWave.toFloat() / missilesInWave * 100).toInt()
        } else {
            100
        }
        canvas.drawText("$progress%", width / 2f, yOffset, smallTextPaint.apply {
            textAlign = Paint.Align.CENTER
            color = Color.LTGRAY
        })

        val activeTypes = missiles.distinctBy { it.getTypeName() }.size
        if (activeTypes > 0) {
            canvas.drawText("Типы: $activeTypes", width - rightPadding, yOffset, smallTextPaint.apply {
                textAlign = Paint.Align.RIGHT
                color = Color.LTGRAY
            })
        }
    }

    private fun updateGame() {
        val currentTime = System.currentTimeMillis()
        val deltaTime = ((currentTime - lastUpdateTime) / 1000f).coerceAtMost(0.1f)
        lastUpdateTime = currentTime

        if (gameOver || gamePaused) return

        gameTime += deltaTime

        if (waveCompleted) {
            startNextWave()
        }

        if (!waveCompleted && missilesSpawnedInWave < missilesInWave) {
            if (gameTime >= nextMissileSpawnTime) {
                addMissile()
                missilesSpawnedInWave++
                nextMissileSpawnTime = gameTime + 1.2f
            }
        }

        if (missiles.isEmpty() && missilesSpawnedInWave >= missilesInWave && !waveCompleted) {
            completeWave()
        }

        player.update(deltaTime)

        // Обновляем ракеты и собираем те, что нужно удалить
        val missilesToRemove = mutableListOf<Missile>()
        val currentMissiles = missiles.toList()

        for (missile in currentMissiles) {
            missile.update(deltaTime)

            if (missile.position.x < -100f || missile.position.x > width + 100f ||
                missile.position.y < gamePanelTopPx - 100f || missile.position.y > height + 100f ||
                missile.shouldDestroy()) {
                missilesToRemove.add(missile)
            }
        }

        // Удаляем ракеты
        missiles.removeAll(missilesToRemove)

        // Проверка коллизий
        for (missile in currentMissiles) {
            if (!missilesToRemove.contains(missile) && player.isCollidingWith(missile)) {
                gameOver = true
                onGameOverListener?.invoke(score)
                break
            }
        }

        invalidate()
    }

    private fun startNextWave() {
        waveCompleted = false
        waveStartTime = gameTime
        missilesSpawnedInWave = 0
        nextMissileSpawnTime = gameTime + 1f

        missilesInWave = currentWave
        score += currentWave * 10
    }

    private fun completeWave() {
        waveCompleted = true
        currentWave++
        score += currentWave * 100
    }

    fun addChildMissile(missile: Missile) {
        missiles.add(missile)
    }

    private fun addMissile() {
        val side = Random.nextInt(0, 4)
        val startPosition = when (side) {
            0 -> Vector2D(-50f, Random.nextInt(gamePanelTopPx.toInt(), height - 50).toFloat())
            1 -> Vector2D(width + 50f, Random.nextInt(gamePanelTopPx.toInt(), height - 50).toFloat())
            2 -> Vector2D(Random.nextInt(50, width - 50).toFloat(), gamePanelTopPx - 50f)
            else -> Vector2D(Random.nextInt(50, width - 50).toFloat(), height + 50f)
        }

        val missileType = when (currentWave) {
            1 -> MissileType.STANDARD
            2 -> if (Random.nextBoolean()) MissileType.STANDARD else MissileType.FAST
            3 -> when (Random.nextInt(0, 100)) {
                in 0..40 -> MissileType.STANDARD
                in 41..70 -> MissileType.FAST
                else -> MissileType.ZIGZAG
            }
            4 -> when (Random.nextInt(0, 100)) {
                in 0..30 -> MissileType.STANDARD
                in 31..55 -> MissileType.FAST
                in 56..75 -> MissileType.ZIGZAG
                in 76..85 -> MissileType.HOMING
                else -> MissileType.SPLITTING
            }
            5 -> when (Random.nextInt(0, 100)) {
                in 0..25 -> MissileType.STANDARD
                in 26..45 -> MissileType.FAST
                in 46..60 -> MissileType.ZIGZAG
                in 61..75 -> MissileType.HOMING
                in 76..85 -> MissileType.SPLITTING
                else -> MissileType.TELEPORTING
            }
            6 -> when (Random.nextInt(0, 100)) {
                in 0..20 -> MissileType.STANDARD
                in 21..40 -> MissileType.FAST
                in 41..55 -> MissileType.ZIGZAG
                in 56..70 -> MissileType.HOMING
                in 71..80 -> MissileType.SPLITTING
                in 81..88 -> MissileType.TELEPORTING
                else -> MissileType.SNIPER
            }
            7 -> when (Random.nextInt(0, 100)) {
                in 0..15 -> MissileType.STANDARD
                in 16..30 -> MissileType.FAST
                in 31..45 -> MissileType.ZIGZAG
                in 46..60 -> MissileType.HOMING
                in 61..70 -> MissileType.SPLITTING
                in 71..80 -> MissileType.TELEPORTING
                in 81..88 -> MissileType.SNIPER
                else -> MissileType.MIRROR
            }
            else -> when (Random.nextInt(0, 100)) {
                in 0..10 -> MissileType.STANDARD
                in 11..25 -> MissileType.FAST
                in 26..40 -> MissileType.ZIGZAG
                in 41..55 -> MissileType.HOMING
                in 56..65 -> MissileType.HEAVY
                in 66..75 -> MissileType.SPLITTING
                in 76..83 -> MissileType.TELEPORTING
                in 84..90 -> MissileType.SNIPER
                else -> MissileType.MIRROR
            }
        }

        missiles.add(Missile(startPosition, player, missileType, this))
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
}