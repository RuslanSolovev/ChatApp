package com.example.chatapp.loterey

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import java.text.SimpleDateFormat
import java.util.*

class LotteryHistoryAdapter : ListAdapter<LotteryHistory, LotteryHistoryAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvDate: TextView = view.findViewById(R.id.tvDate)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        private val tvWinnerName: TextView = view.findViewById(R.id.tvWinnerName)
        private val tvWinnerEmail: TextView = view.findViewById(R.id.tvWinnerEmail)
        private val tvPrize: TextView = view.findViewById(R.id.tvPrize)
        private val tvParticipants: TextView = view.findViewById(R.id.tvParticipants)

        fun bind(history: LotteryHistory) {
            val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val date = Date(history.drawTime)

            tvDate.text = dateFormat.format(date)
            tvTime.text = timeFormat.format(date)
            tvWinnerName.text = history.winnerName
            tvWinnerEmail.text = history.winnerEmail
            tvPrize.text = "🏆 ${history.prizeAmount.toInt()} ₽"
            tvParticipants.text = "🎫 ${history.ticketCount} билетов"

            // Подсветка последнего розыгрыша
            if (adapterPosition == 0) {
                itemView.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.primaryDarkColor))
            } else {
                itemView.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.transparent))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lottery_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object DiffCallback : DiffUtil.ItemCallback<LotteryHistory>() {
        override fun areItemsTheSame(oldItem: LotteryHistory, newItem: LotteryHistory): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: LotteryHistory, newItem: LotteryHistory): Boolean {
            return oldItem == newItem
        }
    }
}