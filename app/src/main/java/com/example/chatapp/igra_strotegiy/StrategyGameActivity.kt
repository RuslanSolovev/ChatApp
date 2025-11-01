package com.example.chatapp.igra_strotegiy

import android.app.AlertDialog
import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
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
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_strategy_game)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        mediaPlayer = MediaPlayer.create(this, R.raw.alarm_sound1)

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

        btnBuild.setOnClickListener { showBuildingMenu() }
        btnNextTurn.setOnClickListener {
            gameLogic.nextTurn()
            refreshGameView()
        }
        btnNewGame.setOnClickListener {
            getSharedPreferences("GameState", Context.MODE_PRIVATE)
                .edit()
                .remove("game_state")
                .apply()

            gameLogic = GameLogic()
            gameLogic.initializeEnemiesIfNeeded()
            gameMapRenderer = GameMapRenderer(this, gameLogic, null) { cell ->
                handleCellClick(cell)
            }
            refreshGameView()
            Toast.makeText(this@StrategyGameActivity, "Новая игра начата!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBuildingMenu() {
        val era = gameLogic.player.era
        val buildings = when (era) {
            Era.STONE_AGE -> arrayOf(
                "Хижина (30Д,20Е)",
                "Колодец (25Д,15В)",
                "Лесопилка (40Д)",
                "Рыболовная хижина (35Д,15В)",
                "Казармы (40Д,20Е)",
                "Научный центр (50Д,25Е)"
            )
            Era.BRONZE_AGE -> arrayOf(
                "Ферма (40Д,20К,15Е)",
                "Каменоломня (30К,20Е)",
                "Золотой рудник (40К,30Д,25Е)",
                "Кузница (50К,25З,20Д)",
                "Казармы (50Д,30К)",
                "Научный центр (60К,40З)"
            )
            Era.MIDDLE_AGES -> arrayOf(
                "Железный рудник (60К,30З,40Ж)",
                "Замок (100К,60Ж,40З)",
                "Оружейная (50Ж,40Д,30К)",
                "Казармы (60К,40Ж)",
                "Научный центр (80Ж,50З)"
            )
            Era.INDUSTRIAL -> arrayOf(
                "Угольная шахта (60Ж,50К,80У)",
                "Нефтяная вышка (80Ж,60У,100Н)",
                "Фабрика (70У,90Ж,50Н)",
                "Электростанция (120У,80Н,60Ж)",
                "Казармы (70Ж,50У)",
                "Научный центр (100У,60Н)"
            )
            Era.FUTURE -> arrayOf(
                "Солнечная станция (150Э,60Ж,40Н)",
                "Ядерный реактор (300Э,100Н,80Ж)",
                "Робо-лаборатория (400Э,100З,70Ж)",
                "Казармы (80Э,60З)",
                "Научный центр (120Э,80З)"
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Построить (${getEraName(era)})")
            .setItems(buildings) { _, index ->
                val building = getBuildingByIndex(era, index)
                if (building != null) {
                    selectedBuilding = building
                    Toast.makeText(this, "Выбрано: ${building.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun getEraName(era: Era): String = when (era) {
        Era.STONE_AGE -> "Каменный век"
        Era.BRONZE_AGE -> "Бронзовый век"
        Era.MIDDLE_AGES -> "Средневековье"
        Era.INDUSTRIAL -> "Индустриальная эра"
        Era.FUTURE -> "Футуристическая эра"
    }

    private fun getBuildingByIndex(era: Era, index: Int): Building? {
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
        }
    }

    private fun showResearchMenu() {
        val availableResearch = gameLogic.getAvailableResearch()
        if (availableResearch.isEmpty()) {
            Toast.makeText(this, "Все исследования завершены!", Toast.LENGTH_SHORT).show()
            return
        }

        val researchNames = availableResearch.map {
            "${it.name}\nСтоимость: ${it.cost.getAvailableResources(gameLogic.player.era)}\n${it.description}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Исследования")
            .setItems(researchNames) { _, index ->
                val research = availableResearch[index]
                if (gameLogic.completeResearch(research)) {
                    refreshGameView()
                    Toast.makeText(this, "Исследование завершено: ${research.name}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Недостаточно ресурсов!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showBarracksMenu() {
        val era = gameLogic.player.era
        val units = when (era) {
            Era.STONE_AGE -> arrayOf(
                "Пещерный человек (15Е,10Д)",
                "Охотник (20Е,15Д,5В)",
                "Всадник на мамонте (50Е,30Д,15В)"
            )
            Era.BRONZE_AGE -> arrayOf(
                "Мечник (25Е,20К,10З)",
                "Лучник (20Е,15К,8З)",
                "Боевая колесница (60Е,40К,25З)"
            )
            Era.MIDDLE_AGES -> arrayOf(
                "Рыцарь (35Е,25Ж,15З)",
                "Арбалетчик (30Е,20Ж,12З)",
                "Таран (40Е,50Ж,30Д)"
            )
            Era.INDUSTRIAL -> arrayOf(
                "Солдат (25Е,15Ж,10У)",
                "Артиллерия (35Е,30Ж,20У,10Н)",
                "Танк (50Е,60Ж,30У,20Н)"
            )
            Era.FUTURE -> arrayOf(
                "Боевой дрон (40Э,20Ж,15З)",
                "Боевой мех (80Э,50Ж,25З)",
                "Лазерная пушка (120Э,30Ж,40З)"
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Нанять (${getEraName(era)})")
            .setItems(units) { _, i ->
                val success = hireUnitByIndex(era, i)
                if (success) {
                    refreshGameView()
                    Toast.makeText(this, "Юнит нанят!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Недостаточно ресурсов!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun hireUnitByIndex(era: Era, index: Int): Boolean {
        return when (era) {
            Era.STONE_AGE -> when (index) {
                0 -> gameLogic.hireUnit(GameUnit.Caveman())
                1 -> gameLogic.hireUnit(GameUnit.Hunter())
                2 -> gameLogic.hireUnit(GameUnit.MammothRider())
                else -> false
            }
            Era.BRONZE_AGE -> when (index) {
                0 -> gameLogic.hireUnit(GameUnit.Swordsman())
                1 -> gameLogic.hireUnit(GameUnit.BronzeArcher())
                2 -> gameLogic.hireUnit(GameUnit.Chariot())
                else -> false
            }
            Era.MIDDLE_AGES -> when (index) {
                0 -> gameLogic.hireUnit(GameUnit.Knight())
                1 -> gameLogic.hireUnit(GameUnit.Crossbowman())
                2 -> gameLogic.hireUnit(GameUnit.Ram())
                else -> false
            }
            Era.INDUSTRIAL -> when (index) {
                0 -> gameLogic.hireUnit(GameUnit.Soldier())
                1 -> gameLogic.hireUnit(GameUnit.Artillery())
                2 -> gameLogic.hireUnit(GameUnit.Tank())
                else -> false
            }
            Era.FUTURE -> when (index) {
                0 -> gameLogic.hireUnit(GameUnit.Drone())
                1 -> gameLogic.hireUnit(GameUnit.Mech())
                2 -> gameLogic.hireUnit(GameUnit.LaserCannon())
                else -> false
            }
        }
    }

    private fun handleCellClick(cell: MapCell) {
        // Проверка на врага или вражескую базу
        if (gameLogic.enemyPositions.values.any { it.first == cell.x && it.second == cell.y } ||
            (gameLogic.enemyBase?.x == cell.x && gameLogic.enemyBase?.y == cell.y && !gameLogic.enemyBase!!.isDestroyed())) {
            val msg = gameLogic.attackTarget(cell.x, cell.y)
            Toast.makeText(this@StrategyGameActivity, msg, Toast.LENGTH_LONG).show()
            refreshGameView()
            return
        }

        // Постройка здания
        if (cell.isBuildable()) {
            val b = selectedBuilding ?: run {
                Toast.makeText(this@StrategyGameActivity, "Выберите здание через 'Построить'!", Toast.LENGTH_SHORT).show()
                return
            }

            // Проверяем, есть ли уже такое здание (если это не казармы/научный центр)
            if (b is Building.Barracks || b is Building.ResearchCenter) {
                // Казармы и научный центр можно строить несколько
            } else {
                val existingBuilding = gameLogic.player.buildings.find { it::class == b::class }
                if (existingBuilding != null) {
                    Toast.makeText(this@StrategyGameActivity, "Такое здание уже построено!", Toast.LENGTH_SHORT).show()
                    return
                }
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

        // Взаимодействие с существующими зданиями
        val building = gameLogic.player.buildings.find {
            it.type == cell.type && !it.isDestroyed()
        } ?: return

        when (building) {
            is Building.Barracks -> showBarracksMenu()
            is Building.ResearchCenter -> showResearchMenu()
            is Building.TownHall -> showEraMenu()
            else -> showBuildingUpgradeMenu(building)
        }
    }

    private fun showEraMenu() {
        val nextEra = Era.values().getOrNull(gameLogic.player.era.ordinal + 1)
        if (nextEra == null) {
            Toast.makeText(this, "Вы достигли вершины развития!", Toast.LENGTH_SHORT).show()
            return
        }

        val req = GameLogic.ERA_REQUIREMENTS[nextEra]!!
        val eraName = when (nextEra) {
            Era.BRONZE_AGE -> "Бронзовый век"
            Era.MIDDLE_AGES -> "Средневековье"
            Era.INDUSTRIAL -> "Индустриальная эра"
            Era.FUTURE -> "Футуристическая эра"
            else -> nextEra.name
        }

        val costText = "Ресурсы: ${req.resources.getAvailableResources(nextEra)}\nТребуется исследований: ${req.completedResearch}\n\nТекущие исследования: ${gameLogic.player.completedResearch.size}"

        AlertDialog.Builder(this)
            .setTitle("Эволюция: $eraName")
            .setMessage(costText)
            .setPositiveButton("Эволюционировать") { _, _ ->
                if (gameLogic.evolveTo(nextEra)) {
                    playEvolutionEffect()
                    refreshGameView()
                    Toast.makeText(this, "Цивилизация перешла в $eraName!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Недостаточно ресурсов или исследований!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun playEvolutionEffect() {
        // Вибрация
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator?.vibrate(500)
        }

        // Звук
        mediaPlayer?.start()

        // Анимация мигания
        val anim = AlphaAnimation(1.0f, 0.3f)
        anim.duration = 600
        anim.repeatCount = 2
        anim.repeatMode = Animation.REVERSE
        window.decorView.startAnimation(anim)
    }

    private fun showBuildingUpgradeMenu(building: Building) {
        if (building.level >= 10) {
            Toast.makeText(this@StrategyGameActivity, "Максимальный уровень достигнут!", Toast.LENGTH_SHORT).show()
            return
        }
        val cost = building.upgradeCost()
        AlertDialog.Builder(this)
            .setTitle("Улучшить ${building.name}?")
            .setMessage("Уровень ${building.level} → ${building.level + 1}\nСтоимость: ${cost.getAvailableResources(gameLogic.player.era)}")
            .setPositiveButton("Улучшить") { _, _ ->
                if (gameLogic.upgradeBuilding(building)) {
                    refreshGameView()
                    Toast.makeText(this@StrategyGameActivity, "${building.name} улучшено до уровня ${building.level}!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@StrategyGameActivity, "Недостаточно ресурсов!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun refreshGameView() {
        mapContainer.removeAllViews()
        val mapLayout = gameMapRenderer.render()
        mapContainer.addView(mapLayout)

        tvPlayerInfo.post {
            tvPlayerInfo.text = gameLogic.getPlayerInfo()
        }

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
                gameLogic.currentEvent != null -> {
                    tvStatus.text = gameLogic.currentEvent
                    tvStatus.setTextColor(ContextCompat.getColor(this@StrategyGameActivity, R.color.primary_color))
                    gameLogic.currentEvent = null
                }
                gameLogic.lastAttackMessage.isNotEmpty() -> {
                    tvStatus.text = gameLogic.lastAttackMessage
                    val color = if (gameLogic.lastAttackMessage.contains("РАТУШУ")) {
                        ContextCompat.getColor(this@StrategyGameActivity, android.R.color.holo_red_dark)
                    } else {
                        ContextCompat.getColor(this@StrategyGameActivity, R.color.primaryDarkColor)
                    }
                    tvStatus.setTextColor(color)

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
                    tvStatus.setTextColor(ContextCompat.getColor(this@StrategyGameActivity, R.color.primary_color))
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

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}