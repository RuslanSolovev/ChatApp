package com.example.chatapp.igra_strotegiy

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.chatapp.R
import com.google.gson.Gson
import com.google.gson.GsonBuilder

class StrategyGameActivity : AppCompatActivity() {

    private lateinit var gameLogic: GameLogic
    private var selectedBuilding: Building? = null

    private lateinit var tvPlayerInfo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var mapContainer: FrameLayout

    private lateinit var btnBuild: Button
    private lateinit var btnNextTurn: Button
    private lateinit var btnNewGame: Button

    private lateinit var gameMapRenderer: GameMapRenderer
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_strategy_game)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        tvPlayerInfo = findViewById(R.id.tvPlayerInfo)
        tvStatus = findViewById(R.id.tvStatus)
        mapContainer = findViewById(R.id.mapContainer)

        btnBuild = findViewById(R.id.btnBuild)
        btnNextTurn = findViewById(R.id.btnNextTurn)
        btnNewGame = findViewById(R.id.btnNewGame)

        gameLogic = loadGameState() ?: GameLogic()
        gameLogic.initializeEnemiesIfNeeded()

        gameMapRenderer = GameMapRenderer(this, gameLogic) { cell ->
            handleCellClick(cell)
        }

        refreshGameView()

        btnBuild.setOnClickListener { showBuildMenu() }
        btnNextTurn.setOnClickListener {
            gameLogic.nextTurn()
            refreshGameView()
        }
        btnNewGame.setOnClickListener {
            // Полный сброс
            getSharedPreferences("GameState", Context.MODE_PRIVATE)
                .edit()
                .remove("game_state")
                .apply()

            gameLogic = GameLogic()
            gameLogic.initializeEnemiesIfNeeded()
            // 🔥 Ключевое: пересоздаём рендерер с новым gameLogic
            gameMapRenderer = GameMapRenderer(this, gameLogic) { cell ->
                handleCellClick(cell)
            }
            refreshGameView()
            Toast.makeText(this@StrategyGameActivity, "Новая игра начата!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBuildMenu() {
        val buildings = arrayOf(
            "Лесопилка (10Д)",
            "Ферма (8Д,2В)",
            "Колодец (5Д,3К)",
            "Каменоломня (2В,10К)",
            "Золотая шахта (10Д,5Е,5В,10К)",
            "Казармы (15Д,5Е)",
        )

        AlertDialog.Builder(this)
            .setTitle("Построить здание")
            .setItems(buildings) { _, index ->
                val building = when (index) {
                    0 -> Building.Sawmill()
                    1 -> Building.Farm()
                    2 -> Building.Well()
                    3 -> Building.Quarry()
                    4 -> Building.GoldMine()
                    5 -> Building.Barracks()
                    else -> null
                }
                if (building != null) {
                    selectedBuilding = building
                    Toast.makeText(this, "Выбрано: ${building.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun handleCellClick(cell: MapCell) {
        // Атака врага или вражеской базы
        if (gameLogic.enemyPositions.values.any { it.first == cell.x && it.second == cell.y } ||
            (gameLogic.enemyBase?.x == cell.x && gameLogic.enemyBase?.y == cell.y && !gameLogic.enemyBase!!.isDestroyed())) {
            val msg = gameLogic.attackTarget(cell.x, cell.y)
            Toast.makeText(this@StrategyGameActivity, msg, Toast.LENGTH_LONG).show()
            refreshGameView()
            return
        }

        // Строительство
        if (cell.isBuildable()) {
            val b = selectedBuilding ?: run {
                Toast.makeText(this@StrategyGameActivity, "Выберите здание через 'Построить'!", Toast.LENGTH_SHORT).show()
                return
            }
            if (gameLogic.buildBuildingOnMap(b, cell.x, cell.y)) {
                refreshGameView()
                Toast.makeText(this@StrategyGameActivity, "${b.name} построено!", Toast.LENGTH_SHORT).show()
                selectedBuilding = null
            } else {
                Toast.makeText(this@StrategyGameActivity, "Недостаточно ресурсов или нельзя строить здесь!", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Взаимодействие со зданием
        val building = gameLogic.player.buildings.find {
            it.type == cell.type && !it.isDestroyed()
        } ?: return

        when (building) {
            is Building.Barracks -> showBarracksMenu()
            else -> showBuildingUpgradeMenu(building)
        }
    }

    private fun showBarracksMenu() {
        AlertDialog.Builder(this)
            .setTitle("Казармы")
            .setItems(arrayOf("Солдат (10Д,5Е)", "Лучник (15Д,5Е,5К)", "Танк (20Д,10Е,5В,15К,5З)")) { _, i ->
                val success = when (i) {
                    0 -> gameLogic.hireUnitFromBarracks(GameUnit.Soldier())
                    1 -> gameLogic.hireUnitFromBarracks(GameUnit.Archer())
                    2 -> gameLogic.hireUnitFromBarracks(GameUnit.Tank())
                    else -> false
                }
                if (success) {
                    refreshGameView()
                    Toast.makeText(this@StrategyGameActivity, "Юнит нанят!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@StrategyGameActivity, "Нет казарм или ресурсов!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showBuildingUpgradeMenu(building: Building) {
        if (building.level >= 10) {
            Toast.makeText(this@StrategyGameActivity, "Макс. уровень!", Toast.LENGTH_SHORT).show()
            return
        }
        val cost = building.upgradeCost()
        AlertDialog.Builder(this)
            .setTitle("Улучшить ${building.name}?")
            .setMessage("Ур. ${building.level} → ${building.level + 1}\nСтоимость: $cost")
            .setPositiveButton("Улучшить") { _, _ ->
                if (gameLogic.upgradeBuilding(building)) {
                    refreshGameView()
                    Toast.makeText(this@StrategyGameActivity, "Улучшено!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@StrategyGameActivity, "Недостаточно ресурсов!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun refreshGameView() {
        // Обновляем карту
        mapContainer.removeAllViews()
        val mapLayout = gameMapRenderer.render()
        mapContainer.addView(mapLayout)

        // Обновляем ресурсы
        tvPlayerInfo.post {
            tvPlayerInfo.text = gameLogic.getPlayerInfo()
        }

        // Обновляем статус
        tvStatus.post {
            when {
                gameLogic.isPlayerWon() -> {
                    tvStatus.text = "🏆 ПОБЕДА! Вражеская база уничтожена!"
                    tvStatus.setTextColor(ContextCompat.getColor(this@StrategyGameActivity, android.R.color.holo_green_dark))
                }
                gameLogic.isPlayerDefeated() -> {
                    tvStatus.text = "💀 ПОРАЖЕНИЕ! Ваша база уничтожена!"
                    tvStatus.setTextColor(ContextCompat.getColor(this@StrategyGameActivity, android.R.color.holo_red_dark))
                }
                gameLogic.lastAttackMessage.isNotEmpty() -> {
                    tvStatus.text = gameLogic.lastAttackMessage
                    val color = if (gameLogic.lastAttackMessage.contains("РАТУШУ")) {
                        ContextCompat.getColor(this@StrategyGameActivity, android.R.color.holo_red_dark)
                    } else {
                        ContextCompat.getColor(this@StrategyGameActivity, R.color.primaryColor)
                    }
                    tvStatus.setTextColor(color)

                    // Вибрация при атаке на базу
                    if (gameLogic.lastAttackMessage.contains("РАТУШУ")) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            vibrator?.vibrate(200)
                        }
                    }

                    Toast.makeText(this@StrategyGameActivity, gameLogic.lastAttackMessage, Toast.LENGTH_LONG).show()
                    gameLogic.lastAttackMessage = ""
                }
                else -> {
                    tvStatus.text = "Готовы к действию"
                    tvStatus.setTextColor(ContextCompat.getColor(this@StrategyGameActivity, R.color.primaryDarkColor))
                }
            }
        }
    }

    private fun saveGameState() {
        getSharedPreferences("GameState", Context.MODE_PRIVATE)
            .edit()
            .putString("game_state", getGson().toJson(gameLogic))
            .apply()
    }

    private fun loadGameState(): GameLogic? {
        return try {
            val json = getSharedPreferences("GameState", Context.MODE_PRIVATE)
                .getString("game_state", null)
            if (json != null) {
                getGson().fromJson(json, GameLogic::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getGson(): Gson = GsonBuilder()
        .registerTypeAdapter(Building::class.java, BuildingTypeAdapter)
        .registerTypeAdapter(GameUnit::class.java, GameUnitTypeAdapter)
        .create()

    override fun onPause() {
        super.onPause()
        saveGameState()
    }
}