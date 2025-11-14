// com.example.chatapp.igra_strotegiy.GameMapRenderer.kt
package com.example.chatapp.igra_strotegiy

import android.content.Context
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.chatapp.R

fun GameUnit.isNaval(): Boolean = this is GameUnit.FishingBoat ||
        this is GameUnit.WarGalley ||
        this is GameUnit.TransportBarge

fun Army.isNaval(): Boolean = units.isNotEmpty() && units.all { it.isNaval() }

class GameMapRenderer(
    private val context: Context,
    private val gameLogic: GameLogic,
    private val allPlayers: List<GamePlayer>? = null,
    private val myUid: String? = null,
    private val onCellClick: (MapCell) -> Unit
) {
    fun render(): LinearLayout {
        val width = gameLogic.gameMap.width
        val height = gameLogic.gameMap.height
        val verticalLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        Log.d("RENDER", "=== START RENDERING MAP ===")
        Log.d("RENDER", "Map size: ${width}x$height")
        Log.d("RENDER", "My UID: $myUid")
        Log.d("RENDER", "Players count: ${allPlayers?.size}")

        // Логируем все армии
        allPlayers?.forEach { player ->
            Log.d("RENDER", "Player ${player.uid} (${player.displayName}) has ${player.gameLogic.armies.size} armies")
            player.gameLogic.armies.forEach { army ->
                Log.d("RENDER", "  Army ${army.id} at (${army.position.x},${army.position.y}) - naval: ${army.isNaval()}, units: ${army.units.size}, type: ${army.units.firstOrNull()?.javaClass?.simpleName}")
                if (army.carriedArmy != null) {
                    Log.d("RENDER", "    Carrying: ${army.carriedArmy!!.units.size} units")
                }
            }
        }

        for (y in 0 until height) {
            val horizontalLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (x in 0 until width) {
                val cell = gameLogic.gameMap.getCell(x, y)!!
                val cellView = createCellView(cell)
                cellView.setOnClickListener { onCellClick(cell) }
                horizontalLayout.addView(cellView)
            }
            verticalLayout.addView(horizontalLayout)
        }
        return verticalLayout
    }

    private fun createCellView(cell: MapCell): FrameLayout {
        val cellSizeDp = 80
        val marginDp = 0
        val bottomTextMarginDp = 0
        val frame = FrameLayout(context).apply {
            val params = LinearLayout.LayoutParams(dpToPx(cellSizeDp), dpToPx(cellSizeDp))
            params.setMargins(dpToPx(marginDp), dpToPx(marginDp), dpToPx(marginDp), dpToPx(marginDp))
            layoutParams = params
            // НЕ устанавливаем setBackgroundResource(R.drawable.trava2) — оно будет только у сухопутных клеток
        }

        val imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val textView = TextView(context).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setShadowLayer(3f, 0f, 0f, ContextCompat.getColor(context, android.R.color.black))
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(bottomTextMarginDp)
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
        }

        // === 🔥 ОТРИСОВКА АРМИЙ (мультиплеер) ===
        if (allPlayers != null) {
            val armiesHere = mutableListOf<Pair<Army, GamePlayer>>()
            for (player in allPlayers) {
                val armies = player.gameLogic.armies.filter {
                    it.position.x == cell.x && it.position.y == cell.y && it.isAlive()
                }
                armies.forEach { army ->
                    armiesHere.add(Pair(army, player))
                    Log.d("RENDER", "Found army at (${cell.x},${cell.y}): ${army.id} from ${player.uid}, naval: ${army.isNaval()}, isOwn: ${player.uid == myUid}")
                }
            }

            if (armiesHere.isNotEmpty()) {
                val (army, owner) = armiesHere.first()
                val isOwn = owner.uid == myUid
                val isNaval = army.isNaval()

                Log.d("RENDER", "Rendering army: ${army.id}, isOwn: $isOwn, isNaval: $isNaval, unitType: ${army.units.firstOrNull()?.javaClass?.simpleName}")

                val iconRes = when {
                    isOwn && isNaval -> {
                        Log.d("RENDER", "→ korabl_krasnii (own naval)")
                        R.drawable.korabl_krasnii
                    }
                    !isOwn && isNaval -> {
                        Log.d("RENDER", "→ korabl_zelenii (enemy naval)")
                        R.drawable.korabl_zelenii
                    }
                    isOwn -> {
                        Log.d("RENDER", "→ vsadnik_krasnii (own land)")
                        R.drawable.vsadnik_krasnii
                    }
                    else -> {
                        Log.d("RENDER", "→ vsadnik_zelenii (enemy land)")
                        R.drawable.vsadnik_zelenii
                    }
                }
                imageView.setImageResource(iconRes)

                val totalUnits = army.units.size
                val ownerName = if (isOwn) "Ты" else owner.displayName.take(6)

                val armyInfo = when {
                    army.carriedArmy != null -> {
                        val cargoUnits = army.carriedArmy!!.units.size
                        "ТРАНСПОРТ\n$ownerName\n$totalUnits+$cargoUnits юн."
                    }
                    army.units.firstOrNull() is GameUnit.TransportBarge -> {
                        "ТРАНСПОРТ\n$ownerName\n$totalUnits юн."
                    }
                    army.units.firstOrNull() is GameUnit.FishingBoat -> {
                        "РЫБАЛКА\n$ownerName\n$totalUnits юн."
                    }
                    army.units.firstOrNull() is GameUnit.WarGalley -> {
                        "ВОЕННЫЙ\n$ownerName\n$totalUnits юн."
                    }
                    else -> {
                        "$ownerName\nАрмия\n$totalUnits юн."
                    }
                }

                textView.text = armyInfo
                textView.setTextColor(
                    if (isOwn) ContextCompat.getColor(context, android.R.color.holo_green_light)
                    else ContextCompat.getColor(context, android.R.color.holo_red_light)
                )

                frame.addView(imageView)
                frame.addView(textView)
                return frame
            }
        }

        // Враги (одиночная игра)
        val enemyEntry = gameLogic.enemyPositions.entries.find { (_, pos) ->
            pos.first == cell.x && pos.second == cell.y
        }
        if (enemyEntry != null) {
            val enemyId = enemyEntry.key.toIntOrNull()
            val enemy = gameLogic.enemies.find { it.id == enemyId }
            if (enemy != null && enemy.isAlive()) {
                imageView.setImageResource(R.drawable.ic_enemy)
                textView.text = "${enemy.name}\nHP: ${enemy.health}"
                frame.addView(imageView)
                frame.addView(textView)
                return frame
            }
        }

        // Вражеская база
        if (cell.x == gameLogic.enemyBase?.x && cell.y == gameLogic.enemyBase?.y && !gameLogic.enemyBase!!.isDestroyed()) {
            imageView.setImageResource(R.drawable.ratuha2)
            textView.text = "База\nHP: ${gameLogic.enemyBase!!.health}/${gameLogic.enemyBase!!.maxHealth}"
            frame.addView(imageView)
            frame.addView(textView)
            return frame
        }

        // ОПРЕДЕЛЕНИЕ РАТУШИ
        var foundBuilding: Building? = null
        var buildingOwner: GamePlayer? = null

        if (allPlayers != null) {
            for (player in allPlayers) {
                val pos = player.gameLogic.player.townHallPosition
                if (pos.x == cell.x && pos.y == cell.y) {
                    val townHall = player.gameLogic.player.buildings.find { it is Building.TownHall && !it.isDestroyed() }
                    if (townHall != null) {
                        foundBuilding = townHall
                        buildingOwner = player
                        Log.d("RENDER", "Found town hall at (${cell.x},${cell.y}) for player ${player.uid}")
                        break
                    }
                }
            }
        }

        // Если нашли ратушу
        if (foundBuilding != null && foundBuilding is Building.TownHall) {
            val isOwn = buildingOwner?.uid == myUid
            val ownerName = if (isOwn) "Твоя" else buildingOwner?.displayName?.take(5) ?: "Игрок"
            imageView.setImageResource(R.drawable.ratuha3)
            textView.text = "Ратуша\n$ownerName\nHP: ${foundBuilding.health}/${foundBuilding.maxHealth}"

            val healthPercent = foundBuilding.health.toFloat() / foundBuilding.maxHealth.toFloat()
            textView.setTextColor(
                if (isOwn) {
                    when {
                        healthPercent > 0.7 -> ContextCompat.getColor(context, android.R.color.holo_green_light)
                        healthPercent > 0.3 -> ContextCompat.getColor(context, android.R.color.holo_orange_light)
                        else -> ContextCompat.getColor(context, android.R.color.holo_red_light)
                    }
                } else {
                    ContextCompat.getColor(context, android.R.color.holo_red_light)
                }
            )

            frame.addView(imageView)
            frame.addView(textView)
            return frame
        }

        // Обычные здания
        if (foundBuilding == null) {
            foundBuilding = gameLogic.player.buildings.find { building ->
                building.type == cell.type && !building.isDestroyed()
            }
        }

        if (foundBuilding != null) {
            val (imageRes, text) = when (foundBuilding.type) {
                "hut" -> R.drawable.higina to "Хижина\nУр.${foundBuilding.level}"
                "well" -> R.drawable.kolodec to "Колодец\nУр.${foundBuilding.level}"
                "sawmill" -> R.drawable.lesopilka to "Лесопилка\nУр.${foundBuilding.level}"
                "fishing_hut" -> R.drawable.ic_fishing_hut to "Рыболовство\nУр.${foundBuilding.level}"
                "farm" -> R.drawable.ferma to "Ферма\nУр.${foundBuilding.level}"
                "shipyard" -> R.drawable.ic_fishing_hut to "Верфь\nУр.${foundBuilding.level}"
                "quarry" -> R.drawable.ic_quarry to "Каменоломня\nУр.${foundBuilding.level}"
                "gold_mine" -> R.drawable.ic_gold_mine to "Золото\nУр.${foundBuilding.level}"
                "forge" -> R.drawable.ic_forge to "Кузница\nУр.${foundBuilding.level}"
                "iron_mine" -> R.drawable.ic_forge to "Железо\nУр.${foundBuilding.level}"
                "castle" -> R.drawable.ic_forge to "Замок\nУр.${foundBuilding.level}"
                "blacksmith" -> R.drawable.ic_forge to "Оружейная\nУр.${foundBuilding.level}"
                "coal_mine" -> R.drawable.ic_forge to "Уголь\nУр.${foundBuilding.level}"
                "oil_rig" -> R.drawable.ic_forge to "Нефть\nУр.${foundBuilding.level}"
                "factory" -> R.drawable.ic_forge to "Фабрика\nУр.${foundBuilding.level}"
                "power_plant" -> R.drawable.ic_forge to "Энергия\nУр.${foundBuilding.level}"
                "solar_plant" -> R.drawable.ic_forge to "Солнце\nУр.${foundBuilding.level}"
                "nuclear_plant" -> R.drawable.ic_forge to "Реактор\nУр.${foundBuilding.level}"
                "robotics_lab" -> R.drawable.ic_forge to "Роботы\nУр.${foundBuilding.level}"
                "barracks" -> R.drawable.kazarma to "Казармы\nУр.${foundBuilding.level}"
                "research_center" -> R.drawable.nauka to "Наука\nУр.${foundBuilding.level}"
                "town_hall" -> R.drawable.ratuha3 to "Ратуша\nHP: ${foundBuilding.health}/${foundBuilding.maxHealth}"
                else -> R.drawable.gazon to foundBuilding.name
            }
            imageView.setImageResource(imageRes)
            textView.text = text
            frame.addView(imageView)
            frame.addView(textView)
            return frame
        }

        // 🔥 Отображаем содержимое ТОЛЬКО если клетка — не море
        if (cell.type != "sea") {
            val (imageRes, text) = when (cell.type) {
                "empty" -> R.drawable.gazon to ""
                "mountain" -> R.drawable.gora2 to "Гора"
                // "river" убран полностью
                else -> R.drawable.gazon to cell.type
            }
            imageView.setImageResource(imageRes)
            textView.text = text
            frame.addView(imageView)
            frame.addView(textView)
        }

        // Для "sea" — frame остаётся пустым и прозрачным
        return frame
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}