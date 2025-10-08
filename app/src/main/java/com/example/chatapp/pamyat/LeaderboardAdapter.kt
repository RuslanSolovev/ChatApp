package com.example.chatapp.pamyat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.chatapp.R
import com.example.chatapp.models.User
import de.hdodenhof.circleimageview.CircleImageView

class MemoryLeaderboardAdapter(private val users: List<UserWithScore>) :
    RecyclerView.Adapter<MemoryLeaderboardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPosition: TextView = view.findViewById(R.id.tvPosition)
        val ivAvatar: CircleImageView = view.findViewById(R.id.ivAvatar)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvScore: TextView = view.findViewById(R.id.tvScore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_memory_leaderboard, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val userWithScore = users[position]

        holder.tvPosition.text = "${position + 1}"
        holder.tvName.text = userWithScore.user.getFullName()
        holder.tvScore.text = userWithScore.score.toString()

        // Загрузка аватара
        userWithScore.user.profileImageUrl?.let { url ->
            Glide.with(holder.itemView.context)
                .load(url)
                .circleCrop()
                .placeholder(R.drawable.ic_default_profile)
                .into(holder.ivAvatar)
        } ?: holder.ivAvatar.setImageResource(R.drawable.ic_default_profile)
    }

    override fun getItemCount() = users.size

    data class UserWithScore(val user: User, val score: Int)
}