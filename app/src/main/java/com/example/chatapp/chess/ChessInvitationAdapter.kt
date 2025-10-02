package com.example.chatapp.chess

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R

class ChessInvitationAdapter(
    private val onAccept: (ChessInvitation, String) -> Unit,
    private val onDecline: (ChessInvitation, String) -> Unit
) : ListAdapter<Pair<String, ChessInvitation>, ChessInvitationAdapter.InvitationViewHolder>(InvitationDiffCallback()) {

    inner class InvitationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fromNameTextView: TextView = itemView.findViewById(R.id.invitationFromName)
        private val statusTextView: TextView = itemView.findViewById(R.id.invitationStatus)
        private val acceptButton: Button = itemView.findViewById(R.id.acceptButton)
        private val declineButton: Button = itemView.findViewById(R.id.declineButton)

        fun bind(invitationPair: Pair<String, ChessInvitation>) {
            val (fromUserId, invitation) = invitationPair

            fromNameTextView.text = "От: ${invitation.fromName}"

            when (invitation.status) {
                "pending" -> {
                    statusTextView.text = "Ожидает ответа"
                    statusTextView.setTextColor(itemView.context.getColor(R.color.colorPrimary))
                    acceptButton.visibility = View.VISIBLE
                    declineButton.visibility = View.VISIBLE
                }
                "accepted" -> {
                    statusTextView.text = "Принято"
                    statusTextView.setTextColor(itemView.context.getColor(R.color.colorPrimaryLight))
                    acceptButton.visibility = View.GONE
                    declineButton.visibility = View.GONE
                }
                "rejected" -> {
                    statusTextView.text = "Отклонено"
                    statusTextView.setTextColor(itemView.context.getColor(R.color.red))
                    acceptButton.visibility = View.GONE
                    declineButton.visibility = View.GONE
                }
            }

            acceptButton.setOnClickListener {
                onAccept(invitation, fromUserId)
            }

            declineButton.setOnClickListener {
                onDecline(invitation, fromUserId)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InvitationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chess_invitation, parent, false)
        return InvitationViewHolder(view)
    }

    override fun onBindViewHolder(holder: InvitationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class InvitationDiffCallback : DiffUtil.ItemCallback<Pair<String, ChessInvitation>>() {
        override fun areItemsTheSame(
            oldItem: Pair<String, ChessInvitation>,
            newItem: Pair<String, ChessInvitation>
        ): Boolean {
            return oldItem.first == newItem.first
        }

        override fun areContentsTheSame(
            oldItem: Pair<String, ChessInvitation>,
            newItem: Pair<String, ChessInvitation>
        ): Boolean {
            return oldItem.second.status == newItem.second.status
        }
    }
}