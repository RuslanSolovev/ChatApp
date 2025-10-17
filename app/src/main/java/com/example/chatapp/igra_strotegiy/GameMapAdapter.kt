package com.example.chatapp.igra_strotegiy

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R

class GameMapAdapter(
    private val gameLogic: GameLogic,
    private val onCellClick: (MapCell) -> Unit // Вот здесь тип - Unit, т.е. "ничего не возвращаем"
) : RecyclerView.Adapter<GameMapAdapter.MapCellViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MapCellViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_map_cell, parent, false)
        return MapCellViewHolder(view)
    }

    override fun onBindViewHolder(holder: MapCellViewHolder, position: Int) {
        val cell = gameLogic.gameMap.cells[position]
        holder.bind(cell)
        holder.itemView.setOnClickListener { onCellClick(cell) }
    }

    override fun getItemCount(): Int = gameLogic.gameMap.cells.size

    class MapCellViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.ivCellImage)
        private val textView: TextView = itemView.findViewById(R.id.tvCellInfo)

        fun bind(cell: MapCell) {
            when (cell.type) {
                "empty" -> {
                    imageView.setImageResource(R.drawable.ic_empty)
                    textView.text = ""
                }
                "base" -> {
                    imageView.setImageResource(R.drawable.ic_base)
                    textView.text = "База"
                }
                "barracks" -> {
                    imageView.setImageResource(R.drawable.ic_barracks)
                    textView.text = "Казармы"
                }
                "mine" -> {
                    imageView.setImageResource(R.drawable.ic_mine)
                    textView.text = "Шахта"
                }
                "town_hall" -> {
                    imageView.setImageResource(R.drawable.ic_town_hall)
                    textView.text = "Ратуша"
                }
            }
        }
    }
}