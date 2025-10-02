package com.example.chatapp.muzika.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.chatapp.muzika.ui.PlaylistFragment
import com.example.chatapp.muzika.ui.SearchFragment

class MusicPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> SearchFragment()
            1 -> PlaylistFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}