
package com.example.chatapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.chatapp.R
import com.example.chatapp.TopUsersActivity
import com.example.chatapp.models.User
import de.hdodenhof.circleimageview.CircleImageView

class TopUsersAdapter(private var period: Int) : RecyclerView.Adapter<TopUsersAdapter.TopUserViewHolder>() {

    private var users = listOf<User>()

    inner class TopUserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAvatar: CircleImageView = itemView.findViewById(R.id.ivAvatar)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvSteps: TextView = itemView.findViewById(R.id.tvSteps)
        private val tvPosition: TextView = itemView.findViewById(R.id.tvPosition)

        fun bind(user: User, position: Int) {
            tvName.text = user.name ?: "Без имени"
            tvSteps.text = when (period) {
                TopUsersActivity.PERIOD_DAY -> "Шагов сегодня: ${user.totalSteps}"
                TopUsersActivity.PERIOD_WEEK -> "Шагов за неделю: ${user.totalSteps}"
                TopUsersActivity.PERIOD_MONTH -> "Шагов за месяц: ${user.totalSteps}"
                else -> "Шагов: ${user.totalSteps}"
            }
            tvPosition.text = "${position + 1}."

            // Загрузка аватара с Glide
            user.profileImageUrl?.let { url ->
                Glide.with(itemView)
                    .load(url)
                    .circleCrop()
                    .placeholder(R.drawable.ic_default_profile)
                    .into(ivAvatar)
            } ?: ivAvatar.setImageResource(R.drawable.ic_default_profile)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopUserViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_top_user, parent, false)
        return TopUserViewHolder(view)
    }

    override fun onBindViewHolder(holder: TopUserViewHolder, position: Int) {
        holder.bind(users[position], position)
    }

    override fun getItemCount() = users.size

    fun submitList(newUsers: List<User>) {
        users = newUsers
        notifyDataSetChanged()
    }

    fun updatePeriod(newPeriod: Int) {
        period = newPeriod
        notifyDataSetChanged()
    }
}
/*
 * Адаптер для отображения списка пользователей в рейтинге.
 * Функционал:
 *  - Отображает аватар, имя, позицию и количество шагов пользователя
 *  - Форматирует текст в зависимости от выбранного периода (день/неделя/месяц)
 *  - Динамически обновляет список при изменении данных
 *  - Использует Glide для загрузки изображений
 */