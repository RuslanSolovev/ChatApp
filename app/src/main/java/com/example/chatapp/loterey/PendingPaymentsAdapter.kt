package com.example.chatapp.loterey

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import java.text.SimpleDateFormat
import java.util.*

class PendingPaymentsAdapter(
    private val onConfirm: (String) -> Unit
) : ListAdapter<ManualPayment, PendingPaymentsAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvUserName: TextView = view.findViewById(R.id.tvUserName)
        private val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        private val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        private val btnConfirm: Button = view.findViewById(R.id.btnConfirm)

        fun bind(payment: ManualPayment) {
            tvUserName.text = "${payment.userName}\n${payment.userEmail}"

            val ticketsCount = (payment.amount / 100).toInt()
            tvAmount.text = "Сумма: ${payment.amount.toInt()} ₽ ($ticketsCount билетов)"

            val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
            tvTime.text = "Время: ${dateFormat.format(Date(payment.createdAt))}"

            when (payment.status) {
                "PENDING" -> {
                    tvStatus.text = "⏳ Ожидает"
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.primaryColor))
                    btnConfirm.visibility = View.VISIBLE
                    btnConfirm.isEnabled = true
                    btnConfirm.setOnClickListener {
                        btnConfirm.isEnabled = false
                        onConfirm(payment.id)
                    }
                }
                "CONFIRMED" -> {
                    tvStatus.text = "✅ Подтвержден"
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.nav_item_blue2))
                    btnConfirm.visibility = View.GONE

                    val addedText = if (payment.ticketsAdded > 0) {
                        " (добавлено ${payment.ticketsAdded} билетов)"
                    } else ""
                    tvAmount.text = "Сумма: ${payment.amount.toInt()} ₽$addedText"
                }
                else -> {
                    tvStatus.text = payment.status
                    btnConfirm.visibility = View.GONE
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pending_payment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ManualPayment>() {
        override fun areItemsTheSame(oldItem: ManualPayment, newItem: ManualPayment): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ManualPayment, newItem: ManualPayment): Boolean {
            return oldItem == newItem
        }
    }
}