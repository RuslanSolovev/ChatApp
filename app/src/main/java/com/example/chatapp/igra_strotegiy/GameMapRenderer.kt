package com.example.chatapp.igra_strotegiy

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.chatapp.R

class GameMapRenderer(
    private val context: Context,
    private val gameLogic: GameLogic,
    private val allPlayers: List<GamePlayer>? = null,
    private val onCellClick: (MapCell) -> Unit
) {
    fun render(): LinearLayout {
        val width = gameLogic.gameMap.width
        val height = gameLogic.gameMap.height
        val verticalLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
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
        val cellSizeDp = 50
        val marginDp = 3
        val bottomTextMarginDp = 5
        val frame = FrameLayout(context).apply {
            val params = LinearLayout.LayoutParams(dpToPx(cellSizeDp), dpToPx(cellSizeDp))
            params.setMargins(dpToPx(marginDp), dpToPx(marginDp), dpToPx(marginDp), dpToPx(marginDp))
            layoutParams = params
            setBackgroundResource(R.drawable.cell_background)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setShadowLayer(3f, 0f, 0f, ContextCompat.getColor(context, android.R.color.black))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(bottomTextMarginDp)
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
        }

        // Враги
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
            imageView.setImageResource(R.drawable.ic_enemy_base)
            textView.text = "База\nHP: ${gameLogic.enemyBase!!.health}/${gameLogic.enemyBase!!.maxHealth}"
            frame.addView(imageView)
            frame.addView(textView)
            return frame
        }

        // ОПРЕДЕЛЕНИЕ РАТУШИ: если есть allPlayers — ищем по позиции среди всех игроков
        var foundBuilding: Building? = null
        var buildingOwner: GamePlayer? = null

        if (allPlayers != null) {
            // Мультиплеер: ищем ратушу по позиции у любого игрока
            for (player in allPlayers) {
                val pos = player.gameLogic.player.townHallPosition
                if (pos.x == cell.x && pos.y == cell.y) {
                    val townHall = player.gameLogic.player.buildings.find { it is Building.TownHall && !it.isDestroyed() }
                    if (townHall != null) {
                        foundBuilding = townHall
                        buildingOwner = player
                        break
                    }
                }
            }
        }

        // Если нашли ратушу в мультиплеере
        if (foundBuilding != null && foundBuilding is Building.TownHall) {
            val ownerName = buildingOwner?.displayName?.take(5) ?: "Игрок"
            imageView.setImageResource(R.drawable.ic_town_hall)
            textView.text = "Ратуша\n$ownerName\nHP: ${foundBuilding.health}/${foundBuilding.maxHealth}"

            // Цвет текста в зависимости от здоровья
            val healthPercent = foundBuilding.health.toFloat() / foundBuilding.maxHealth.toFloat()
            textView.setTextColor(when {
                healthPercent > 0.7 -> ContextCompat.getColor(context, android.R.color.holo_green_light)
                healthPercent > 0.3 -> ContextCompat.getColor(context, android.R.color.holo_orange_light)
                else -> ContextCompat.getColor(context, android.R.color.holo_red_light)
            })

            frame.addView(imageView)
            frame.addView(textView)
            return frame
        }

        // Если не нашли ратушу (или в одиночной игре), ищем обычные здания
        if (foundBuilding == null) {
            foundBuilding = gameLogic.player.buildings.find { building ->
                building.type == cell.type && !building.isDestroyed()
            }
        }

        if (foundBuilding != null) {
            val (imageRes, text) = when (foundBuilding.type) {
                "hut" -> R.drawable.ic_hut to "Хижина\nУр.${foundBuilding.level}"
                "well" -> R.drawable.ic_well to "Колодец\nУр.${foundBuilding.level}"
                "sawmill" -> R.drawable.ic_sawmill to "Лесопилка\nУр.${foundBuilding.level}"
                "fishing_hut" -> R.drawable.ic_fishing_hut to "Рыболовство\nУр.${foundBuilding.level}"
                "farm" -> R.drawable.ic_farm to "Ферма\nУр.${foundBuilding.level}"
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
                "barracks" -> R.drawable.ic_barracks to "Казармы\nУр.${foundBuilding.level}"
                "research_center" -> R.drawable.ic_forge to "Наука\nУр.${foundBuilding.level}"
                "town_hall" -> R.drawable.ic_town_hall to "Ратуша\nHP: ${foundBuilding.health}/${foundBuilding.maxHealth}"
                else -> R.drawable.ic_empty to foundBuilding.name
            }
            imageView.setImageResource(imageRes)
            textView.text = text
            frame.addView(imageView)
            frame.addView(textView)
            return frame
        }

        // Препятствия
        val (imageRes, text) = when (cell.type) {
            "empty" -> R.drawable.ic_empty to ""
            "mountain" -> R.drawable.ic_mountain to "Гора"
            "river" -> R.drawable.ic_river to "Река"
            else -> R.drawable.ic_empty to cell.type
        }
        imageView.setImageResource(imageRes)
        textView.text = text
        frame.addView(imageView)
        frame.addView(textView)
        return frame
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}