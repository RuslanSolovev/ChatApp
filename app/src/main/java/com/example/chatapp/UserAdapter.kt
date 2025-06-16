package com.example.chatapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.chatapp.R
import com.example.chatapp.models.User
import java.util.HashSet

class UserAdapter(
    private val onUserSelected: (User, Boolean) -> Unit,
    private val onUserProfileClick: (String) -> Unit
) : RecyclerView.Adapter<UserAdapter.ViewHolder>() {

    private var originalUsers = listOf<User>()
    private var filteredUsers = listOf<User>()
    private val selectedUserIds = HashSet<String>()

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvUserName)
        private val tvEmail: TextView = itemView.findViewById(R.id.tvUserEmail)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivUserAvatar)
        private val checkBox: CheckBox = itemView.findViewById(R.id.cbSelectUser)

        fun bind(user: User) {
            tvName.text = user.name ?: "Без имени"
            tvEmail.text = user.email ?: "Нет email"
            checkBox.isChecked = selectedUserIds.contains(user.uid)

            // Загрузка аватарки
            user.profileImageUrl?.let { url ->
                Glide.with(itemView)
                    .load(url)
                    .circleCrop()
                    .placeholder(R.drawable.ic_default_profile)
                    .into(ivAvatar)
            } ?: ivAvatar.setImageResource(R.drawable.ic_default_profile)

            // Обработчик выбора через чекбокс
            checkBox.setOnClickListener {
                val isSelected = checkBox.isChecked
                if (isSelected) {
                    selectedUserIds.add(user.uid)
                } else {
                    selectedUserIds.remove(user.uid)
                }
                onUserSelected(user, isSelected)
            }

            // Клик по аватарке - переход в профиль
            ivAvatar.setOnClickListener {
                onUserProfileClick(user.uid)
            }

            // Клик по имени - переход в профиль
            tvName.setOnClickListener {
                onUserProfileClick(user.uid)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(filteredUsers[position])
    }

    override fun getItemCount(): Int = filteredUsers.size

    fun submitList(users: List<User>) {
        originalUsers = users
        filteredUsers = users
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        filteredUsers = if (query.isEmpty()) {
            originalUsers
        } else {
            originalUsers.filter { user ->
                user.name?.contains(query, ignoreCase = true) == true ||
                        user.email?.contains(query, ignoreCase = true) == true
            }
        }
        notifyDataSetChanged()
    }

    fun getSelectedUsers(): List<User> {
        return originalUsers.filter { selectedUserIds.contains(it.uid) }
    }

    fun clearSelection() {
        selectedUserIds.clear()
        notifyDataSetChanged()
    }
}