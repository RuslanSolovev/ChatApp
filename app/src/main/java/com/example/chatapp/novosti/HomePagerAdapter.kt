package com.example.chatapp.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.chatapp.novosti.FeedFragment
import com.example.chatapp.fragments.GroupChatsFragment
import com.example.chatapp.fragments.MyChatsFragment

class HomePagerAdapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle
) : FragmentStateAdapter(fragmentManager, lifecycle) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> FeedFragment()
            1 -> MyChatsFragment()
            2 -> GroupChatsFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }

    /**
     * Генерирует уникальный ID для фрагмента
     * Это нужно для правильного поиска фрагмента по тегу
     */
    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    /**
     * Проверяет, содержат ли фрагменты одинаковые данные
     */
    override fun containsItem(itemId: Long): Boolean {
        return itemId in 0 until itemCount
    }
}