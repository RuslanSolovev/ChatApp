package com.example.chatapp.igra_strotegiy

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.google.gson.Gson

class StrategyGameActivity : AppCompatActivity() {

    private lateinit var gameLogic: GameLogic
    private lateinit var mapAdapter: GameMapAdapter
    private var selectedBuilding: Building? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_strategy_game)

        val tvPlayerInfo = findViewById<TextView>(R.id.tvPlayerInfo)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val btnBuildBarracks = findViewById<Button>(R.id.btnBuildBarracks)
        val btnBuildTownHall = findViewById<Button>(R.id.btnBuildTownHall)
        val btnBuildMine = findViewById<Button>(R.id.btnBuildMine)
        val btnHireSoldier = findViewById<Button>(R.id.btnHireSoldier)
        val btnHireArcher = findViewById<Button>(R.id.btnHireArcher)
        val btnHireTank = findViewById<Button>(R.id.btnHireTank)
        val btnNextTurn = findViewById<Button>(R.id.btnNextTurn)
        val btnAttack = findViewById<Button>(R.id.btnAttack)
        val rvGameMap = findViewById<RecyclerView>(R.id.rvGameMap)

        gameLogic = GameLogic()
        loadGameState()

        mapAdapter = GameMapAdapter(gameLogic) { cell ->
            handleCellClick(cell)
        }
        rvGameMap.layoutManager = GridLayoutManager(this, 5)
        rvGameMap.adapter = mapAdapter

        updateUI(tvPlayerInfo, tvStatus)

        // Кнопки строительства
        btnBuildBarracks.setOnClickListener {
            selectedBuilding = Building.Barracks()
            Toast.makeText(this, "Выбрано: ${selectedBuilding?.name}", Toast.LENGTH_SHORT).show()
        }

        btnBuildTownHall.setOnClickListener {
            selectedBuilding = Building.TownHall()
            Toast.makeText(this, "Выбрано: ${selectedBuilding?.name}", Toast.LENGTH_SHORT).show()
        }

        btnBuildMine.setOnClickListener {
            selectedBuilding = Building.Mine()
            Toast.makeText(this, "Выбрано: ${selectedBuilding?.name}", Toast.LENGTH_SHORT).show()
        }

        // Кнопки найма
        btnHireSoldier.setOnClickListener {
            if (gameLogic.hireUnit(GameUnit.Soldier())) { // Замените здесь
                updateUI(tvPlayerInfo, tvStatus)
                Toast.makeText(this, "Солдат нанят!", Toast.LENGTH_SHORT).show()
            } else {
                tvStatus.text = "Недостаточно ресурсов!"
            }
        }

        btnHireArcher.setOnClickListener {
            if (gameLogic.hireUnit(GameUnit.Archer())) { // Замените здесь
                updateUI(tvPlayerInfo, tvStatus)
                Toast.makeText(this, "Лучник нанят!", Toast.LENGTH_SHORT).show()
            } else {
                tvStatus.text = "Недостаточно ресурсов!"
            }
        }

        btnHireTank.setOnClickListener {
            if (gameLogic.hireUnit(GameUnit.Tank())) { // Замените здесь
                updateUI(tvPlayerInfo, tvStatus)
                Toast.makeText(this, "Танк нанят!", Toast.LENGTH_SHORT).show()
            } else {
                tvStatus.text = "Недостаточно ресурсов!"
            }
        }

        btnNextTurn.setOnClickListener {
            gameLogic.nextTurn()
            updateUI(tvPlayerInfo, tvStatus)
        }

        btnAttack.setOnClickListener {
            val result = gameLogic.attackEnemy()
            Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            updateUI(tvPlayerInfo, tvStatus)
        }
    }

    private fun handleCellClick(cell: MapCell) {
        if (cell.type != "empty") {
            Toast.makeText(this, "Ячейка занята!", Toast.LENGTH_SHORT).show()
            return
        }

        val building = selectedBuilding
        if (building == null) {
            Toast.makeText(this, "Сначала выберите здание!", Toast.LENGTH_SHORT).show()
            return
        }

        if (gameLogic.buildBuildingOnMap(building, cell.x, cell.y)) {
            mapAdapter.notifyDataSetChanged()
            Toast.makeText(this, "${building.name} построено!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Недостаточно ресурсов!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI(tvPlayerInfo: TextView, tvStatus: TextView) {
        tvPlayerInfo.text = gameLogic.getPlayerInfo()
        tvStatus.text = "Готовы к действию"
    }

    private fun saveGameState() {
        val sharedPreferences = getSharedPreferences("GameState", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val json = gameLogic.saveState()
        editor.putString("game_state", json)
        editor.apply()
    }

    private fun loadGameState() {
        val sharedPreferences = getSharedPreferences("GameState", Context.MODE_PRIVATE)
        val json = sharedPreferences.getString("game_state", null)
        json?.let { gameLogic.loadState(it) }
    }

    override fun onPause() {
        super.onPause()
        saveGameState()
    }
}