package com.example.chatapp

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.chatapp.fragments.LocationFragment // Убедитесь, что импорт правильный
import de.hdodenhof.circleimageview.CircleImageView
import java.lang.ref.WeakReference // <-- Импорт WeakReference

class ParticipantAdapter(
    var participantList: List<Participant>,
    fragment: LocationFragment // <-- Принимаем обычную ссылку
) : RecyclerView.Adapter<ParticipantAdapter.ParticipantViewHolder>() {

    companion object {
        private const val TAG = "ParticipantAdapter"
    }

    // Храним слабую ссылку на фрагмент
    private val fragmentRef: WeakReference<LocationFragment> = WeakReference(fragment) // <-- Используем WeakReference

    class ParticipantViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatarImageView: CircleImageView = view.findViewById(R.id.ivParticipantAvatar)
        val nameTextView: TextView = view.findViewById(R.id.tvParticipantName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ParticipantViewHolder {
        Log.d(TAG, "onCreateViewHolder called for viewType: $viewType")
        // Получаем фрагмент через WeakReference при создании ViewHolder
        val fragment = fragmentRef.get()
        // Проверяем, доступен ли фрагмент и прикреплен ли он
        if (fragment == null || !fragment.isAdded) {
            Log.w(TAG, "onCreateViewHolder: Fragment недоступен, используем context из parent")
            // Используем context из parent в качестве запасного варианта
            val itemView = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_participant, parent, false)
            return ParticipantViewHolder(itemView)
        }
        // Используем контекст фрагмента для инфляции
        val itemView = LayoutInflater.from(fragment.requireContext())
            .inflate(R.layout.item_participant, parent, false)
        Log.d(TAG, "Item view inflated successfully")
        return ParticipantViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ParticipantViewHolder, position: Int) {
        if (position >= participantList.size) {
            Log.e(TAG, "onBindViewHolder: IndexOutOfBoundsException. position=$position, size=${participantList.size}")
            return
        }

        val participant = participantList[position]
        Log.d(TAG, "onBindViewHolder: Binding item at position $position, name: ${participant.name}, avatar: ${participant.profileImageUrl}")

        holder.nameTextView.text = participant.name
        Log.d(TAG, "onBindViewHolder: Text '${participant.name}' set to TextView")

        // Получаем фрагмент через WeakReference при связывании
        val fragment = fragmentRef.get()
        // Проверяем, доступен ли фрагмент и прикреплен ли он
        if (fragment == null || !fragment.isAdded) {
            Log.w(TAG, "onBindViewHolder: Fragment недоступен, аватар не загружается")
            // Устанавливаем заглушку, если фрагмент недоступен
            holder.avatarImageView.setImageResource(R.drawable.ic_default_profile)
            return
        }

        // Загрузка аватара с использованием Glide
        if (!participant.profileImageUrl.isNullOrEmpty()) {
            // Используем фрагмент из WeakReference для Glide
            Glide.with(fragment)
                .load(participant.profileImageUrl)
                .placeholder(R.drawable.ic_default_profile)
                .error(R.drawable.ic_default_profile)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(holder.avatarImageView)
            Log.d(TAG, "onBindViewHolder: Запущена загрузка аватара из URL: ${participant.profileImageUrl}")
        } else {
            holder.avatarImageView.setImageResource(R.drawable.ic_default_profile)
            Log.d(TAG, "onBindViewHolder: Установлена локальная заглушка для аватара")
        }
    }

    override fun getItemCount(): Int {
        val count = participantList.size
        Log.d(TAG, "getItemCount: returning $count")
        return count
    }

    fun updateParticipants(newList: List<Participant>) {
        Log.d(TAG, "updateParticipants called. Old size: ${participantList.size}, New size: ${newList.size}")

        newList.forEachIndexed { index, participant ->
            Log.d(TAG, "  New item $index: userId=${participant.userId}, name=${participant.name}, avatar=${participant.profileImageUrl}")
        }

        // Обновляем внутренний список
        this.participantList = newList.toList()
        Log.d(TAG, "updateParticipants: List reference updated")
        // Уведомляем адаптер об изменениях
        notifyDataSetChanged()
        Log.d(TAG, "updateParticipants: notifyDataSetChanged called")
    }

    // Добавляем геттер для получения списка участников
    val participants: List<Participant>
        get() = participantList
}