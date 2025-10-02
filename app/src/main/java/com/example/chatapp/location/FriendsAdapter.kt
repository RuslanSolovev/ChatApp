package com.example.chatapp.location

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.chatapp.R

class FriendsAdapter(
    private val friends: List<String>,
    private val friendIds: List<String>,
    private val onChatClick: (Int) -> Unit,
    private val onProfileClick: (Int) -> Unit,
    private val onDeleteClick: (Int) -> Unit,
    private val isOnlineMap: Map<String, Boolean>,
    private val avatarUrls: Map<String, String>
) : RecyclerView.Adapter<FriendsAdapter.FriendViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_friend, parent, false)
        return FriendViewHolder(view)
    }

    override fun onBindViewHolder(holder: FriendViewHolder, position: Int) {
        val friendName = friends[position]
        val friendId = friendIds[position]
        val isOnline = isOnlineMap[friendId] ?: false
        val avatarUrl = avatarUrls[friendId]
        holder.bind(friendName, friendId, isOnline, avatarUrl, position, onChatClick, onProfileClick, onDeleteClick)
    }

    override fun getItemCount(): Int = friends.size

    class FriendViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatarImage: ImageView = itemView.findViewById(R.id.avatarImage)
        private val nameText: TextView = itemView.findViewById(R.id.nameText)
        private val statusText: TextView = itemView.findViewById(R.id.statusText)
        private val chatButton: Button = itemView.findViewById(R.id.chatButton)
        private val profileButton: Button = itemView.findViewById(R.id.profileButton)
        private val deleteButton: Button = itemView.findViewById(R.id.deleteButton)

        fun bind(
            friendName: String,
            friendId: String,
            isOnline: Boolean,
            avatarUrl: String?,
            position: Int,
            onChatClick: (Int) -> Unit,
            onProfileClick: (Int) -> Unit,
            onDeleteClick: (Int) -> Unit
        ) {
            nameText.text = friendName
            statusText.text = if (isOnline) "онлайн" else "оффлайн"
            statusText.setTextColor(if (isOnline) 0xFF00FF00.toInt() else 0xFFFF0000.toInt())

            if (!avatarUrl.isNullOrEmpty()) {
                Glide.with(avatarImage.context)
                    .load(avatarUrl)
                    .circleCrop()
                    .placeholder(R.drawable.avatar_border)
                    .error(R.drawable.avatar_border)
                    .into(avatarImage)
            } else {
                Glide.with(avatarImage.context)
                    .load(R.drawable.avatar_border)
                    .circleCrop()
                    .into(avatarImage)
            }

            chatButton.setOnClickListener { onChatClick(position) }
            profileButton.setOnClickListener { onProfileClick(position) }
            deleteButton.setOnClickListener { onDeleteClick(position) }
        }
    }
}