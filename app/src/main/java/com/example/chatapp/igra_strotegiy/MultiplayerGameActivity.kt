package com.example.chatapp.igra_strotegiy

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
    private lateinit var btnBuild: ImageButton
    private lateinit var btnEndTurn: ImageButton
    private lateinit var btnLeaveGame: ImageButton
    private lateinit var resourcesContainer: LinearLayout
    private lateinit var unitsContainer: LinearLayout
    private lateinit var tvPlayerEra: TextView
    private lateinit var tvPlayersSummary: TextView
    // TextView для всех ресурсов
    private lateinit var tvFood: TextView
    private lateinit var tvWood: TextView
    private lateinit var tvWater: TextView
    private lateinit var tvStone: TextView
    private lateinit var tvGold: TextView
    private lateinit var tvIron: TextView
    private lateinit var tvCoal: TextView
    private lateinit var tvOil: TextView
    private lateinit var tvEnergy: TextView
    // TextView для юнитов
    private lateinit var tvCaveman: TextView
    private lateinit var tvHunter: TextView
    private lateinit var tvMammothRider: TextView
    private lateinit var tvSwordsman: TextView
    private lateinit var tvArcher: TextView
    private var currentGame: MultiplayerGame? = null
    private var currentUser: User? = null
    private var isSpectator = false
    private val gameId by lazy { intent.getStringExtra("GAME_ID") ?: "" }
    private var selectedBuilding: Building? = null
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
    // 🔥 ДЛЯ ВЫГРУЗКИ
    private var isUnloadMode = false

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
        tvPlayerEra = findViewById(R.id.tvPlayerEra)
        tvPlayersSummary = findViewById(R.id.tvPlayersSummary)
        resourcesContainer = findViewById(R.id.resourcesContainer)
        unitsContainer = findViewById(R.id.unitsContainer)
        // Инициализация всех TextView для ресурсов
        tvFood = findViewById(R.id.tvFood)
        tvWood = findViewById(R.id.tvWood)
        tvWater = findViewById(R.id.tvWater)
        tvStone = findViewById(R.id.tvStone)
        tvGold = findViewById(R.id.tvGold)
        tvIron = findViewById(R.id.tvIron)
        tvCoal = findViewById(R.id.tvCoal)
        tvOil = findViewById(R.id.tvOil)
        tvEnergy = findViewById(R.id.tvEnergy)
        // Инициализация TextView для юнитов
        tvCaveman = findViewById(R.id.tvCaveman)
        tvHunter = findViewById(R.id.tvHunter)
        tvMammothRider = findViewById(R.id.tvMammothRider)
        tvSwordsman = findViewById(R.id.tvSwordsman)
        tvArcher = findViewById(R.id.tvArcher)
        // Настройка кликов
        btnBuild.setOnClickListener { showBuildingMenu() }
        btnEndTurn.setOnClickListener { endTurn() }
        btnLeaveGame.setOnClickListener { showLeaveConfirmation() }
        updateUIForSpectator()
    }

    private fun showLeaveConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Покинуть игру")
            .setMessage("Вы уверены, что хотите покинуть игру?")
            .setPositiveButton("Да") { _, _ -> leaveGame() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showLoadingState() {
        tvGameStatus.text = "Загрузка игры..."
        tvCurrentPlayer.text = "Загрузка..."
        tvPlayerEra.text = ""
        tvPlayersSummary.text = "0/0"
        // Скрываем статические TextView до загрузки данных
        hideAllResourceViews()
        hideAllUnitViews()
    }

    private fun hideAllResourceViews() {
        tvFood.visibility = View.GONE
        tvWood.visibility = View.GONE
        tvWater.visibility = View.GONE
        tvStone.visibility = View.GONE
        tvGold.visibility = View.GONE
        tvIron.visibility = View.GONE
        tvCoal.visibility = View.GONE
        tvOil.visibility = View.GONE
        tvEnergy.visibility = View.GONE
    }

    private fun hideAllUnitViews() {
        tvCaveman.visibility = View.GONE
        tvHunter.visibility = View.GONE
        tvMammothRider.visibility = View.GONE
        tvSwordsman.visibility = View.GONE
        tvArcher.visibility = View.GONE
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
            tvCurrentPlayer.text = "Вы: ${player.displayName}"
            tvPlayerEra.text = eraName
            // Обновляем ресурсы и юниты
            updateResourcesDisplay(player.gameLogic.player.resources, player.gameLogic.player.era)
            updateUnitsDisplay(player.gameLogic.player.units)
        } else {
            tvCurrentPlayer.text = "Наблюдатель"
            tvPlayerEra.text = ""
            clearResourcesAndUnits()
        }
    }

    private fun showTransportBargeMenu(uid: String, transport: Army, cell: MapCell, sharedMap: GameMap) {
        val game = currentGame ?: return
        val myLogic = game.players[uid]?.gameLogic ?: return

        // 🔥 ВАЖНО: Берем актуальные данные транспорта из текущего состояния игры
        val actualTransport = myLogic.armies.find { it.id == transport.id }
        if (actualTransport == null) {
            Toast.makeText(this, "Транспорт не найден", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "=== TRANSPORT BARGEE DEBUG ===")
        Log.d(TAG, "Transport ID: ${actualTransport.id}")
        Log.d(TAG, "Position: (${actualTransport.position.x}, ${actualTransport.position.y})")
        Log.d(TAG, "Has moved: ${actualTransport.hasMovedThisTurn}")
        Log.d(TAG, "Carried army exists: ${actualTransport.carriedArmy != null}")
        Log.d(TAG, "Carried army units count: ${actualTransport.carriedArmy?.units?.size ?: 0}")

        val dialog = AlertDialog.Builder(this)
            .setTitle("Транспортный барж")
            .setNegativeButton("Закрыть") { d, _ ->
                d.dismiss()
            }
            .create()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // ОСНОВНАЯ ИНФОРМАЦИЯ
        val info = TextView(this).apply {
            text = "📍 Координаты: (${actualTransport.position.x}, ${actualTransport.position.y})\n" +
                    "⚡ Состояние: ${if (actualTransport.hasMovedThisTurn) "Уже ходил" else "Может ходить"}\n" +
                    "📦 Груз: ${if (actualTransport.carriedArmy != null) "${actualTransport.carriedArmy!!.units.size} юнитов" else "нет"}"
            setPadding(0, 0, 0, 20)
        }
        layout.addView(info)

        // 🔥 КНОПКА ПЕРЕМЕЩЕНИЯ
        if (!actualTransport.hasMovedThisTurn) {
            val btnMove = Button(this).apply {
                text = "🔄 Переместить корабль"
                setOnClickListener {
                    dialog.dismiss()
                    selectedArmy = actualTransport
                    Toast.makeText(this@MultiplayerGameActivity,
                        "Транспорт выбран. Кликните на клетку моря для перемещения.",
                        Toast.LENGTH_LONG).show()
                }
            }
            layout.addView(btnMove)
        } else {
            val movedInfo = TextView(this).apply {
                text = "⏹️ Корабль уже перемещался в этом ходу"
                setTextColor(Color.GRAY)
            }
            layout.addView(movedInfo)
        }

        // 🔥 КНОПКА ВЫГРУЗКИ АРМИИ - ТОЛЬКО РЕЖИМ КЛИКА
        if (actualTransport.carriedArmy != null) {
            Log.d(TAG, "SHOWING UNLOAD BUTTON - Transport has cargo: ${actualTransport.carriedArmy!!.units.size} units")

            val cargo = actualTransport.carriedArmy!!
            val cargoInfo = TextView(this).apply {
                text = "\n📦 ГРУЗ НА БОРТУ:\n" +
                        "• ${cargo.units.size} юнитов\n" +
                        "• Типы: ${cargo.units.groupBy { it.name }.map { "${it.key} (${it.value.size})" }.joinToString(", ")}"
                setPadding(0, 16, 0, 16)
                setTextColor(ContextCompat.getColor(this@MultiplayerGameActivity, R.color.primaryDarkColor))
            }
            layout.addView(cargoInfo)

            if (!actualTransport.hasMovedThisTurn) {
                val btnUnload = Button(this).apply {
                    text = "🚪 ВЫСАДИТЬ АРМИЮ"
                    setBackgroundColor(ContextCompat.getColor(this@MultiplayerGameActivity, R.color.accent))
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setPadding(0, 20, 0, 20)
                    setOnClickListener {
                        Log.d(TAG, "Unload mode activated for transport ${actualTransport.id}")
                        dialog.dismiss()
                        selectedArmy = actualTransport
                        isUnloadMode = true
                        Toast.makeText(this@MultiplayerGameActivity,
                            "Режим выгрузки: кликните на клетку суши в радиусе 3 клеток от транспорта\n\n" +
                                    "📍 Транспорт: (${actualTransport.position.x}, ${actualTransport.position.y})",
                            Toast.LENGTH_LONG).show()
                    }
                }
                layout.addView(btnUnload)
            } else {
                val cannotUnload = TextView(this).apply {
                    text = "❌ Нельзя высадить: корабль уже перемещался в этом ходу"
                    setTextColor(ContextCompat.getColor(this@MultiplayerGameActivity, android.R.color.holo_red_dark))
                    setPadding(0, 10, 0, 10)
                }
                layout.addView(cannotUnload)
            }
        } else {
            Log.d(TAG, "NO UNLOAD BUTTON - Transport has NO cargo")

            // ЗАГРУЗКА АРМИИ
            val loadTitle = TextView(this).apply {
                text = "\n⬆️ ЗАГРУЗКА АРМИИ"
                setPadding(0, 16, 0, 8)
                setTextColor(Color.DKGRAY)
            }
            layout.addView(loadTitle)

            val adjacentArmies = mutableListOf<Army>()
            for (dx in -1..1) {
                for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = actualTransport.position.x + dx
                    val ny = actualTransport.position.y + dy
                    val armyHere = myLogic.armies.find {
                        it.position.x == nx && it.position.y == ny &&
                                it.isAlive() &&
                                !it.isNaval() && // только сухопутные армии
                                it.id != actualTransport.id // не сам транспорт
                    }
                    if (armyHere != null) {
                        adjacentArmies.add(armyHere)
                        Log.d(TAG, "Found adjacent army for loading: ${armyHere.id} at ($nx, $ny) with ${armyHere.units.size} units")
                    }
                }
            }

            if (adjacentArmies.isEmpty()) {
                val noArmy = TextView(this).apply {
                    text = "❌ Нет сухопутных армий рядом для загрузки"
                    setTextColor(ContextCompat.getColor(this@MultiplayerGameActivity, android.R.color.holo_red_dark))
                    setPadding(0, 10, 0, 10)
                }
                layout.addView(noArmy)
            } else {
                for (army in adjacentArmies) {
                    val btn = Button(this).apply {
                        text = "⬆️ Загрузить армию (${army.units.size} юнитов)"
                        setOnClickListener {
                            Log.d(TAG, "Loading army: ${army.id} into transport: ${actualTransport.id}")
                            dialog.dismiss()
                            lifecycleScope.launch {
                                try {
                                    val success = multiplayerLogic.makeTurn(
                                        gameId, uid,
                                        listOf(GameAction.LoadArmyIntoTransport(actualTransport.id, army.id))
                                    )
                                    if (success) {
                                        Toast.makeText(this@MultiplayerGameActivity, "Армия загружена!", Toast.LENGTH_SHORT).show()
                                        updatePlayerState(uid)
                                        lastSharedMapHash = 0
                                        reloadGameData()
                                    } else {
                                        Toast.makeText(this@MultiplayerGameActivity, "Ошибка загрузки армии", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Ошибка загрузки армии", e)
                                    Toast.makeText(this@MultiplayerGameActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    layout.addView(btn)
                }
            }
        }

        // РАЗДЕЛИТЕЛЬ
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            ).apply {
                setMargins(0, 20, 0, 20)
            }
            setBackgroundColor(Color.LTGRAY)
        }
        layout.addView(divider)

        // ИНФОРМАЦИЯ О ТРАНСПОРТЕ
        val transportInfo = TextView(this).apply {
            text = "💡 Информация о транспорте:\n" +
                    "• Может перевозить 1 сухопутную армию\n" +
                    "• Может загружать/высаживать за 1 ход\n" +
                    "• Высаживает на сушу в радиусе 3 клеток\n" +
                    "• Не может атаковать"
            setTextColor(Color.DKGRAY)
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }
        layout.addView(transportInfo)

        dialog.setView(layout)
        dialog.show()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun renderSharedMapOptimized(game: MultiplayerGame, sharedMap: GameMap) {
        if (isRendering) return
        isRendering = true

        lifecycleScope.launch(Dispatchers.Default) {
            val (renderLogic, allPlayers) = prepareRenderLogic(game, sharedMap)
            withContext(Dispatchers.Main) {
                try {
                    // Размер карты в пикселях: 13 клеток × 80dp
                    val cellSizeDp = 80
                    val mapWidthPx = dpToPx(sharedMap.width * cellSizeDp)
                    val mapHeightPx = dpToPx(sharedMap.height * cellSizeDp)

                    val mapRoot = FrameLayout(this@MultiplayerGameActivity).apply {
                        layoutParams = FrameLayout.LayoutParams(mapWidthPx, mapHeightPx)
                    }

                    val seaBg = ImageView(this@MultiplayerGameActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageResource(R.drawable.voda)
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                    mapRoot.addView(seaBg, 0)

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
                    val islandsView = renderer.render()
                    mapRoot.addView(islandsView)

                    swapMapView(mapRoot)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка рендеринга карты", e)
                    showMapError("Ошибка отображения карты")
                } finally {
                    isRendering = false
                }
            }
        }
    }

    private fun updateResourcesDisplay(resources: Resource, era: Era) {
        // Сначала скрываем все
        hideAllResourceViews()
        val resourceMap = resources.getAvailableResourcesMap(era)
        // Обновляем все TextView для ресурсов
        resourceMap.forEach { (resource, amount) ->
            when (resource) {
                "food" -> {
                    tvFood.text = "🍎 $amount"
                    tvFood.visibility = View.VISIBLE
                }
                "wood" -> {
                    tvWood.text = "🪵 $amount"
                    tvWood.visibility = View.VISIBLE
                }
                "water" -> {
                    tvWater.text = "💧 $amount"
                    tvWater.visibility = View.VISIBLE
                }
                "stone" -> {
                    tvStone.text = "⛰️ $amount"
                    tvStone.visibility = View.VISIBLE
                }
                "gold" -> {
                    tvGold.text = "💰 $amount"
                    tvGold.visibility = View.VISIBLE
                }
                "iron" -> {
                    tvIron.text = "⚙️ $amount"
                    tvIron.visibility = View.VISIBLE
                }
                "coal" -> {
                    tvCoal.text = "🪨 $amount"
                    tvCoal.visibility = View.VISIBLE
                }
                "oil" -> {
                    tvOil.text = "🛢️ $amount"
                    tvOil.visibility = View.VISIBLE
                }
                "energy" -> {
                    tvEnergy.text = "⚡ $amount"
                    tvEnergy.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun updateUnitsDisplay(units: List<GameUnit>) {
        // Сначала скрываем все
        hideAllUnitViews()
        val aliveUnits = units.filter { it.health > 0 }
        val unitCounts = aliveUnits.groupBy { it.name }
        // Обновляем TextView для юнитов
        unitCounts.forEach { (name, unitList) ->
            when (name) {
                "Пещерный человек" -> {
                    tvCaveman.text = "🪓 ${unitList.size}"
                    tvCaveman.visibility = View.VISIBLE
                }
                "Охотник" -> {
                    tvHunter.text = "🏹 ${unitList.size}"
                    tvHunter.visibility = View.VISIBLE
                }
                "Всадник на мамонте" -> {
                    tvMammothRider.text = "🐘 ${unitList.size}"
                    tvMammothRider.visibility = View.VISIBLE
                }
                "Мечник" -> {
                    tvSwordsman.text = "⚔️ ${unitList.size}"
                    tvSwordsman.visibility = View.VISIBLE
                }
                "Лучник" -> {
                    tvArcher.text = "🎯 ${unitList.size}"
                    tvArcher.visibility = View.VISIBLE
                }
            }
        }
        // Если нет юнитов, показываем сообщение
        if (unitCounts.isEmpty()) {
            tvCaveman.text = "⚔️ 0"
            tvCaveman.visibility = View.VISIBLE
        }
    }

    private fun clearResourcesAndUnits() {
        hideAllResourceViews()
        hideAllUnitViews()
        // Показываем нулевые значения для наблюдателя
        tvFood.text = "🍎 0"
        tvFood.visibility = View.VISIBLE
        tvCaveman.text = "⚔️ 0"
        tvCaveman.visibility = View.VISIBLE
    }

    private fun updateButtons(game: MultiplayerGame) {
        val isMyTurn = isMyTurn() && game.gameState == GameState.IN_PROGRESS && !isSpectator
        btnBuild.isEnabled = isMyTurn
        btnEndTurn.isEnabled = isMyTurn
        btnEndTurn.alpha = if (isMyTurn) 1.0f else 0.5f
    }

    private fun isMyTurn(): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        return currentGame?.isPlayerTurn(uid) == true
    }

    // 🔥 МЕТОДЫ ДЛЯ КОНТЕКСТНЫХ МЕНЮ КОРАБЛЕЙ

    private fun showWarshipMenu(uid: String, game: MultiplayerGame, army: Army, currentCell: MapCell) {
        if (army.hasMovedThisTurn) {
            Toast.makeText(this, "Корабль уже ходил в этом ходу", Toast.LENGTH_SHORT).show()
            return
        }
        val options = mutableListOf("Переместиться")
        val canAttack = canAttackFromNavalPosition(uid, army, game)
        if (canAttack) {
            options.add("Атаковать")
        }
        AlertDialog.Builder(this)
            .setTitle("Военный галеон")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        selectedArmy = army
                        Toast.makeText(this, "Выберите клетку моря для перемещения.", Toast.LENGTH_LONG).show()
                    }
                    1 -> {
                        if (canAttack) {
                            showNavalAttackTargetSelection(uid, army, game)
                        }
                    }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun canAttackFromNavalPosition(uid: String, army: Army, game: MultiplayerGame): Boolean {
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = army.position.x + dx
                val ny = army.position.y + dy
                for ((otherUid, otherPlayer) in game.players) {
                    if (otherUid == uid) continue
                    if (otherPlayer.gameLogic.armies.any {
                            it.position.x == nx && it.position.y == ny && it.isAlive()
                        }) {
                        return true
                    }
                    val th = otherPlayer.gameLogic.player.townHallPosition
                    if (th.x == nx && th.y == ny) return true
                }
            }
        }
        return false
    }

    private fun showNavalAttackTargetSelection(uid: String, army: Army, game: MultiplayerGame) {
        val targets = mutableListOf<Pair<String, String>>()
        val positions = mutableListOf<Position>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = army.position.x + dx
                val ny = army.position.y + dy
                for ((otherUid, otherPlayer) in game.players) {
                    if (otherUid == uid) continue
                    val enemyArmy = otherPlayer.gameLogic.armies.find {
                        it.position.x == nx && it.position.y == ny && it.isAlive()
                    }
                    if (enemyArmy != null) {
                        targets.add("Армия ${otherPlayer.displayName} (${enemyArmy.units.size} юн.)" to "army")
                        positions.add(Position(nx, ny))
                    }
                    val th = otherPlayer.gameLogic.player.townHallPosition
                    if (th.x == nx && th.y == ny) {
                        val townHall = otherPlayer.gameLogic.player.buildings.find { it is Building.TownHall && !it.isDestroyed() }
                        if (townHall != null) {
                            targets.add("Ратуша ${otherPlayer.displayName}" to "town_hall")
                            positions.add(Position(nx, ny))
                        }
                    }
                }
            }
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, "Нет целей для атаки", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Выберите цель для атаки")
            .setItems(targets.map { it.first }.toTypedArray()) { _, index ->
                val pos = positions[index]
                showBattlePreview(uid, army, pos.x, pos.y, game)
            }
            .show()
    }

    private fun handleCellClickOnSharedMap(cell: MapCell, game: MultiplayerGame, sharedMap: GameMap) {
        val uid = auth.currentUser?.uid ?: return

        Log.d(TAG, "=== CELL CLICK ===")
        Log.d(TAG, "Cell: (${cell.x}, ${cell.y}) type: ${cell.type}")
        Log.d(TAG, "SelectedArmy: ${selectedArmy?.id}, SelectedBuilding: ${selectedBuilding?.name}")

        // 🔥 ПРОВЕРКА РЕЖИМА ВЫГРУЗКИ
        if (isUnloadMode && selectedArmy != null && selectedArmy?.isTransport() == true) {
            val transport = selectedArmy!!
            Log.d(TAG, "Unload mode: transport at (${transport.position.x}, ${transport.position.y}) -> target (${cell.x}, ${cell.y})")

            val dx = abs(transport.position.x - cell.x)
            val dy = abs(transport.position.y - cell.y)
            val distance = dx + dy

            if (distance <= 3 && (cell.type == "empty" || cell.type == "town_hall")) {
                // Проверяем, что клетка не занята
                var isOccupied = false
                for ((otherUid, otherPlayer) in game.players) {
                    if (otherPlayer.gameLogic.armies.any {
                            it.position.x == cell.x && it.position.y == cell.y && it.isAlive()
                        }) {
                        isOccupied = true
                        break
                    }
                }

                if (!isOccupied) {
                    executeUnload(uid, transport, cell.x, cell.y)
                } else {
                    Toast.makeText(this, "Клетка занята другой армией", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Нельзя выгрузить здесь: слишком далеко или не суша", Toast.LENGTH_SHORT).show()
            }

            isUnloadMode = false
            selectedArmy = null
            return
        }

        // 🔥 1. ЕСЛИ УЖЕ ВЫБРАН КОРАБЛЬ ДЛЯ ПЕРЕМЕЩЕНИЯ (ВКЛЮЧАЯ РЫБОЛОВНЫЙ)
        if (selectedArmy != null && selectedArmy?.isNaval() == true) {
            val army = selectedArmy!!
            Log.d(TAG, "Processing naval movement for: ${army.id} at (${army.position.x}, ${army.position.y}) -> target (${cell.x}, ${cell.y})")

            // Проверяем, можно ли переместиться в эту клетку
            val dx = abs(army.position.x - cell.x)
            val dy = abs(army.position.y - cell.y)

            Log.d(TAG, "Movement distance: dx=$dx, dy=$dy, total=${dx + dy}")
            Log.d(TAG, "Target cell type: ${cell.type}")

            if (dx + dy <= 2 && cell.type == "sea") {
                // Проверяем, не занята ли клетка другими кораблями
                var isOccupied = false
                for ((otherUid, otherPlayer) in game.players) {
                    val occupyingArmy = otherPlayer.gameLogic.armies.find {
                        it.id != army.id && // кроме выбранного
                                it.position.x == cell.x && it.position.y == cell.y &&
                                it.isAlive() && it.isNaval()
                    }
                    if (occupyingArmy != null) {
                        isOccupied = true
                        Log.d(TAG, "Cell occupied by naval army: ${occupyingArmy.id} from player $otherUid")
                        break
                    }
                }

                if (!isOccupied) {
                    lifecycleScope.launch {
                        try {
                            Log.d(TAG, "Attempting to move naval army ${army.id} to (${cell.x}, ${cell.y})")
                            val success = multiplayerLogic.makeTurn(
                                gameId, uid,
                                listOf(GameAction.MoveArmy(army.id, cell.x, cell.y))
                            )
                            if (success) {
                                Toast.makeText(this@MultiplayerGameActivity, "Корабль перемещен!", Toast.LENGTH_SHORT).show()
                                updatePlayerState(uid)
                                lastSharedMapHash = 0
                                currentGame?.let { updateGameUI(it) }
                            } else {
                                Toast.makeText(this@MultiplayerGameActivity, "Не удалось переместить корабль", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка перемещения корабля", e)
                            Toast.makeText(this@MultiplayerGameActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Клетка занята другим кораблем", Toast.LENGTH_SHORT).show()
                }
            } else {
                val errorMsg = when {
                    cell.type != "sea" -> "Корабль может перемещаться только по морю"
                    dx + dy > 2 -> "Корабль может перемещаться не более чем на 2 клетки"
                    else -> "Нельзя переместиться в эту клетку"
                }
                Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
            }
            selectedArmy = null
            return
        }

        // 🔥 2. ЕСЛИ УЖЕ ВЫБРАНА СУХОПУТНАЯ АРМИЯ ДЛЯ ПЕРЕМЕЩЕНИЯ/АТАКИ
        if (selectedArmy != null) {
            val army = selectedArmy!!
            Log.d(TAG, "Processing land army movement for: ${army.id} at (${army.position.x}, ${army.position.y}) -> target (${cell.x}, ${cell.y})")

            val dx = abs(army.position.x - cell.x)
            val dy = abs(army.position.y - cell.y)

            // Проверка занятости целевой клетки (ратуша или чужая армия)
            var isOccupied = false
            var defenderUid: String? = null
            for ((otherUid, otherPlayer) in game.players) {
                if (otherUid == uid) continue

                // Проверяем вражескую ратушу
                val pos = otherPlayer.gameLogic.player.townHallPosition
                if (pos.x == cell.x && pos.y == cell.y) {
                    isOccupied = true
                    defenderUid = otherUid
                    Log.d(TAG, "Target is enemy town hall of player $otherUid")
                    break
                }

                // Проверяем вражеские армии
                val enemyArmy = otherPlayer.gameLogic.armies.find {
                    it.position.x == cell.x && it.position.y == cell.y && it.isAlive()
                }
                if (enemyArmy != null) {
                    isOccupied = true
                    defenderUid = otherUid
                    Log.d(TAG, "Target is enemy army: ${enemyArmy.id} of player $otherUid")
                    break
                }
            }

            if (isOccupied) {
                // АТАКА
                if (dx + dy == 1) {
                    Log.d(TAG, "Initiating attack on target at (${cell.x}, ${cell.y})")
                    showBattlePreview(uid, army, cell.x, cell.y, game)
                } else {
                    Toast.makeText(this, "Для атаки армия должна быть на соседней клетке", Toast.LENGTH_SHORT).show()
                }
                selectedArmy = null
                return
            }

            // ОБЫЧНОЕ ПЕРЕМЕЩЕНИЕ
            val canMoveHere = if (army.isNaval()) {
                cell.type == "sea"
            } else {
                cell.type == "empty" || cell.type == "town_hall"
            }

            if (dx + dy <= 2 && canMoveHere) {
                // Дополнительная проверка на занятость клетки своими юнитами
                var isOccupiedByAlly = false
                val myLogic = game.players[uid]?.gameLogic
                if (myLogic != null) {
                    val allyArmy = myLogic.armies.find {
                        it.id != army.id && it.position.x == cell.x && it.position.y == cell.y && it.isAlive()
                    }
                    if (allyArmy != null) {
                        isOccupiedByAlly = true
                        Log.d(TAG, "Cell occupied by ally army: ${allyArmy.id}")
                    }
                }

                if (!isOccupiedByAlly) {
                    lifecycleScope.launch {
                        try {
                            Log.d(TAG, "Attempting to move army ${army.id} to (${cell.x}, ${cell.y})")
                            val success = multiplayerLogic.makeTurn(
                                gameId, uid,
                                listOf(GameAction.MoveArmy(army.id, cell.x, cell.y))
                            )
                            if (success) {
                                Toast.makeText(this@MultiplayerGameActivity, "Армия движется!", Toast.LENGTH_SHORT).show()
                                updatePlayerState(uid)
                                lastSharedMapHash = 0
                                currentGame?.let { updateGameUI(it) }
                            } else {
                                Toast.makeText(this@MultiplayerGameActivity, "Не удалось переместить армию", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка перемещения армии", e)
                            Toast.makeText(this@MultiplayerGameActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Клетка занята вашей другой армией", Toast.LENGTH_SHORT).show()
                }
            } else {
                val errorMsg = when {
                    !canMoveHere -> "Нельзя переместиться в эту клетку (тип: ${cell.type})"
                    dx + dy > 2 -> "Армия может перемещаться не более чем на 2 клетки"
                    else -> "Нельзя двигаться сюда"
                }
                Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
            }
            selectedArmy = null
            return
        }

        // 🔥 3. ПРОВЕРКА: КЛИК ПО СВОЕЙ АРМИИ ИЛИ КОРАБЛЮ
        val myLogic = game.players[uid]?.gameLogic ?: return
        val myArmiesHere = myLogic.armies.filter { it.position.x == cell.x && it.position.y == cell.y && it.isAlive() }
        if (myArmiesHere.isNotEmpty()) {
            val clickedArmy = myArmiesHere.first()
            Log.d(TAG, "Clicked on own army: ${clickedArmy.id}, naval: ${clickedArmy.isNaval()}, units: ${clickedArmy.units.size}")

            if (clickedArmy.units.isNotEmpty()) {
                val unit = clickedArmy.units[0]
                Log.d(TAG, "First unit type: ${unit.javaClass.simpleName}, name: ${unit.name}")
            }

            // 🔥 ЛОГИКА ДЛЯ КОРАБЛЕЙ
            if (clickedArmy.isNaval()) {
                val unit = clickedArmy.units.getOrNull(0)
                Log.d(TAG, "Naval unit type: ${unit?.javaClass?.simpleName}")
                when (unit) {
                    is GameUnit.TransportBarge -> {
                        Log.d(TAG, "Showing TransportBarge menu")
                        showTransportBargeMenu(uid, clickedArmy, cell, sharedMap)
                    }
                    is GameUnit.WarGalley -> {
                        Log.d(TAG, "Showing WarGalley menu")
                        showWarshipMenu(uid, game, clickedArmy, cell)
                    }
                    is GameUnit.FishingBoat -> {
                        Log.d(TAG, "Showing FishingBoat menu")
                        showFishingBoatMenu(uid, clickedArmy)
                    }
                    else -> {
                        Log.d(TAG, "Unknown naval unit, default selection")
                        selectedArmy = clickedArmy
                        Toast.makeText(this, "Корабль выбран. Кликните на клетку моря для перемещения.", Toast.LENGTH_LONG).show()
                    }
                }
                return
            }

            // ОБЫЧНАЯ СУХОПУТНАЯ АРМИЯ
            selectedArmy = clickedArmy
            Toast.makeText(this, "Армия выбрана. Кликните на клетку для перемещения или атаки.", Toast.LENGTH_LONG).show()
            return
        }

        // 🔥 4. ПРОВЕРКА ВРАЖЕСКИХ ЦЕЛЕЙ
        var enemyTargetFound = false
        for ((otherUid, otherPlayer) in game.players) {
            if (otherUid == uid) continue

            // Проверяем вражеские армии
            val enemyArmies = otherPlayer.gameLogic.armies.filter {
                it.position.x == cell.x && it.position.y == cell.y && it.isAlive()
            }
            if (enemyArmies.isNotEmpty()) {
                enemyTargetFound = true
                Log.d(TAG, "Found enemy army at (${cell.x}, ${cell.y}) from player $otherUid")
                showArmySelectionForAttack(uid, cell.x, cell.y)
                break
            }

            // Проверяем вражескую ратушу
            val isEnemyTownHall = otherPlayer.gameLogic.player.townHallPosition.x == cell.x &&
                    otherPlayer.gameLogic.player.townHallPosition.y == cell.y
            if (isEnemyTownHall) {
                enemyTargetFound = true
                Log.d(TAG, "Found enemy town hall at (${cell.x}, ${cell.y}) from player $otherUid")
                showArmySelectionForAttack(uid, cell.x, cell.y)
                break
            }
        }
        if (enemyTargetFound) return

        // 🔥 5. СВОЯ РАТУША
        val myPos = myLogic.player.townHallPosition
        if (cell.type == "town_hall" && myPos.x == cell.x && myPos.y == cell.y) {
            Log.d(TAG, "Clicked on own town hall")
            showTownHallMenu(uid)
            return
        }

        // 🔥 6. СТРОИТЕЛЬСТВО
        if (cell.type == "empty" && selectedBuilding != null) {
            Log.d(TAG, "Building ${selectedBuilding?.name} at (${cell.x}, ${cell.y})")
            buildOnCell(uid, cell)
            return
        }
        // 🔥 ВЗАИМОДЕЙСТВИЕ СО ЗДАНИЯМИ
        handleBuildingInteraction(uid, cell, game)

        // 🔥 8. ЕСЛИ НИЧЕГО НЕ ВЫБРАНО - СБРОС И СООБЩЕНИЕ
        if (selectedBuilding != null) {
            Toast.makeText(this, "Строительство отменено - выберите пустую клетку", Toast.LENGTH_SHORT).show()
            selectedBuilding = null
        } else {
            Log.d(TAG, "Cell click handled but no action taken")
        }
    }



    private fun showUnloadTargetSelection(uid: String, transport: Army, game: MultiplayerGame) {
        Log.d(TAG, "=== SHOW UNLOAD TARGET SELECTION ===")

        lifecycleScope.launch {
            try {
                val sharedMapSnapshot = database.child("multiplayer_games").child(gameId).child("sharedMap").get().await()
                val sharedMap = sharedMapSnapshot.getValue(GameMap::class.java) ?: GameMap()

                Log.d(TAG, "SharedMap loaded: width=${sharedMap.width}, height=${sharedMap.height}")

                withContext(Dispatchers.Main) {
                    showUnloadTargetSelectionWithMap(uid, transport, game, sharedMap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки sharedMap", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MultiplayerGameActivity, "Ошибка загрузки карты", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showUnloadTargetSelectionWithMap(uid: String, transport: Army, game: MultiplayerGame, sharedMap: GameMap) {
        Log.d(TAG, "=== UNLOAD DIALOG WITH MAP ===")

        if (transport.carriedArmy == null) {
            Toast.makeText(this, "В транспорте нет армии для выгрузки", Toast.LENGTH_SHORT).show()
            return
        }

        val validCells = findValidUnloadCells(transport, game, sharedMap)
        Log.d(TAG, "Found ${validCells.size} valid cells for unloading")

        if (validCells.isEmpty()) {
            Toast.makeText(this,
                "Нет свободных клеток суши в радиусе 3 клеток для выгрузки",
                Toast.LENGTH_LONG).show()
            return
        }

        // 🔥 ПРОСТОЙ ДИАЛОГ С ВЫБОРОМ КЛЕТОК
        val options = validCells.sortedBy { (x, y) ->
            abs(x - transport.position.x) + abs(y - transport.position.y)
        }.map { (x, y) ->
            val distance = abs(x - transport.position.x) + abs(y - transport.position.y)
            val cellType = sharedMap.getCellType(x, y)
            val cellTypeText = when (cellType) {
                "town_hall" -> " (ваша ратуша)"
                "empty" -> " (пустая)"
                else -> " ($cellType)"
            }
            "📍 ($x, $y) - расстояние: $distance$cellTypeText"
        }.toTypedArray()

        Log.d(TAG, "Creating dialog with ${options.size} options")

        AlertDialog.Builder(this)
            .setTitle("ВЫБЕРИТЕ КЛЕТКУ ДЛЯ ВЫГРУЗКИ")
            .setMessage("Доступно ${validCells.size} клеток в радиусе 3 клеток")
            .setItems(options) { dialog, which ->
                val (x, y) = validCells[which]
                val distance = abs(x - transport.position.x) + abs(y - transport.position.y)
                Log.d(TAG, "Selected cell ($x, $y), distance: $distance")

                if (distance > 1) {
                    showUnloadConfirmation(uid, transport, x, y, distance)
                } else {
                    executeUnload(uid, transport, x, y)
                }
                dialog.dismiss()
            }
            .setNegativeButton("ОТМЕНА") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun findValidUnloadCells(transport: Army, game: MultiplayerGame, sharedMap: GameMap): List<Pair<Int, Int>> {
        val validCells = mutableListOf<Pair<Int, Int>>()
        val transportX = transport.position.x
        val transportY = transport.position.y

        for (dx in -3..3) {
            for (dy in -3..3) {
                if (abs(dx) + abs(dy) > 3) continue

                val nx = transportX + dx
                val ny = transportY + dy

                if (nx !in 0 until sharedMap.width || ny !in 0 until sharedMap.height) continue

                val cellType = sharedMap.getCellType(nx, ny)
                if (cellType != "empty" && cellType != "town_hall") continue

                // Проверяем, что клетка не занята
                var isOccupied = false
                for ((otherUid, otherPlayer) in game.players) {
                    // Проверяем армии
                    if (otherPlayer.gameLogic.armies.any {
                            it.position.x == nx && it.position.y == ny && it.isAlive()
                        }) {
                        isOccupied = true
                        break
                    }

                    // Проверяем ратуши (только чужие)
                    val pos = otherPlayer.gameLogic.player.townHallPosition
                    if (pos.x == nx && pos.y == ny && otherUid != game.currentTurnUid) {
                        isOccupied = true
                        break
                    }
                }

                if (!isOccupied) {
                    validCells.add(Pair(nx, ny))
                }
            }
        }
        return validCells
    }

    private fun showUnloadConfirmation(uid: String, transport: Army, targetX: Int, targetY: Int, distance: Int) {
        AlertDialog.Builder(this)
            .setTitle("Подтверждение выгрузки")
            .setMessage("Вы уверены, что хотите выгрузить армию на расстояние $distance клеток?\n\n" +
                    "📍 Транспорт: (${transport.position.x}, ${transport.position.y})\n" +
                    "🎯 Цель: ($targetX, $targetY)")
            .setPositiveButton("Да, выгрузить") { _, _ ->
                executeUnload(uid, transport, targetX, targetY)
            }
            .setNegativeButton("Отмена") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun executeUnload(uid: String, transport: Army, targetX: Int, targetY: Int) {
        lifecycleScope.launch {
            try {
                val success = multiplayerLogic.makeTurn(
                    gameId, uid,
                    listOf(GameAction.UnloadArmyFromTransport(transport.id, targetX, targetY))
                )
                if (success) {
                    Toast.makeText(this@MultiplayerGameActivity,
                        "Армия выгружена на клетку ($targetX, $targetY)!",
                        Toast.LENGTH_SHORT).show()
                    updatePlayerState(uid)
                    lastSharedMapHash = 0
                    currentGame?.let { updateGameUI(it) }
                    reloadGameData()
                } else {
                    Toast.makeText(this@MultiplayerGameActivity,
                        "Ошибка выгрузки армии",
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unload error: ${e.message}", e)
                Toast.makeText(this@MultiplayerGameActivity,
                    "Ошибка: ${e.message}",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun reloadGameData() {
        try {
            val gameSnapshot = database.child("multiplayer_games").child(gameId).get().await()
            val updatedGame = FirebaseGameMapper.safeGetMultiplayerGame(gameSnapshot)
            withContext(Dispatchers.Main) {
                updatedGame?.let {
                    currentGame = it
                    updateGameUI(it)
                    lastSharedMapHash = 0
                    lastMapUpdate = 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка перезагрузки данных игры", e)
        }
    }

    private fun showFishingBoatMenu(uid: String, army: Army) {
        if (army.hasMovedThisTurn) {
            Toast.makeText(this, "Рыболовный корабль уже ходил в этом ходу", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf("Переместиться", "Рыбачить")

        AlertDialog.Builder(this)
            .setTitle("Рыболовный корабль")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        selectedArmy = army
                        Toast.makeText(this, "Рыболовный корабль выбран. Кликните на клетку моря для перемещения.", Toast.LENGTH_LONG).show()
                    }
                    1 -> {
                        Toast.makeText(this, "Функция рыбалки в разработке", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // 🔥 ОБНОВЛЁННЫЙ МЕТОД ДЛЯ ВЫБОРА АРМИИ ДЛЯ АТАКИ
    private fun showArmySelectionForAttack(uid: String, targetX: Int, targetY: Int) {
        val game = currentGame ?: return
        val logic = game.players[uid]?.gameLogic ?: return
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

    // 🔥 ПРЕДПРОСМОТР И ВСЕ ОСТАЛЬНЫЕ МЕТОДЫ БОЯ
    private fun showBattlePreview(attackerUid: String, attackerArmy: Army, targetX: Int, targetY: Int, game: MultiplayerGame) {
        var defenderUid: String? = null
        var defenderArmy: Army? = null
        var defenderTownHall: Building.TownHall? = null
        var defenderName: String? = null
        var isTownHallAttack = false
        for ((otherUid, otherPlayer) in game.players) {
            if (otherUid == attackerUid) continue
            val enemyArmy = otherPlayer.gameLogic.armies.find {
                it.position.x == targetX && it.position.y == targetY && it.isAlive()
            }
            if (enemyArmy != null) {
                defenderUid = otherUid
                defenderArmy = enemyArmy
                defenderName = otherPlayer.displayName
                break
            }
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
        showBattleConfirmationDialog(attackerUid, battlePreview)
    }

    private fun showBattleConfirmationDialog(attackerUid: String, battlePreview: BattlePreview) {
        val battleResult = battlePreview.calculateBattleResult()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val tvTitle = TextView(this).apply {
            text = "Предпросмотр боя"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        dialogView.addView(tvTitle)
        val armiesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val attackerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundResource(R.drawable.battle_side_background)
            setPadding(12, 12, 12, 12)
        }
        val tvAttackerTitle = TextView(this).apply {
            text = "Ваша армия"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
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
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 4, 0, 0)
        }
        attackerLayout.addView(tvAttackerPower)
        armiesLayout.addView(attackerLayout)
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 120).apply {
                setMargins(8, 0, 8, 0)
            }
            setBackgroundColor(ContextCompat.getColor(this@MultiplayerGameActivity, R.color.gray))
        }
        armiesLayout.addView(divider)
        val defenderLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundResource(R.drawable.battle_side_background)
            setPadding(12, 12, 12, 12)
        }
        val tvDefenderTitle = TextView(this).apply {
            text = if (battlePreview.isTownHallAttack) "Ратуша противника" else "Армия ${battlePreview.defenderName}"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
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
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 4, 0, 0)
        }
        defenderLayout.addView(tvDefenderPower)
        armiesLayout.addView(defenderLayout)
        dialogView.addView(armiesLayout)
        val victoryChance = calculateVictoryChance(battlePreview)
        val tvPrediction = TextView(this).apply {
            text = "Шанс победы: ${"%.0f".format(victoryChance * 100)}%"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
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

    private fun showBattleVideoAndExecute(attackerUid: String, battlePreview: BattlePreview, battleResult: BattleResult) {
        currentBattlePreview = battlePreview
        val videoResource = R.raw.battle_army_vs_army
        try {
            val videoUri = Uri.parse("android.resource://${packageName}/$videoResource")
            val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.setContentView(R.layout.dialog_battle_video)
            val videoView = dialog.findViewById<VideoView>(R.id.videoView)
            val btnSkip = dialog.findViewById<Button>(R.id.btnSkip)
            val progressBar = dialog.findViewById<ProgressBar>(R.id.progressBar)
            videoView.setVideoURI(videoUri)
            videoView.setOnPreparedListener { mediaPlayer ->
                progressBar.visibility = View.GONE
                mediaPlayer.isLooping = false
                videoView.start()
            }
            videoView.setOnCompletionListener {
                dialog.dismiss()
                executeRealBattle(attackerUid, battlePreview, battleResult)
            }
            videoView.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Ошибка воспроизведения видео: $what, $extra")
                progressBar.visibility = View.GONE
                dialog.dismiss()
                executeRealBattle(attackerUid, battlePreview, battleResult)
                true
            }
            btnSkip.setOnClickListener {
                videoView.stopPlayback()
                dialog.dismiss()
                executeRealBattle(attackerUid, battlePreview, battleResult)
            }
            dialog.setCancelable(false)
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки видео: ${e.message}")
            executeRealBattle(attackerUid, battlePreview, battleResult)
        }
    }

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
                    val actualBattleResult = getActualBattleResult(attackerUid, battlePreview)
                    showBattleResults(attackerUid, actualBattleResult ?: predictedResult)
                } else {
                    Toast.makeText(this@MultiplayerGameActivity, "Ошибка выполнения боя", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MultiplayerGameActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun getActualBattleResult(attackerUid: String, battlePreview: BattlePreview): BattleResult? {
        return withContext(Dispatchers.IO) {
            try {
                val attackerSnapshot = database.child("multiplayer_games").child(gameId)
                    .child("players").child(attackerUid).child("gameLogic").get().await()
                val attackerLogic = FirebaseGameMapper.parseGameLogic(attackerSnapshot)
                val attackerArmy = attackerLogic?.armies?.find { it.id == battlePreview.attackerArmy.id }
                val result = BattleResult()
                if (attackerArmy != null) {
                    result.attackerSurvivedUnits = if (attackerArmy.isAlive()) listOf(attackerArmy) else emptyList()
                    result.attackerPowerRemaining = attackerArmy.totalAttackPower()
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

    private fun formatArmyUnits(army: Army): String {
        val unitGroups = army.units.groupBy { it.name }
        return unitGroups.entries.joinToString("\n") { (name, units) ->
            "• $name: ${units.size} шт. (сила: ${units.sumOf { it.attackPower }})"
        }
    }

    private fun calculateVictoryChance(battlePreview: BattlePreview): Double {
        val attackerPower = battlePreview.attackerTotalPower.toDouble()
        val defenderPower = battlePreview.defenderTotalPower.toDouble()
        return if (attackerPower + defenderPower > 0) {
            attackerPower / (attackerPower + defenderPower)
        } else {
            0.5
        }
    }

    private fun showBattleResults(attackerUid: String, battleResult: BattleResult) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val tvTitle = TextView(this).apply {
            text = "Результаты боя"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        dialogView.addView(tvTitle)
        val tvBattleResult = TextView(this).apply {
            text = battleResult.getResultMessage()
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (battleResult.victory) ContextCompat.getColor(this@MultiplayerGameActivity, R.color.primaryDarkColor)
            else ContextCompat.getColor(this@MultiplayerGameActivity, R.color.red))
            setPadding(0, 0, 0, 16)
        }
        dialogView.addView(tvBattleResult)
        val lossesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val attackerLossLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundResource(R.drawable.battle_result_background)
            setPadding(12, 12, 12, 12)
        }
        val tvAttackerTitle = TextView(this).apply {
            text = "Ваши потери"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
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
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 80).apply {
                setMargins(8, 0, 8, 0)
            }
            setBackgroundColor(ContextCompat.getColor(this@MultiplayerGameActivity, R.color.gray))
        }
        lossesLayout.addView(divider)
        val defenderLossLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundResource(R.drawable.battle_result_background)
            setPadding(12, 12, 12, 12)
        }
        val tvDefenderTitle = TextView(this).apply {
            text = "Потери врага"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
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

    // 🔥 МЕНЮ СТРОИТЕЛЬСТВА И ПРОЧИЕ ДЕЙСТВИЯ

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
                4 -> Building.Shipyard()
                5 -> Building.Barracks()
                6 -> Building.ResearchCenter()
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
            Era.BRONZE_AGE -> arrayOf("Ферма", "Каменоломня", "Золотой рудник", "Кузница", "Верфь", "Казармы", "Научный центр")
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
                        updateResourcesDisplay(updatedLogic.player.resources, updatedLogic.player.era)
                        updateUnitsDisplay(updatedLogic.player.units)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обновления состояния игрока", e)
            }
        }
    }

    private fun createShipArmy(uid: String, unit: GameUnit, quantity: Int) {
        if (quantity <= 0) return
        val context = this@MultiplayerGameActivity
        lifecycleScope.launch {
            try {
                val hireSuccess = multiplayerLogic.makeTurn(
                    gameId, uid,
                    listOf(GameAction.HireUnit(unit, quantity))
                )
                if (!hireSuccess) {
                    Toast.makeText(context, "Не удалось нанять юнитов", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val unitType = unit.type
                val unitCounts = mapOf(unitType to quantity)
                val armySuccess = multiplayerLogic.makeTurn(
                    gameId, uid,
                    listOf(GameAction.CreateArmy(unitCounts))
                )
                if (armySuccess) {
                    Toast.makeText(context, "Корабль создан!", Toast.LENGTH_SHORT).show()
                    updatePlayerState(uid)
                } else {
                    Toast.makeText(context, "Не удалось создать армию", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("SHIP", "Error creating ship army", e)
                Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
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
            .setTitle("Нанять юнитов")
            .setItems(units) { _, index ->
                showUnitQuantityDialog(uid, era, index)
            }
            .show()
    }

    private fun showShipyardMenu(uid: String) {
        val game = currentGame ?: return
        val logic = game.players[uid]?.gameLogic ?: return
        val era = logic.player.era
        if (era.ordinal < Era.BRONZE_AGE.ordinal) {
            Toast.makeText(this, "Корабли доступны только с Бронзового века", Toast.LENGTH_SHORT).show()
            return
        }
        val ships = arrayOf("Рыболовный корабль", "Военный галеон", "Транспортный барж")
        AlertDialog.Builder(this)
            .setTitle("Построить корабль")
            .setItems(ships) { _, index ->
                showShipQuantityDialog(uid, index)
            }
            .show()
    }

    private fun showShipQuantityDialog(uid: String, shipIndex: Int) {
        val unit = when (shipIndex) {
            0 -> GameUnit.FishingBoat()
            1 -> GameUnit.WarGalley()
            2 -> GameUnit.TransportBarge()
            else -> return
        }
        val cost = getUnitCost(unit)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val tvInfo = TextView(this).apply {
            text = "${unit.name}\nСтоимость за 1: ${cost.getAvailableResources(Era.BRONZE_AGE)}"
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvInfo)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
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
        AlertDialog.Builder(this)
            .setTitle("Строительство кораблей")
            .setView(layout)
            .setPositiveButton("Построить") { _, _ ->
                val quantity = etQuantity.text.toString().toIntOrNull() ?: 1
                if (quantity > 0) {
                    createShipArmy(uid, unit, quantity)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showUnitQuantityDialog(uid: String, era: Era, unitIndex: Int) {
        val unit = createUnitByEraAndIndex(era, unitIndex) ?: return
        val cost = getUnitCost(unit)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val tvInfo = TextView(this).apply {
            text = "${unit.name}\nСтоимость за 1: ${cost.getAvailableResources(era)}"
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvInfo)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
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
                is Building.Shipyard -> showShipyardMenu(uid)
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