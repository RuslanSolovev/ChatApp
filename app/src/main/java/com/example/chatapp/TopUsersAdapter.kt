package com.example.chatapp.adapters

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.chatapp.R
import com.example.chatapp.TopUsersActivity
import com.example.chatapp.models.User
import de.hdodenhof.circleimageview.CircleImageView

class TopUsersAdapter : RecyclerView.Adapter<TopUsersAdapter.TopUserViewHolder>() {

    private var users = listOf<User>()
    private var currentPeriod = TopUsersActivity.PERIOD_DAY

    inner class TopUserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val positionView: TextView = itemView.findViewById(R.id.tvPosition)
        private val avatarView: CircleImageView = itemView.findViewById(R.id.ivAvatar)
        private val nameView: TextView = itemView.findViewById(R.id.tvName)
        private val stepsView: TextView = itemView.findViewById(R.id.tvSteps)
        private val medalView: ImageView = itemView.findViewById(R.id.ivMedal)

        fun bind(user: User) {
            try {
                positionView.text = user.position.toString()
                nameView.text = user.name

                stepsView.text = when (currentPeriod) {
                    TopUsersActivity.PERIOD_DAY -> "Шагов сегодня: ${user.totalSteps}"
                    TopUsersActivity.PERIOD_WEEK -> "Шагов за неделю: ${user.totalSteps}"
                    TopUsersActivity.PERIOD_MONTH -> "Шагов за месяц: ${user.totalSteps}"
                    else -> "Шагов: ${user.totalSteps}"
                }

                // Загрузка аватара
                user.profileImageUrl?.let { url ->
                    Glide.with(itemView)
                        .load(url)
                        .circleCrop()
                        .placeholder(R.drawable.ic_default_profile)
                        .into(avatarView)
                } ?: avatarView.setImageResource(R.drawable.ic_default_profile)

                // Медали для топ-3
                when (user.position) {
                    1 -> {
                        medalView.visibility = View.VISIBLE
                        medalView.setImageResource(R.drawable.ic_medal_gold)
                        positionView.background = ContextCompat.getDrawable(
                            itemView.context, R.drawable.bg_position_circle_gold
                        )
                    }
                    2 -> {
                        medalView.visibility = View.VISIBLE
                        medalView.setImageResource(R.drawable.ic_medal_silver)
                        positionView.background = ContextCompat.getDrawable(
                            itemView.context, R.drawable.bg_position_circle_silver
                        )
                    }
                    3 -> {
                        medalView.visibility = View.VISIBLE
                        medalView.setImageResource(R.drawable.ic_medal_bronze)
                        positionView.background = ContextCompat.getDrawable(
                            itemView.context, R.drawable.bg_position_circle_bronze
                        )
                    }
                    else -> {
                        medalView.visibility = View.GONE
                        positionView.background = ContextCompat.getDrawable(
                            itemView.context, R.drawable.bg_position_circle
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("TopUsersAdapter", "Error binding user data", e)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopUserViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_top_user, parent, false)
        return TopUserViewHolder(view)
    }

    override fun onBindViewHolder(holder: TopUserViewHolder, position: Int) {
        holder.bind(users[position])
    }

    override fun getItemCount() = users.size

    fun submitList(newUsers: List<User>, period: Int) {
        currentPeriod = period
        users = newUsers
        notifyDataSetChanged()
    }
}