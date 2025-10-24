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
        val cellSizeDp = 90 // ← БЫЛО 100, СТАЛО 90
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setShadowLayer(3f, 0f, 0f, ContextCompat.getColor(context, android.R.color.black))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(bottomTextMarginDp)
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
        }

        // === Враги ===
        val enemyHere = gameLogic.enemyPositions.entries.find { (_, pos) ->
            pos.first == cell.x && pos.second == cell.y
        }
        if (enemyHere != null) {
            val enemy = gameLogic.enemies.find { it.id == enemyHere.key }
            if (enemy != null && enemy.isAlive()) {
                imageView.setImageResource(R.drawable.ic_enemy)
                textView.text = "${enemy.name}\nHP: ${enemy.health}"
                frame.addView(imageView)
                frame.addView(textView)
                return frame
            }
        }

        // === Вражеская база ===
        if (cell.x == gameLogic.enemyBase?.x && cell.y == gameLogic.enemyBase?.y && !gameLogic.enemyBase!!.isDestroyed()) {
            imageView.setImageResource(R.drawable.ic_enemy_base)
            textView.text = "База\nHP: ${gameLogic.enemyBase!!.health}/${gameLogic.enemyBase!!.maxHealth}"
            frame.addView(imageView)
            frame.addView(textView)
            return frame
        }

        // === Здания игрока ===
        val playerBuilding = gameLogic.player.buildings.find {
            it.type == cell.type && !it.isDestroyed()
        }
        if (playerBuilding != null) {
            val (imageRes, text) = when (playerBuilding) {
                is Building.Sawmill -> R.drawable.ic_sawmill to "Лесопилка\nУр.${playerBuilding.level}"
                is Building.Farm -> R.drawable.ic_farm to "Ферма\nУр.${playerBuilding.level}"
                is Building.Well -> R.drawable.ic_well to "Колодец\nУр.${playerBuilding.level}"
                is Building.Quarry -> R.drawable.ic_quarry to "Каменоломня\nУр.${playerBuilding.level}"
                is Building.GoldMine -> R.drawable.ic_gold_mine to "Золото\nУр.${playerBuilding.level}"
                is Building.Barracks -> R.drawable.ic_barracks to "Казармы"
                is Building.TownHall -> R.drawable.ic_town_hall to "Ратуша\nHP: ${playerBuilding.health}/${playerBuilding.maxHealth}"
                else -> R.drawable.ic_empty to playerBuilding.name
            }
            imageView.setImageResource(imageRes)
            textView.text = text
            frame.addView(imageView)
            frame.addView(textView)
            return frame
        }

        // === Препятствия и пустота ===
        val (imageRes, text) = when (cell.type) {
            "empty" -> R.drawable.ic_empty to ""
            "base" -> R.drawable.ic_base to "База"
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

    private fun String.capitalize(): String {
        return if (isEmpty()) this else this[0].uppercaseChar() + substring(1)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}