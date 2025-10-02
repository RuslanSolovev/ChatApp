package com.example.chatapp.mozgi.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.chatapp.R
import com.example.chatapp.mozgi.data.UserResult

class LeaderboardAdapter : RecyclerView.Adapter<LeaderboardAdapter.ViewHolder>() {
    private var results = listOf<UserResult>()

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val positionText: TextView = itemView.findViewById(R.id.tvPosition)
        val nameText: TextView = itemView.findViewById(R.id.tvUserName)
        val scoreText: TextView = itemView.findViewById(R.id.tvScore)
        val timeText: TextView = itemView.findViewById(R.id.tvTime)
        val iqText: TextView = itemView.findViewById(R.id.tvIQ)
        val avatarImage: ImageView = itemView.findViewById(R.id.ivUserAvatar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_leaderboard, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = results[position]
        holder.positionText.text = "#${position + 1}"
        holder.nameText.text = result.userName
        holder.scoreText.text = "${result.correctAnswers}/${result.totalQuestions}"
        holder.timeText.text = "${result.timeTaken / 1000} сек"
        holder.iqText.text = "IQ: ${result.iq}"

        result.profileImageUrl?.let { url ->
            Glide.with(holder.itemView)
                .load(url)
                .circleCrop()
                .placeholder(R.drawable.ic_default_profile)
                .into(holder.avatarImage)
        } ?: holder.avatarImage.setImageResource(R.drawable.ic_default_profile)
    }

    override fun getItemCount(): Int = results.size

    fun submitList(newResults: List<UserResult>) {
        results = newResults
        notifyDataSetChanged()
    }
}