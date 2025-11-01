package com.example.chatapp.igra_strotegiy

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.R
import com.example.chatapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

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

    // Управление слушателями
    private var gameListener: ValueEventListener? = null
    private var playersListener: ValueEventListener? = null
    private var mapListener: ValueEventListener? = null

    companion object {
        private const val TAG = "MultiplayerGameActivity"
        private const val MAP_UPDATE_THROTTLE = 300L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multiplayer_game)

        // Быстрая инициализация
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().reference
        multiplayerLogic = MultiplayerGameLogic(database)

        if (gameId.isEmpty()) {
            showErrorAndFinish("Ошибка: ID игры не получен")
            return
        }

        initViews() // Только UI элементы
        showLoadingState()

        // Асинхронная загрузка данных
        lifecycleScope.launch {
            loadGameDataAsync()
        }

        // Настройка слушателя игроков для мгновенного обновления здоровья ратуши
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

        // Изначально скрываем кнопки до загрузки данных
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
                setupGameListener() // Теперь в основном потоке
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
        if (uid == null) {
            Log.d(TAG, "Пользователь не авторизован - наблюдатель")
            return@withContext true
        }

        return@withContext try {
            val snapshot = database.child("multiplayer_games").child(gameId).child("players").child(uid)
                .get().await()
            val isPlayer = snapshot.exists()
            Log.d(TAG, "Статус игрока: $isPlayer")
            !isPlayer
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
        // Удалить предыдущий слушатель
        gameListener?.let {
            database.child("multiplayer_games").child(gameId).removeEventListener(it)
        }

        gameListener = database.child("multiplayer_games").child(gameId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    // Троттлинг обновлений
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

                                    // ПРИНУДИТЕЛЬНОЕ ОБНОВЛЕНИЕ КАРТЫ ПРИ ИЗМЕНЕНИИ ДАННЫХ ИГРОКОВ
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

    // Проверяем, изменились ли данные игроков (здоровье ратуши и т.д.)
    private fun playersDataChanged(oldGame: MultiplayerGame, newGame: MultiplayerGame): Boolean {
        if (oldGame.players.size != newGame.players.size) return true

        for ((uid, oldPlayer) in oldGame.players) {
            val newPlayer = newGame.players[uid] ?: continue

            // Проверяем здоровье ратуши
            val oldTownHall = oldPlayer.gameLogic.player.buildings.find { it is Building.TownHall }
            val newTownHall = newPlayer.gameLogic.player.buildings.find { it is Building.TownHall }

            if (oldTownHall?.health != newTownHall?.health) {
                Log.d(TAG, "Обнаружено изменение здоровья ратуши: ${oldTownHall?.health} -> ${newTownHall?.health}")
                return true
            }

            // Проверяем другие важные данные
            if (oldPlayer.gameLogic.player.resources != newPlayer.gameLogic.player.resources) return true
            if (oldPlayer.gameLogic.player.units.size != newPlayer.gameLogic.player.units.size) return true
        }

        return false
    }

    private fun setupPlayersListener() {
        // Удалить предыдущий слушатель
        playersListener?.let {
            database.child("multiplayer_games").child(gameId).child("players").removeEventListener(it)
        }

        playersListener = database.child("multiplayer_games").child(gameId).child("players")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    Log.d(TAG, "Обновление данных игроков обнаружено")

                    // Принудительно обновляем карту при любом изменении игроков
                    lifecycleScope.launch {
                        lastSharedMapHash = 0
                        lastMapUpdate = 0

                        // Перезагружаем игру
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

        // Рендеринг карты - с троттлингом и в фоне
        lifecycleScope.launch {
            updateMapAsync(game)
        }
    }

    private suspend fun updateMapAsync(game: MultiplayerGame) = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        // Убираем троттлинг при принудительном обновлении
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
            // Всегда обновляем если хэш изменился ИЛИ принудительное обновление
            if (newHash != lastSharedMapHash || lastSharedMapHash == 0) {
                Log.d(TAG, "Обновление карты: хэш $lastSharedMapHash -> $newHash")
                lastSharedMapHash = newHash
                lastMapUpdate = now
                withContext(Dispatchers.Main) {
                    renderSharedMapOptimized(game, sharedMap)
                }
            } else {
                Log.d(TAG, "Карта не изменилась, пропускаем рендеринг")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки карты", e)
        }
    }

    private fun prepareRenderLogic(game: MultiplayerGame, sharedMap: GameMap): Pair<GameLogic, List<GamePlayer>> {
        val renderLogic = GameLogic()
        renderLogic.gameMap = sharedMap
        // Собираем все здания (для не-ратуш), но ратуши будем брать по позиции
        for (player in game.players.values) {
            val activeBuildings = player.gameLogic.player.buildings.filter { !it.isDestroyed() && it !is Building.TownHall }
            renderLogic.player.buildings.addAll(activeBuildings)
        }
        return Pair(renderLogic, game.players.values.toList())
    }

    private fun renderSharedMapOptimized(game: MultiplayerGame, sharedMap: GameMap) {
        if (isRendering) {
            Log.d(TAG, "Рендеринг уже выполняется, пропускаем")
            return
        }
        isRendering = true
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val (renderLogic, allPlayers) = prepareRenderLogic(game, sharedMap)
                withContext(Dispatchers.Main) {
                    try {
                        val renderer = GameMapRenderer(
                            this@MultiplayerGameActivity,
                            renderLogic,
                            allPlayers
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
                // Удаляем старые view, кроме новой
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
            .setTitle("Нанять юнита")
            .setItems(units) { _, index ->
                val unit = createUnitByEraAndIndex(era, index)
                if (unit != null) {
                    hireUnit(uid, unit)
                }
            }
            .show()
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

    private fun hireUnit(uid: String, unit: GameUnit) {
        lifecycleScope.launch {
            try {
                val success = multiplayerLogic.makeTurn(gameId, uid, listOf(GameAction.HireUnit(unit)))
                if (success) {
                    Toast.makeText(this@MultiplayerGameActivity, "Юнит нанят!", Toast.LENGTH_SHORT).show()
                    updatePlayerState(uid)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MultiplayerGameActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleCellClickOnSharedMap(cell: MapCell, game: MultiplayerGame, sharedMap: GameMap) {
        val uid = auth.currentUser?.uid ?: return

        // Атака на вражескую ратушу
        if (cell.type == "town_hall") {
            var isOwnTownHall = false
            var targetUid: String? = null

            for ((playerUid, player) in game.players) {
                val pos = player.gameLogic.player.townHallPosition
                if (pos.x == cell.x && pos.y == cell.y) {
                    if (playerUid == uid) {
                        isOwnTownHall = true
                    } else {
                        targetUid = playerUid
                    }
                    break
                }
            }

            if (!isOwnTownHall && targetUid != null) {
                attackEnemyTownHall(uid, targetUid, game)
                return
            }

            if (isOwnTownHall) {
                showEraMenu(uid)
                return
            }
        }

        // Строительство
        if (cell.type == "empty" && selectedBuilding != null) {
            buildOnCell(uid, cell)
            return
        }

        // Взаимодействие со зданиями
        handleBuildingInteraction(uid, cell, game)
    }

    private fun attackEnemyTownHall(attackerUid: String, targetUid: String, game: MultiplayerGame) {
        Log.d(TAG, "Начало атаки: атакующий=$attackerUid, цель=$targetUid")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val myLogicSnapshot = database.child("multiplayer_games").child(gameId).child("players").child(attackerUid).child("gameLogic").get().await()
                val myLogic = FirebaseGameMapper.parseGameLogic(myLogicSnapshot) ?: return@launch

                val aliveUnits = myLogic.player.units.filter { it.health > 0 }
                if (aliveUnits.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MultiplayerGameActivity, "Нет живых юнитов для атаки!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val success = multiplayerLogic.makeTurn(
                    gameId, attackerUid,
                    listOf(GameAction.AttackEnemyTownHall(targetUid))
                )

                if (success) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MultiplayerGameActivity,
                            "Атака на ратушу ${game.players[targetUid]?.displayName}!",
                            Toast.LENGTH_SHORT
                        ).show()

                        // СИЛЬНОЕ ПРИНУДИТЕЛЬНОЕ ОБНОВЛЕНИЕ
                        forceRefreshGameData()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка атаки на ратушу", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MultiplayerGameActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // МЕТОД ДЛЯ СИЛЬНОГО ПРИНУДИТЕЛЬНОГО ОБНОВЛЕНИЯ
    private fun forceRefreshGameData() {
        Log.d(TAG, "Принудительное обновление данных игры")

        // Сбрасываем все кэши
        lastSharedMapHash = 0
        lastMapUpdate = 0
        lastUpdate = 0

        // Принудительно перезагружаем данные игры
        lifecycleScope.launch {
            try {
                val snapshot = database.child("multiplayer_games").child(gameId).get().await()
                val game = FirebaseGameMapper.safeGetMultiplayerGame(snapshot)
                withContext(Dispatchers.Main) {
                    game?.let {
                        currentGame = it
                        updateGameUI(it)

                        // Дополнительное обновление через небольшой промежуток времени
                        lifecycleScope.launch {
                            delay(500) // Ждем полсекунды для применения изменений в Firebase
                            lastSharedMapHash = 0
                            updateGameUI(it)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка принудительного обновления", e)
            }
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

                    // Принудительное обновление карты
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
                is Building.TownHall -> showEraMenu(uid)
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
                    // Принудительное обновление после завершения хода
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

        // Обязательно удаляем слушатели
        gameListener?.let {
            database.child("multiplayer_games").child(gameId).removeEventListener(it)
        }

        playersListener?.let {
            database.child("multiplayer_games").child(gameId).child("players").removeEventListener(it)
        }

        mapListener?.let {
            database.child("multiplayer_games").child(gameId).child("sharedMap").removeEventListener(it)
        }

        Log.d(TAG, "MultiplayerGameActivity уничтожена, слушатели удалены")
    }
}