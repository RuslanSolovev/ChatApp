package com.example.chatapp.igra_strotegiy

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.R
import com.example.chatapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

class MultiplayerGameActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private lateinit var multiplayerLogic: MultiplayerGameLogic
    private lateinit var tvGameStatus: TextView
    private lateinit var tvCurrentPlayer: TextView
    private lateinit var mapContainer: FrameLayout
    private lateinit var btnBuild: Button
    private lateinit var btnEndTurn: Button
    private lateinit var btnLeaveGame: Button
    private var currentGame: MultiplayerGame? = null
    private var currentUser: User? = null
    private var isSpectator = false
    private val gameId by lazy { intent.getStringExtra("GAME_ID") ?: "" }
    private var selectedBuilding: Building? = null
    private lateinit var tvResources: TextView
    private lateinit var tvUnits: TextView
    private lateinit var tvPlayersSummary: TextView
    private var isRendering = false
    private var lastMapUpdate = 0L
    private var lastSharedMapHash = 0
    private var lastUpdate = 0L
    private var selectedArmy: Army? = null

    // Управление слушателями
    private var gameListener: ValueEventListener? = null
    private var playersListener: ValueEventListener? = null
    private var mapListener: ValueEventListener? = null

    // 🔥 ДЛЯ ВИДЕО СРАЖЕНИЙ
    private var currentBattlePreview: BattlePreview? = null

    companion object {
        private const val TAG = "MultiplayerGameActivity"
        private const val MAP_UPDATE_THROTTLE = 300L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multiplayer_game)
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().reference
        multiplayerLogic = MultiplayerGameLogic(database)
        if (gameId.isEmpty()) {
            showErrorAndFinish("Ошибка: ID игры не получен")
            return
        }
        initViews()
        showLoadingState()
        lifecycleScope.launch { loadGameDataAsync() }
        setupPlayersListener()
    }

    private fun initViews() {
        tvGameStatus = findViewById(R.id.tvGameStatus)
        tvCurrentPlayer = findViewById(R.id.tvCurrentPlayer)
        mapContainer = findViewById(R.id.mapContainer)
        btnBuild = findViewById(R.id.btnBuild)
        btnEndTurn = findViewById(R.id.btnEndTurn)
        btnLeaveGame = findViewById(R.id.btnLeaveGame)
        tvResources = findViewById(R.id.tvResources)
        tvUnits = findViewById(R.id.tvUnits)
        tvPlayersSummary = findViewById(R.id.tvPlayersSummary)
        btnBuild.setOnClickListener { showBuildingMenu() }
        btnEndTurn.setOnClickListener { endTurn() }
        btnLeaveGame.setOnClickListener { leaveGame() }
        btnBuild.visibility = View.GONE
        btnEndTurn.visibility = View.GONE
    }

    private fun showLoadingState() {
        tvGameStatus.text = "Загрузка игры..."
        tvCurrentPlayer.text = "Загрузка..."
        tvResources.text = "Ресурсы: загрузка..."
        tvUnits.text = "Юниты: загрузка..."
        tvPlayersSummary.text = "0/0"
    }

    private suspend fun loadGameDataAsync() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Начало асинхронной загрузки данных игры")
            val uid = auth.currentUser?.uid
            val isSpectator = determineSpectatorStatus(uid)
            withContext(Dispatchers.Main) {
                this@MultiplayerGameActivity.isSpectator = isSpectator
                updateUIForSpectator()
                setupGameListener()
            }
            loadCurrentUserAsync()
            Log.d(TAG, "Асинхронная загрузка данных завершена")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки данных игры", e)
            withContext(Dispatchers.Main) {
                showErrorAndFinish("Ошибка загрузки игры: ${e.message}")
            }
        }
    }

    private suspend fun determineSpectatorStatus(uid: String?): Boolean = withContext(Dispatchers.IO) {
        if (uid == null) return@withContext true
        try {
            val snapshot = database.child("multiplayer_games").child(gameId).child("players").child(uid).get().await()
            !snapshot.exists()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка определения статуса наблюдателя", e)
            true
        }
    }

    private suspend fun loadCurrentUserAsync() = withContext(Dispatchers.IO) {
        try {
            val uid = auth.currentUser?.uid ?: return@withContext
            val snapshot = database.child("users").child(uid).get().await()
            currentUser = snapshot.getValue(User::class.java)
            Log.d(TAG, "Данные пользователя загружены")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки данных пользователя", e)
        }
    }

    private fun updateUIForSpectator() {
        if (isSpectator) {
            btnBuild.visibility = View.GONE
            btnEndTurn.visibility = View.GONE
            tvCurrentPlayer.text = "Наблюдатель"
        } else {
            btnBuild.visibility = View.VISIBLE
            btnEndTurn.visibility = View.VISIBLE
        }
    }

    private fun setupGameListener() {
        gameListener?.let { database.child("multiplayer_games").child(gameId).removeEventListener(it) }
        gameListener = database.child("multiplayer_games").child(gameId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate < 200) return
                    lastUpdate = now
                    lifecycleScope.launch {
                        try {
                            val oldGame = currentGame
                            currentGame = FirebaseGameMapper.safeGetMultiplayerGame(snapshot)
                            currentGame?.let { newGame ->
                                withContext(Dispatchers.Main) {
                                    updateGameUI(newGame)
                                    if (oldGame != null && playersDataChanged(oldGame, newGame)) {
                                        Log.d(TAG, "Обнаружено изменение данных игроков - принудительное обновление карты")
                                        lastSharedMapHash = 0
                                        lastMapUpdate = 0
                                        updateMapAsync(newGame)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка обработки данных игры", e)
                        }
                    }
                }
                override fun onCancelled(e: DatabaseError) {
                    Log.e(TAG, "Ошибка слушателя игры: ${e.message}")
                    Toast.makeText(this@MultiplayerGameActivity, "Ошибка загрузки игры", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun playersDataChanged(oldGame: MultiplayerGame, newGame: MultiplayerGame): Boolean {
        if (oldGame.players.size != newGame.players.size) return true
        for ((uid, oldPlayer) in oldGame.players) {
            val newPlayer = newGame.players[uid] ?: continue
            val oldTownHall = oldPlayer.gameLogic.player.buildings.find { it is Building.TownHall }
            val newTownHall = newPlayer.gameLogic.player.buildings.find { it is Building.TownHall }
            if (oldTownHall?.health != newTownHall?.health) return true
            if (oldPlayer.gameLogic.player.resources != newPlayer.gameLogic.player.resources) return true
            if (oldPlayer.gameLogic.player.units.size != newPlayer.gameLogic.player.units.size) return true
            if (oldPlayer.gameLogic.armies.size != newPlayer.gameLogic.armies.size) return true
        }
        return false
    }

    private fun setupPlayersListener() {
        playersListener?.let { database.child("multiplayer_games").child(gameId).child("players").removeEventListener(it) }
        playersListener = database.child("multiplayer_games").child(gameId).child("players")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    lifecycleScope.launch {
                        lastSharedMapHash = 0
                        lastMapUpdate = 0
                        try {
                            val gameSnapshot = database.child("multiplayer_games").child(gameId).get().await()
                            val game = FirebaseGameMapper.safeGetMultiplayerGame(gameSnapshot)
                            withContext(Dispatchers.Main) {
                                game?.let {
                                    currentGame = it
                                    updateGameUI(it)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка перезагрузки игры", e)
                        }
                    }
                }
                override fun onCancelled(e: DatabaseError) {
                    Log.e(TAG, "Ошибка слушателя игроков: ${e.message}")
                }
            })
    }

    private fun updateGameUI(game: MultiplayerGame) {
        updateGameStatus(game)
        updatePlayersList(game)
        updateCurrentPlayerInfo(game)
        updateButtons(game)
        lifecycleScope.launch { updateMapAsync(game) }
    }

    private suspend fun updateMapAsync(game: MultiplayerGame) = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        if (now - lastMapUpdate < MAP_UPDATE_THROTTLE && lastSharedMapHash != 0) {
            Log.d(TAG, "Пропуск обновления карты (троттлинг)")
            return@withContext
        }
        try {
            val sharedMap = withContext(Dispatchers.IO) {
                database.child("multiplayer_games").child(gameId).child("sharedMap")
                    .get().await().getValue(GameMap::class.java) ?: GameMap()
            }
            val newHash = sharedMap.cells.hashCode()
            if (newHash != lastSharedMapHash || lastSharedMapHash == 0) {
                Log.d(TAG, "Обновление карты: хэш $lastSharedMapHash -> $newHash")
                lastSharedMapHash = newHash
                lastMapUpdate = now
                withContext(Dispatchers.Main) {
                    renderSharedMapOptimized(game, sharedMap)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки карты", e)
        }
    }

    private fun prepareRenderLogic(game: MultiplayerGame, sharedMap: GameMap): Pair<GameLogic, List<GamePlayer>> {
        val renderLogic = GameLogic()
        renderLogic.gameMap = sharedMap
        for (player in game.players.values) {
            val activeBuildings = player.gameLogic.player.buildings.filter { !it.isDestroyed() && it !is Building.TownHall }
            renderLogic.player.buildings.addAll(activeBuildings)
        }
        return Pair(renderLogic, game.players.values.toList())
    }

    private fun renderSharedMapOptimized(game: MultiplayerGame, sharedMap: GameMap) {
        if (isRendering) return
        isRendering = true
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val (renderLogic, allPlayers) = prepareRenderLogic(game, sharedMap)
                withContext(Dispatchers.Main) {
                    try {
                        val renderer = GameMapRenderer(
                            this@MultiplayerGameActivity,
                            renderLogic,
                            allPlayers,
                            auth.currentUser?.uid
                        ) { cell ->
                            if (!isSpectator && isMyTurn()) {
                                handleCellClickOnSharedMap(cell, game, sharedMap)
                            }
                        }
                        val newView = renderer.render()
                        swapMapView(newView)
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка рендеринга карты", e)
                        showMapError("Ошибка отображения карты")
                    } finally {
                        isRendering = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка подготовки данных для рендеринга", e)
                isRendering = false
            }
        }
    }

    private fun swapMapView(newView: View) {
        newView.alpha = 0f
        mapContainer.addView(newView)
        newView.animate()
            .alpha(1f)
            .setDuration(200)
            .withEndAction {
                for (i in mapContainer.childCount - 2 downTo 0) {
                    val child = mapContainer.getChildAt(i)
                    if (child != newView) {
                        mapContainer.removeViewAt(i)
                    }
                }
            }
            .start()
    }

    private fun showMapError(message: String) {
        val errorView = TextView(this).apply {
            text = message
            setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
            gravity = Gravity.CENTER
            setPadding(0, 50, 0, 50)
        }
        mapContainer.addView(errorView)
        mapContainer.postDelayed({
            if (mapContainer.indexOfChild(errorView) != -1) {
                mapContainer.removeView(errorView)
            }
        }, 3000)
    }

    private fun updateGameStatus(game: MultiplayerGame) {
        when (game.gameState) {
            GameState.WAITING_FOR_PLAYERS -> {
                tvGameStatus.text = "Ожидание игроков (${game.players.size}/${game.maxPlayers})"
                tvGameStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
            }
            GameState.IN_PROGRESS -> {
                val name = game.players[game.currentTurnUid]?.displayName ?: "Неизвестно"
                tvGameStatus.text = "Идет игра - Ход: $name"
                tvGameStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
            }
            GameState.FINISHED -> {
                val winner = game.players[game.winnerUid]?.displayName ?: "Неизвестно"
                tvGameStatus.text = "Игра завершена! Победитель: $winner"
                tvGameStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
            }
            GameState.ABANDONED -> {
                tvGameStatus.text = "Игра прервана"
                tvGameStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
            }
        }
    }

    private fun updatePlayersList(game: MultiplayerGame) {
        tvPlayersSummary.text = "${game.players.size}/${game.maxPlayers}"
    }

    private fun updateCurrentPlayerInfo(game: MultiplayerGame) {
        val uid = auth.currentUser?.uid
        val player = uid?.let { game.players[it] }
        if (player != null) {
            val eraName = getEraName(player.gameLogic.player.era)
            tvCurrentPlayer.text = "Вы: ${player.displayName} ($eraName)"
            val resourcesText = player.gameLogic.player.resources.getAvailableResources(player.gameLogic.player.era)
            tvResources.text = "Ресурсы:\n$resourcesText"
            val aliveUnits = player.gameLogic.player.units.filter { it.health > 0 }
            val unitCounts = aliveUnits.groupBy { it.name }.map { "${it.key} (${it.value.size})" }
            val unitsText = if (unitCounts.isEmpty()) "Нет юнитов" else unitCounts.joinToString(", ")
            tvUnits.text = "Юниты:\n$unitsText"
        } else {
            tvCurrentPlayer.text = "Наблюдатель"
            tvResources.text = "Ресурсы: —"
            tvUnits.text = "Юниты: —"
        }
    }

    private fun updateButtons(game: MultiplayerGame) {
        val isMyTurn = isMyTurn() && game.gameState == GameState.IN_PROGRESS && !isSpectator
        btnBuild.isEnabled = isMyTurn
        btnEndTurn.isEnabled = isMyTurn
        btnEndTurn.alpha = if (isMyTurn) 1.0f else 0.5f
        btnEndTurn.text = if (isMyTurn) "Завершить ход" else "Ожидание хода"
    }

    private fun isMyTurn(): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        return currentGame?.isPlayerTurn(uid) == true
    }

    // 🔥 ОСНОВНОЙ МЕТОД КЛИКА ПО КЛЕТКЕ - С ВИДЕО СРАЖЕНИЙ
    private fun handleCellClickOnSharedMap(cell: MapCell, game: MultiplayerGame, sharedMap: GameMap) {
        val uid = auth.currentUser?.uid ?: return

        // Если армия уже выбрана — перемещаем или показываем предпросмотр боя
        if (selectedArmy != null) {
            val army = selectedArmy!!
            val dx = abs(army.position.x - cell.x)
            val dy = abs(army.position.y - cell.y)

            // Проверяем, занята ли целевая клетка (ратуша или армия)
            var isOccupied = false
            var defenderUid: String? = null

            for ((otherUid, otherPlayer) in game.players) {
                if (otherUid == uid) continue

                // Чужая ратуша
                val pos = otherPlayer.gameLogic.player.townHallPosition
                if (pos.x == cell.x && pos.y == cell.y) {
                    isOccupied = true
                    defenderUid = otherUid
                    break
                }

                // Чужая армия
                if (otherPlayer.gameLogic.armies.any {
                        it.position.x == cell.x && it.position.y == cell.y && it.isAlive()
                    }) {
                    isOccupied = true
                    defenderUid = otherUid
                    break
                }
            }

            if (isOccupied) {
                // 🔥 ПОКАЗЫВАЕМ ПРЕДПРОСМОТР БОЯ С ВИДЕО
                if (dx + dy == 1) {
                    showBattlePreview(uid, army, cell.x, cell.y, game)
                } else {
                    Toast.makeText(this, "Для атаки армия должна быть на соседней клетке", Toast.LENGTH_SHORT).show()
                }
                selectedArmy = null
                return
            }

            // 🔥 ОБЫЧНОЕ ПЕРЕМЕЩЕНИЕ (только на пустые клетки)
            if (dx + dy <= 2 && cell.type == "empty") {
                lifecycleScope.launch {
                    try {
                        val success = multiplayerLogic.makeTurn(
                            gameId, uid,
                            listOf(GameAction.MoveArmy(army.id, cell.x, cell.y))
                        )
                        if (success) {
                            Toast.makeText(this@MultiplayerGameActivity, "Армия движется!", Toast.LENGTH_SHORT).show()
                            updatePlayerState(uid)
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MultiplayerGameActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Нельзя двигаться сюда", Toast.LENGTH_SHORT).show()
            }
            selectedArmy = null
            return
        }

        // Проверка своей армии
        val myLogic = game.players[uid]?.gameLogic ?: return
        val myArmiesHere = myLogic.armies.filter { it.position.x == cell.x && it.position.y == cell.y && it.isAlive() }
        if (myArmiesHere.isNotEmpty()) {
            selectedArmy = myArmiesHere.first()
            Toast.makeText(this, "Армия выбрана. Кликните на клетку для перемещения или атаки.", Toast.LENGTH_LONG).show()
            return
        }

        // 🔥 ПРОВЕРКА ВРАЖЕСКИХ ЦЕЛЕЙ ДЛЯ ВЫБОРА АРМИИ ДЛЯ АТАКИ
        var enemyTargetFound = false
        for ((otherUid, otherPlayer) in game.players) {
            if (otherUid == uid) continue

            // Чужая армия или ратуша
            val isEnemyArmy = otherPlayer.gameLogic.armies.any {
                it.position.x == cell.x && it.position.y == cell.y && it.isAlive()
            }
            val isEnemyTownHall = otherPlayer.gameLogic.player.townHallPosition.x == cell.x &&
                    otherPlayer.gameLogic.player.townHallPosition.y == cell.y

            if (isEnemyArmy || isEnemyTownHall) {
                enemyTargetFound = true
                showArmySelectionForAttack(uid, cell.x, cell.y)
                break
            }
        }

        if (enemyTargetFound) return

        // Своя ратуша
        val myPos = myLogic.player.townHallPosition
        if (cell.type == "town_hall" && myPos.x == cell.x && myPos.y == cell.y) {
            showTownHallMenu(uid)
            return
        }

        // Строительство
        if (cell.type == "empty" && selectedBuilding != null) {
            buildOnCell(uid, cell)
            return
        }

        // Взаимодействие со зданиями
        handleBuildingInteraction(uid, cell, game)
    }

    // 🔥 ОБНОВЛЁННЫЙ МЕТОД ДЛЯ ВЫБОРА АРМИИ ДЛЯ АТАКИ
    private fun showArmySelectionForAttack(uid: String, targetX: Int, targetY: Int) {
        val game = currentGame ?: return
        val logic = game.players[uid]?.gameLogic ?: return

        // Ищем армии на соседних клетках
        val attackableArmies = logic.armies.filter { army ->
            army.isAlive() && !army.hasMovedThisTurn &&
                    abs(army.position.x - targetX) + abs(army.position.y - targetY) == 1
        }

        if (attackableArmies.isEmpty()) {
            Toast.makeText(this, "Нет армий на соседних клетках для атаки", Toast.LENGTH_SHORT).show()
            return
        }

        val armyNames = attackableArmies.mapIndexed { i, a ->
            "Армия ${i + 1} (${a.units.size} юнитов, сила: ${a.totalAttackPower()})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Выберите армию для атаки")
            .setItems(armyNames) { _, index ->
                val army = attackableArmies[index]
                showBattlePreview(uid, army, targetX, targetY, game)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // 🔥 НОВЫЙ МЕТОД ДЛЯ ПРЕДПРОСМОТРА БОЯ
    private fun showBattlePreview(attackerUid: String, attackerArmy: Army, targetX: Int, targetY: Int, game: MultiplayerGame) {
        // Находим защитника
        var defenderUid: String? = null
        var defenderArmy: Army? = null
        var defenderTownHall: Building.TownHall? = null
        var defenderName: String? = null
        var isTownHallAttack = false

        for ((otherUid, otherPlayer) in game.players) {
            if (otherUid == attackerUid) continue

            // Проверяем вражескую армию
            val enemyArmy = otherPlayer.gameLogic.armies.find {
                it.position.x == targetX && it.position.y == targetY && it.isAlive()
            }
            if (enemyArmy != null) {
                defenderUid = otherUid
                defenderArmy = enemyArmy
                defenderName = otherPlayer.displayName
                break
            }

            // Проверяем вражескую ратушу
            val pos = otherPlayer.gameLogic.player.townHallPosition
            if (pos.x == targetX && pos.y == targetY) {
                defenderUid = otherUid
                defenderTownHall = otherPlayer.gameLogic.player.buildings
                    .find { it is Building.TownHall } as? Building.TownHall
                defenderName = otherPlayer.displayName
                isTownHallAttack = true
                break
            }
        }

        if (defenderUid == null) {
            Toast.makeText(this, "Цель для атаки не найдена", Toast.LENGTH_SHORT).show()
            return
        }

        val battlePreview = BattlePreview(
            attackerArmy = attackerArmy,
            defenderArmy = defenderArmy,
            defenderTownHall = defenderTownHall,
            defenderUid = defenderUid,
            defenderName = defenderName,
            targetPosition = Position(targetX, targetY),
            isTownHallAttack = isTownHallAttack
        )

        // 🔥 ПОКАЗЫВАЕМ ДИАЛОГ ПОДТВЕРЖДЕНИЯ БОЯ
        showBattleConfirmationDialog(attackerUid, battlePreview)
    }

    // 🔥 ИСПРАВЛЕННЫЙ ДИАЛОГ ПОДТВЕРЖДЕНИЯ БОЯ - ВИДЕО ПОСЛЕ НАЧАЛА БОЯ
    private fun showBattleConfirmationDialog(attackerUid: String, battlePreview: BattlePreview) {
        val battleResult = battlePreview.calculateBattleResult()

        // Создаем кастомный layout для диалога
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Заголовок
        val tvTitle = TextView(this).apply {
            text = "Предпросмотр боя"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        dialogView.addView(tvTitle)

        // Контейнер для армий
        val armiesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Атакующая армия
        val attackerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundResource(R.drawable.battle_side_background)
            setPadding(12, 12, 12, 12)
        }

        val tvAttackerTitle = TextView(this).apply {
            text = "Ваша армия"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MultiplayerGameActivity, R.color.primaryDarkColor))
            gravity = Gravity.CENTER
        }
        attackerLayout.addView(tvAttackerTitle)

        val tvAttackerUnits = TextView(this).apply {
            text = formatArmyUnits(battlePreview.attackerArmy)
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }
        attackerLayout.addView(tvAttackerUnits)

        val tvAttackerPower = TextView(this).apply {
            text = "Общая сила: ${battlePreview.attackerTotalPower}"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 4, 0, 0)
        }
        attackerLayout.addView(tvAttackerPower)

        armiesLayout.addView(attackerLayout)

        // Разделитель
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 120).apply {
                setMargins(8, 0, 8, 0)
            }
            setBackgroundColor(ContextCompat.getColor(this@MultiplayerGameActivity, R.color.gray))
        }
        armiesLayout.addView(divider)

        // Защищающаяся армия
        val defenderLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundResource(R.drawable.battle_side_background)
            setPadding(12, 12, 12, 12)
        }

        val tvDefenderTitle = TextView(this).apply {
            text = if (battlePreview.isTownHallAttack) "Ратуша противника" else "Армия ${battlePreview.defenderName}"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MultiplayerGameActivity, R.color.red))
            gravity = Gravity.CENTER
        }
        defenderLayout.addView(tvDefenderTitle)

        val tvDefenderUnits = TextView(this).apply {
            text = if (battlePreview.isTownHallAttack) {
                "Прочность: ${battlePreview.defenderTownHall?.health ?: 0}"
            } else {
                formatArmyUnits(battlePreview.defenderArmy!!)
            }
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }
        defenderLayout.addView(tvDefenderUnits)

        val tvDefenderPower = TextView(this).apply {
            text = if (battlePreview.isTownHallAttack) {
                "Тип: Защитное сооружение"
            } else {
                "Общая сила: ${battlePreview.defenderTotalPower}"
            }
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 4, 0, 0)
        }
        defenderLayout.addView(tvDefenderPower)

        armiesLayout.addView(defenderLayout)
        dialogView.addView(armiesLayout)

        // Предсказание результата
        val victoryChance = calculateVictoryChance(battlePreview)
        val tvPrediction = TextView(this).apply {
            text = "Шанс победы: ${"%.0f".format(victoryChance * 100)}%"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(ContextCompat.getColor(this@MultiplayerGameActivity, R.color.external_news_bg))
            setTextColor(ContextCompat.getColor(this@MultiplayerGameActivity, R.color.icon_tint))
            setPadding(0, 8, 0, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 0)
            }
        }
        dialogView.addView(tvPrediction)

        // Подсказка
        val tvHint = TextView(this).apply {
            text = "После подтверждения начнется видео сражения"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@MultiplayerGameActivity, R.color.gray))
            setPadding(0, 8, 0, 0)
        }
        dialogView.addView(tvHint)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Начать бой!") { _, _ ->
                // 🔥 ЗАПУСКАЕМ ВИДЕО СРАЗУ ПОСЛЕ ПОДТВЕРЖДЕНИЯ БОЯ
                showBattleVideoAndExecute(attackerUid, battlePreview, battleResult)
            }
            .setNegativeButton("Отмена") { dialog, _ ->
                dialog.dismiss()
                selectedArmy = null
            }
            .setOnCancelListener {
                selectedArmy = null
            }
            .show()
    }

    // 🔥 НОВЫЙ МЕТОД ДЛЯ ПОКАЗА ВИДЕО И ВЫПОЛНЕНИЯ БОЯ
    private fun showBattleVideoAndExecute(attackerUid: String, battlePreview: BattlePreview, battleResult: BattleResult) {
        currentBattlePreview = battlePreview

        // Определяем какое видео показывать
        val videoResource = when {
            battlePreview.isTownHallAttack -> R.raw.battle_army_vs_army
            else -> R.raw.battle_army_vs_army
        }

        // Проверяем существование видеоресурса
        try {
            val videoUri = Uri.parse("android.resource://${packageName}/$videoResource")

            // Создаем полноэкранный диалог с видео
            val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.setContentView(R.layout.dialog_battle_video)

            val videoView = dialog.findViewById<VideoView>(R.id.videoView)
            val btnSkip = dialog.findViewById<Button>(R.id.btnSkip)
            val progressBar = dialog.findViewById<ProgressBar>(R.id.progressBar)

            // Настройка VideoView
            videoView.setVideoURI(videoUri)

            videoView.setOnPreparedListener { mediaPlayer ->
                progressBar.visibility = View.GONE
                mediaPlayer.isLooping = false
                videoView.start()
            }

            videoView.setOnCompletionListener {
                dialog.dismiss()
                // 🔥 ПОСЛЕ ВИДЕО ВЫПОЛНЯЕМ РЕАЛЬНЫЙ БОЙ И ПОКАЗЫВАЕМ РЕЗУЛЬТАТЫ
                executeRealBattle(attackerUid, battlePreview, battleResult)
            }

            videoView.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Ошибка воспроизведения видео: $what, $extra")
                progressBar.visibility = View.GONE
                // Если видео не загрузилось, выполняем бой сразу
                dialog.dismiss()
                executeRealBattle(attackerUid, battlePreview, battleResult)
                true
            }

            btnSkip.setOnClickListener {
                videoView.stopPlayback()
                dialog.dismiss()
                // Пользователь пропустил видео - выполняем бой
                executeRealBattle(attackerUid, battlePreview, battleResult)
            }

            dialog.setCancelable(false)
            dialog.show()

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки видео: ${e.message}")
            // Если возникла ошибка с видео, выполняем бой сразу
            executeRealBattle(attackerUid, battlePreview, battleResult)
        }
    }

    // 🔥 НОВЫЙ МЕТОД ДЛЯ ВЫПОЛНЕНИЯ РЕАЛЬНОГО БОЯ И ПОКАЗА РЕЗУЛЬТАТОВ
    private fun executeRealBattle(attackerUid: String, battlePreview: BattlePreview, predictedResult: BattleResult) {
        lifecycleScope.launch {
            try {
                val action = GameAction.ConfirmArmyCombat(
                    attackerArmyId = battlePreview.attackerArmy.id,
                    defenderArmyId = battlePreview.defenderArmy?.id,
                    defenderUid = battlePreview.defenderUid,
                    targetX = battlePreview.targetPosition.x,
                    targetY = battlePreview.targetPosition.y,
                    isTownHallAttack = battlePreview.isTownHallAttack
                )

                val success = multiplayerLogic.makeTurn(gameId, attackerUid, listOf(action))

                if (success) {
                    // 🔥 ПОЛУЧАЕМ АКТУАЛЬНЫЕ РЕЗУЛЬТАТЫ БОЯ ИЗ БАЗЫ ДАННЫХ
                    val actualBattleResult = getActualBattleResult(attackerUid, battlePreview)
                    // Показываем результаты боя
                    showBattleResults(attackerUid, actualBattleResult ?: predictedResult)
                } else {
                    Toast.makeText(this@MultiplayerGameActivity, "Ошибка выполнения боя", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MultiplayerGameActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 🔥 МЕТОД ДЛЯ ПОЛУЧЕНИЯ АКТУАЛЬНЫХ РЕЗУЛЬТАТОВ БОЯ ИЗ БАЗЫ ДАННЫХ
    private suspend fun getActualBattleResult(attackerUid: String, battlePreview: BattlePreview): BattleResult? {
        return withContext(Dispatchers.IO) {
            try {
                // Получаем актуальные данные об армиях после боя
                val attackerSnapshot = database.child("multiplayer_games").child(gameId)
                    .child("players").child(attackerUid).child("gameLogic").get().await()
                val attackerLogic = FirebaseGameMapper.parseGameLogic(attackerSnapshot)

                val attackerArmy = attackerLogic?.armies?.find { it.id == battlePreview.attackerArmy.id }

                // Создаем актуальный результат боя
                val result = BattleResult()

                if (attackerArmy != null) {
                    result.attackerSurvivedUnits = if (attackerArmy.isAlive()) listOf(attackerArmy) else emptyList()
                    result.attackerPowerRemaining = attackerArmy.totalAttackPower()

                    // Определяем победу на основе состояния армии
                    result.victory = attackerArmy.isAlive() &&
                            (battlePreview.isTownHallAttack || battlePreview.defenderArmy == null)
                } else {
                    result.attackerSurvivedUnits = emptyList()
                    result.attackerPowerRemaining = 0
                    result.victory = false
                }

                return@withContext result
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка получения актуальных результатов боя", e)
                return@withContext null
            }
        }
    }

    // 🔥 МЕТОД ДЛЯ ФОРМАТИРОВАНИЯ СПИСКА ЮНИТОВ АРМИИ
    private fun formatArmyUnits(army: Army): String {
        val unitGroups = army.units.groupBy { it.name }
        return unitGroups.entries.joinToString("\n") { (name, units) ->
            "• $name: ${units.size} шт. (сила: ${units.sumOf { it.attackPower }})"
        }
    }

    // 🔥 РАСЧЕТ ШАНСА ПОБЕДЫ
    private fun calculateVictoryChance(battlePreview: BattlePreview): Double {
        val attackerPower = battlePreview.attackerTotalPower.toDouble()
        val defenderPower = battlePreview.defenderTotalPower.toDouble()

        return if (attackerPower + defenderPower > 0) {
            attackerPower / (attackerPower + defenderPower)
        } else {
            0.5
        }
    }

    // 🔥 ДИАЛОГ РЕЗУЛЬТАТОВ БОЯ
    private fun showBattleResults(attackerUid: String, battleResult: BattleResult) {
        // Создаем кастомный layout для результатов
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Заголовок
        val tvTitle = TextView(this).apply {
            text = "Результаты боя"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        dialogView.addView(tvTitle)

        // Результат
        val tvBattleResult = TextView(this).apply {
            text = battleResult.getResultMessage()
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (battleResult.victory) ContextCompat.getColor(this@MultiplayerGameActivity, R.color.primaryDarkColor)
            else ContextCompat.getColor(this@MultiplayerGameActivity, R.color.red))
            setPadding(0, 0, 0, 16)
        }
        dialogView.addView(tvBattleResult)

        // Контейнер для потерь
        val lossesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Потери атакующего
        val attackerLossLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundResource(R.drawable.battle_result_background)
            setPadding(12, 12, 12, 12)
        }

        val tvAttackerTitle = TextView(this).apply {
            text = "Ваши потери"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MultiplayerGameActivity, R.color.colorAccent))
            gravity = Gravity.CENTER
        }
        attackerLossLayout.addView(tvAttackerTitle)

        val attackerLossText = if (battleResult.attackerSurvivedUnits.isNotEmpty()) {
            "Выжившие: ${battleResult.attackerSurvivedUnits.sumOf { it.units.size }} юнитов\n" +
                    "Оставшаяся сила: ${battleResult.attackerPowerRemaining}"
        } else {
            "Армия уничтожена"
        }

        val tvAttackerLosses = TextView(this).apply {
            text = attackerLossText
            textSize = 12f
            setPadding(0, 4, 0, 0)
        }
        attackerLossLayout.addView(tvAttackerLosses)

        lossesLayout.addView(attackerLossLayout)

        // Разделитель
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 80).apply {
                setMargins(8, 0, 8, 0)
            }
            setBackgroundColor(ContextCompat.getColor(this@MultiplayerGameActivity, R.color.gray))
        }
        lossesLayout.addView(divider)

        // Потери защитника
        val defenderLossLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundResource(R.drawable.battle_result_background)
            setPadding(12, 12, 12, 12)
        }

        val tvDefenderTitle = TextView(this).apply {
            text = "Потери врага"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MultiplayerGameActivity, R.color.red))
            gravity = Gravity.CENTER
        }
        defenderLossLayout.addView(tvDefenderTitle)

        val defenderLossText = if (battleResult.townHallDestroyed) {
            "Ратуша уничтожена!"
        } else if (battleResult.defenderSurvivedUnits.isNotEmpty()) {
            "Выжившие: ${battleResult.defenderSurvivedUnits.sumOf { it.units.size }} юнитов\n" +
                    "Оставшаяся сила: ${battleResult.defenderPowerRemaining}"
        } else if (battleResult.townHallHealthRemaining > 0) {
            "Ратуша повреждена\nОсталось прочности: ${battleResult.townHallHealthRemaining}"
        } else {
            "Армия уничтожена"
        }

        val tvDefenderLosses = TextView(this).apply {
            text = defenderLossText
            textSize = 12f
            setPadding(0, 4, 0, 0)
        }
        defenderLossLayout.addView(tvDefenderLosses)

        lossesLayout.addView(defenderLossLayout)
        dialogView.addView(lossesLayout)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                updatePlayerState(attackerUid)
                lastSharedMapHash = 0
                currentGame?.let { updateGameUI(it) }
                selectedArmy = null
            }
            .setCancelable(false)
            .show()
    }

    // 🔥 МЕНЮ РАТУШИ
    private fun showTownHallMenu(uid: String) {
        val options = arrayOf("Эволюция", "Сформировать армию")
        AlertDialog.Builder(this)
            .setTitle("Ратуша")
            .setItems(options) { _, i ->
                when (i) {
                    0 -> showEraMenu(uid)
                    1 -> showCreateArmyDialog(uid)
                }
            }
            .show()
    }

    // 🔥 ДИАЛОГ ФОРМИРОВАНИЯ АРМИИ
    private fun showCreateArmyDialog(uid: String) {
        val game = currentGame ?: return
        val logic = game.players[uid]?.gameLogic ?: return
        val aliveUnits = logic.player.units.filter { it.health > 0 }
        if (aliveUnits.isEmpty()) {
            Toast.makeText(this, "Нет юнитов для формирования армии!", Toast.LENGTH_SHORT).show()
            return
        }

        val unitGroups = aliveUnits.groupBy { it.type }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val counts = mutableMapOf<String, Int>()

        unitGroups.forEach { (type, units) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = "${units.first().name} (${units.size})"
                setPadding(0, 0, 16, 0)
            }
            val input = EditText(this).apply {
                setText("0")
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                maxWidth = 80
            }
            row.addView(label)
            row.addView(input)
            layout.addView(row)
            counts[type] = 0
            input.addTextChangedListener { s ->
                counts[type] = s.toString().toIntOrNull() ?: 0
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Формирование армии")
            .setView(layout)
            .setPositiveButton("Создать") { _, _ ->
                val validCounts = counts.filter { it.value > 0 && it.value <= unitGroups[it.key]?.size ?: 0 }
                if (validCounts.isEmpty()) {
                    Toast.makeText(this, "Выберите хотя бы одного юнита", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        val success = multiplayerLogic.makeTurn(gameId, uid, listOf(GameAction.CreateArmy(validCounts)))
                        if (success) {
                            Toast.makeText(this@MultiplayerGameActivity, "Армия создана!", Toast.LENGTH_SHORT).show()
                            updatePlayerState(uid)
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MultiplayerGameActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun createBuildingByEraAndIndex(era: Era, index: Int): Building? {
        return when (era) {
            Era.STONE_AGE -> when (index) {
                0 -> Building.Hut()
                1 -> Building.Well()
                2 -> Building.Sawmill()
                3 -> Building.FishingHut()
                4 -> Building.Barracks()
                5 -> Building.ResearchCenter()
                else -> null
            }
            Era.BRONZE_AGE -> when (index) {
                0 -> Building.Farm()
                1 -> Building.Quarry()
                2 -> Building.GoldMine()
                3 -> Building.Forge()
                4 -> Building.Barracks()
                5 -> Building.ResearchCenter()
                else -> null
            }
            Era.MIDDLE_AGES -> when (index) {
                0 -> Building.IronMine()
                1 -> Building.Castle()
                2 -> Building.Blacksmith()
                3 -> Building.Barracks()
                4 -> Building.ResearchCenter()
                else -> null
            }
            Era.INDUSTRIAL -> when (index) {
                0 -> Building.CoalMine()
                1 -> Building.OilRig()
                2 -> Building.Factory()
                3 -> Building.PowerPlant()
                4 -> Building.Barracks()
                5 -> Building.ResearchCenter()
                else -> null
            }
            Era.FUTURE -> when (index) {
                0 -> Building.SolarPlant()
                1 -> Building.NuclearPlant()
                2 -> Building.RoboticsLab()
                3 -> Building.Barracks()
                4 -> Building.ResearchCenter()
                else -> null
            }
            else -> null
        }
    }

    private fun showBuildingMenu() {
        val uid = auth.currentUser?.uid ?: return
        val game = currentGame ?: return
        val logic = game.players[uid]?.gameLogic ?: return
        val era = logic.player.era
        val buildings = when (era) {
            Era.STONE_AGE -> arrayOf("Хижина", "Колодец", "Лесопилка", "Рыболовная хижина", "Казармы", "Научный центр")
            Era.BRONZE_AGE -> arrayOf("Ферма", "Каменоломня", "Золотой рудник", "Кузница", "Казармы", "Научный центр")
            Era.MIDDLE_AGES -> arrayOf("Железный рудник", "Замок", "Оружейная", "Казармы", "Научный центр")
            Era.INDUSTRIAL -> arrayOf("Угольная шахта", "Нефтяная вышка", "Фабрика", "Электростанция", "Казармы", "Научный центр")
            Era.FUTURE -> arrayOf("Солнечная станция", "Ядерный реактор", "Робо-лаборатория", "Казармы", "Научный центр")
            else -> emptyArray()
        }
        AlertDialog.Builder(this)
            .setTitle("Построить")
            .setItems(buildings) { _, index ->
                val building = createBuildingByEraAndIndex(era, index)
                if (building != null) {
                    selectedBuilding = building
                    Toast.makeText(this, "Выбрано: ${building.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun updatePlayerState(uid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val snapshot = database.child("multiplayer_games").child(gameId).child("players").child(uid)
                    .child("gameLogic").get().await()
                val logic = FirebaseGameMapper.parseGameLogic(snapshot)
                withContext(Dispatchers.Main) {
                    logic?.let { updatedLogic ->
                        val resourcesText = updatedLogic.player.resources.getAvailableResources(updatedLogic.player.era)
                        tvResources.text = "Ресурсы:\n$resourcesText"
                        val aliveUnits = updatedLogic.player.units.filter { it.health > 0 }
                        val unitCounts = aliveUnits.groupBy { it.name }.map { "${it.key} (${it.value.size})" }
                        tvUnits.text = "Юниты:\n${if (unitCounts.isEmpty()) "Нет юнитов" else unitCounts.joinToString(", ")}"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обновления состояния игрока", e)
            }
        }
    }

    // 🔥 ОБНОВЛЁННЫЙ МЕТОД ДЛЯ ПОКАЗА МЕНЮ КАЗАРМ
    private fun showBarracksMenu(uid: String) {
        val game = currentGame ?: return
        val logic = game.players[uid]?.gameLogic ?: return
        val era = logic.player.era

        val units = when (era) {
            Era.STONE_AGE -> arrayOf("Пещерный человек", "Охотник", "Всадник на мамонте")
            Era.BRONZE_AGE -> arrayOf("Мечник", "Лучник", "Боевая колесница")
            Era.MIDDLE_AGES -> arrayOf("Рыцарь", "Арбалетчик", "Таран")
            Era.INDUSTRIAL -> arrayOf("Солдат", "Артиллерия", "Танк")
            Era.FUTURE -> arrayOf("Боевой дрон", "Боевой мех", "Лазерная пушка")
            else -> emptyArray()
        }

        AlertDialog.Builder(this)
            .setTitle("Нанять юнитов")
            .setItems(units) { _, index ->
                showUnitQuantityDialog(uid, era, index)
            }
            .show()
    }

    // 🔥 НОВЫЙ МЕТОД ДЛЯ ВЫБОРА КОЛИЧЕСТВА ЮНИТОВ
    private fun showUnitQuantityDialog(uid: String, era: Era, unitIndex: Int) {
        val unit = createUnitByEraAndIndex(era, unitIndex) ?: return
        val cost = getUnitCost(unit)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Название юнита и стоимость
        val tvInfo = TextView(this).apply {
            text = "${unit.name}\nСтоимость за 1: ${cost.getAvailableResources(era)}"
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvInfo)

        // Поле для ввода количества
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val tvLabel = TextView(this).apply {
            text = "Количество:"
            setPadding(0, 0, 16, 0)
        }

        val etQuantity = EditText(this).apply {
            setText("1")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxWidth = 120
        }

        row.addView(tvLabel)
        row.addView(etQuantity)
        layout.addView(row)

        // Кнопки +/-
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }

        val btnMinus = Button(this).apply {
            text = "-"
            setOnClickListener {
                val current = etQuantity.text.toString().toIntOrNull() ?: 1
                if (current > 1) {
                    etQuantity.setText((current - 1).toString())
                }
            }
        }

        val btnPlus = Button(this).apply {
            text = "+"
            setOnClickListener {
                val current = etQuantity.text.toString().toIntOrNull() ?: 1
                etQuantity.setText((current + 1).toString())
            }
        }

        buttonRow.addView(btnMinus)
        buttonRow.addView(btnPlus)
        layout.addView(buttonRow)

        AlertDialog.Builder(this)
            .setTitle("Наем юнитов")
            .setView(layout)
            .setPositiveButton("Нанять") { _, _ ->
                val quantity = etQuantity.text.toString().toIntOrNull() ?: 1
                if (quantity > 0) {
                    hireUnits(uid, unit, quantity)
                } else {
                    Toast.makeText(this, "Введите корректное количество", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // 🔥 ОБНОВЛЁННЫЙ МЕТОД ДЛЯ НАЙМА ЮНИТОВ
    private fun hireUnits(uid: String, unit: GameUnit, quantity: Int) {
        if (quantity <= 0) return

        lifecycleScope.launch {
            try {
                val success = multiplayerLogic.makeTurn(gameId, uid, listOf(GameAction.HireUnit(unit, quantity)))
                if (success) {
                    Toast.makeText(this@MultiplayerGameActivity, "Нанято $quantity ${unit.name}!", Toast.LENGTH_SHORT).show()
                    updatePlayerState(uid)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MultiplayerGameActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 🔥 МЕТОД ДЛЯ ПОЛУЧЕНИЯ СТОИМОСТИ ЮНИТА
    private fun getUnitCost(unit: GameUnit): Resource {
        return when (unit) {
            is GameUnit.Caveman -> Resource(food = 15, wood = 10)
            is GameUnit.Hunter -> Resource(food = 20, wood = 15, water = 5)
            is GameUnit.MammothRider -> Resource(food = 50, wood = 30, water = 15)
            is GameUnit.Swordsman -> Resource(food = 25, stone = 20, gold = 10)
            is GameUnit.BronzeArcher -> Resource(food = 20, stone = 15, gold = 8)
            is GameUnit.Chariot -> Resource(food = 60, stone = 40, gold = 25)
            is GameUnit.Knight -> Resource(food = 35, iron = 25, gold = 15)
            is GameUnit.Crossbowman -> Resource(food = 30, iron = 20, gold = 12)
            is GameUnit.Ram -> Resource(food = 40, iron = 50, wood = 30)
            is GameUnit.Soldier -> Resource(food = 25, iron = 15, coal = 10)
            is GameUnit.Artillery -> Resource(food = 35, iron = 30, coal = 20, oil = 10)
            is GameUnit.Tank -> Resource(food = 50, iron = 60, coal = 30, oil = 20)
            is GameUnit.Drone -> Resource(energy = 40, iron = 20, gold = 15)
            is GameUnit.Mech -> Resource(energy = 80, iron = 50, gold = 25)
            is GameUnit.LaserCannon -> Resource(energy = 120, iron = 30, gold = 40)
            else -> Resource()
        }
    }

    private fun createUnitByEraAndIndex(era: Era, index: Int): GameUnit? {
        return when (era) {
            Era.STONE_AGE -> when (index) {
                0 -> GameUnit.Caveman()
                1 -> GameUnit.Hunter()
                2 -> GameUnit.MammothRider()
                else -> null
            }
            Era.BRONZE_AGE -> when (index) {
                0 -> GameUnit.Swordsman()
                1 -> GameUnit.BronzeArcher()
                2 -> GameUnit.Chariot()
                else -> null
            }
            Era.MIDDLE_AGES -> when (index) {
                0 -> GameUnit.Knight()
                1 -> GameUnit.Crossbowman()
                2 -> GameUnit.Ram()
                else -> null
            }
            Era.INDUSTRIAL -> when (index) {
                0 -> GameUnit.Soldier()
                1 -> GameUnit.Artillery()
                2 -> GameUnit.Tank()
                else -> null
            }
            Era.FUTURE -> when (index) {
                0 -> GameUnit.Drone()
                1 -> GameUnit.Mech()
                2 -> GameUnit.LaserCannon()
                else -> null
            }
            else -> null
        }
    }

    private fun buildOnCell(uid: String, cell: MapCell) {
        val building = selectedBuilding ?: return
        lifecycleScope.launch {
            try {
                val success = multiplayerLogic.makeTurn(
                    gameId, uid, listOf(GameAction.BuildBuilding(building, cell.x, cell.y))
                )
                if (success) {
                    updateSharedMapAfterBuilding(cell, building)
                    Toast.makeText(this@MultiplayerGameActivity, "${building.name} построено!", Toast.LENGTH_SHORT).show()
                    selectedBuilding = null
                    updatePlayerState(uid)
                    lastSharedMapHash = 0
                    currentGame?.let { updateGameUI(it) }
                }
            } catch (e: Exception) {
                Toast.makeText(this@MultiplayerGameActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleBuildingInteraction(uid: String, cell: MapCell, game: MultiplayerGame) {
        val myLogic = game.players[uid]?.gameLogic ?: return
        val myBuilding = myLogic.player.buildings.find { it.type == cell.type && !it.isDestroyed() }
        if (myBuilding != null) {
            when (myBuilding) {
                is Building.Barracks -> showBarracksMenu(uid)
                is Building.ResearchCenter -> showResearchMenu(uid)
                is Building.TownHall -> showTownHallMenu(uid)
                else -> showUpgradeMenu(uid, myBuilding)
            }
        }
    }

    private fun updateSharedMapAfterBuilding(cell: MapCell, building: Building) {
        database.child("multiplayer_games").child(gameId).child("sharedMap")
            .runTransaction(object : Transaction.Handler {
                override fun doTransaction(mutableData: MutableData): Transaction.Result {
                    try {
                        val mapSnapshot = mutableData.value as? Map<*, *> ?: return Transaction.success(mutableData)
                        val width = (mapSnapshot["width"] as? Long)?.toInt() ?: 9
                        val height = (mapSnapshot["height"] as? Long)?.toInt() ?: 9
                        val cellsList = mapSnapshot["cells"] as? List<Map<String, *>> ?: return Transaction.success(mutableData)
                        val cells = cellsList.mapIndexed { index, cellMap ->
                            val x = index % width
                            val y = index / width
                            MapCell(
                                type = (cellMap["type"] as? String) ?: "empty",
                                x = x,
                                y = y
                            )
                        }.toMutableList()
                        val index = cell.y * width + cell.x
                        if (index in cells.indices) {
                            cells[index] = MapCell(building.type, cell.x, cell.y)
                        }
                        val updatedMap = hashMapOf<String, Any?>(
                            "width" to width,
                            "height" to height,
                            "cells" to cells.map {
                                hashMapOf(
                                    "type" to it.type,
                                    "x" to it.x,
                                    "y" to it.y
                                )
                            }
                        )
                        mutableData.value = updatedMap
                        return Transaction.success(mutableData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка транзакции карты", e)
                        return Transaction.success(mutableData)
                    }
                }
                override fun onComplete(error: DatabaseError?, committed: Boolean, dataSnapshot: DataSnapshot?) {
                    if (error != null) {
                        Log.e(TAG, "Транзакция не выполнена: ${error.message}")
                    }
                }
            })
    }

    private fun showResearchMenu(uid: String) {
        val game = currentGame ?: return
        val logic = game.players[uid]?.gameLogic ?: return
        val researchList = logic.getAvailableResearch()
        if (researchList.isEmpty()) {
            Toast.makeText(this, "Нет доступных исследований", Toast.LENGTH_SHORT).show()
            return
        }
        val names = researchList.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Исследования")
            .setItems(names) { _, index ->
                val research = researchList[index]
                completeResearch(uid, research)
            }
            .show()
    }

    private fun completeResearch(uid: String, research: Research) {
        lifecycleScope.launch {
            try {
                val success = multiplayerLogic.makeTurn(gameId, uid, listOf(GameAction.CompleteResearch(research)))
                if (success) {
                    Toast.makeText(this@MultiplayerGameActivity, "Исследование завершено!", Toast.LENGTH_SHORT).show()
                    updatePlayerState(uid)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MultiplayerGameActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showEraMenu(uid: String) {
        val game = currentGame ?: return
        val logic = game.players[uid]?.gameLogic ?: return
        val nextEra = Era.values().getOrNull(logic.player.era.ordinal + 1) ?: return
        val req = GameLogic.ERA_REQUIREMENTS[nextEra] ?: return
        val eraName = getEraName(nextEra)
        val costText = "Ресурсы: ${req.resources.getAvailableResources(nextEra)}\n" +
                "Требуется исследований: ${req.completedResearch}\n" +
                "У вас: ${logic.player.completedResearch.size}"
        AlertDialog.Builder(this)
            .setTitle("Эволюция: $eraName")
            .setMessage(costText)
            .setPositiveButton("Эволюционировать") { _, _ ->
                evolveToEra(uid, nextEra)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun evolveToEra(uid: String, era: Era) {
        lifecycleScope.launch {
            try {
                val success = multiplayerLogic.makeTurn(
                    gameId, uid,
                    listOf(GameAction.EvolveToEra(era))
                )
                if (success) {
                    Toast.makeText(this@MultiplayerGameActivity, "Цивилизация перешла в ${getEraName(era)}!", Toast.LENGTH_LONG).show()
                    updatePlayerState(uid)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MultiplayerGameActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUpgradeMenu(uid: String, building: Building) {
        if (building.level >= 10) {
            Toast.makeText(this, "Макс. уровень", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Улучшить ${building.name}?")
            .setMessage("Уровень ${building.level} → ${building.level + 1}")
            .setPositiveButton("Улучшить") { _, _ ->
                upgradeBuilding(uid, building)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun upgradeBuilding(uid: String, building: Building) {
        lifecycleScope.launch {
            try {
                val success = multiplayerLogic.makeTurn(gameId, uid, listOf(GameAction.UpgradeBuilding(building)))
                if (success) {
                    Toast.makeText(this@MultiplayerGameActivity, "Улучшено!", Toast.LENGTH_SHORT).show()
                    updatePlayerState(uid)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MultiplayerGameActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun endTurn() {
        if (!isMyTurn()) return
        val uid = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                val success = multiplayerLogic.makeTurn(gameId, uid, listOf(GameAction.NextTurn))
                if (success) {
                    updatePlayerState(uid)
                    lastSharedMapHash = 0
                    currentGame?.let { updateGameUI(it) }
                }
            } catch (e: Exception) {
                Toast.makeText(this@MultiplayerGameActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun leaveGame() {
        val uid = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                multiplayerLogic.leaveGame(gameId, uid)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@MultiplayerGameActivity, "Ошибка выхода: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getEraName(era: Era): String = when (era) {
        Era.STONE_AGE -> "Каменный век"
        Era.BRONZE_AGE -> "Бронзовый век"
        Era.MIDDLE_AGES -> "Средневековье"
        Era.INDUSTRIAL -> "Индустриальная эра"
        Era.FUTURE -> "Футуристическая эра"
    }

    private fun showErrorAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        gameListener?.let { database.child("multiplayer_games").child(gameId).removeEventListener(it) }
        playersListener?.let { database.child("multiplayer_games").child(gameId).child("players").removeEventListener(it) }
        mapListener?.let { database.child("multiplayer_games").child(gameId).child("sharedMap").removeEventListener(it) }
        Log.d(TAG, "MultiplayerGameActivity уничтожена, слушатели удалены")
    }
}