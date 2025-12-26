package com.example.chatapp.location

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.chatapp.R
import com.example.chatapp.databinding.FragmentLocationPagerBinding

class LocationPagerFragment : Fragment() {

    private var _binding: FragmentLocationPagerBinding? = null
    private val binding get() = _binding!!

    private var isTransitionInProgress = false
    private lateinit var viewPager: ViewPager2

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLocationPagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewPager = binding.viewPager
        val btnUsers = binding.compactUsers
        val btnRoute = binding.compactRoute
        val navContainer = binding.navContainer

        val adapter = LocationPagerAdapter(this)
        viewPager.adapter = adapter
        viewPager.isUserInputEnabled = false
        viewPager.offscreenPageLimit = 1

        // Настройка начального состояния
        btnUsers.isSelected = true
        btnRoute.isSelected = false
        updateButtonAppearance(btnUsers, true)
        updateButtonAppearance(btnRoute, false)

        // Обработка выбора кнопок
        btnUsers.setOnClickListener {
            if (!isTransitionInProgress && viewPager.currentItem != 0) {
                switchToPage(0)
            }
        }

        btnRoute.setOnClickListener {
            if (!isTransitionInProgress && viewPager.currentItem != 1) {
                switchToPage(1)
            }
        }

        // Синхронизация с ViewPager
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateButtonSelection(position)
            }

            override fun onPageScrollStateChanged(state: Int) {
                when (state) {
                    ViewPager2.SCROLL_STATE_DRAGGING,
                    ViewPager2.SCROLL_STATE_SETTLING -> {
                        isTransitionInProgress = true
                        navContainer.isVisible = false // Скрываем на время перехода
                    }
                    ViewPager2.SCROLL_STATE_IDLE -> {
                        isTransitionInProgress = false
                        navContainer.isVisible = true // Показываем после перехода
                        // Плавное появление
                        navContainer.animate()
                            .alpha(1f)
                            .setDuration(200)
                            .start()
                    }
                }
            }
        })

        // Настройка интерактивности карточки
        setupContainerInteractions()
    }

    private fun switchToPage(page: Int) {
        isTransitionInProgress = true
        binding.navContainer.isVisible = false

        // Обновляем кнопки визуально
        updateButtonSelection(page)

        // Переключаем страницу
        viewPager.setCurrentItem(page, true)

        // Показываем кнопки через время анимации
        view?.postDelayed({
            isTransitionInProgress = false
            binding.navContainer.isVisible = true
            binding.navContainer.alpha = 0f
            binding.navContainer.animate()
                .alpha(1f)
                .setDuration(200)
                .start()
        }, 300)
    }

    private fun updateButtonSelection(position: Int) {
        val btnUsers = binding.compactUsers
        val btnRoute = binding.compactRoute

        when (position) {
            0 -> {
                btnUsers.isSelected = true
                btnRoute.isSelected = false
                updateButtonAppearance(btnUsers, true)
                updateButtonAppearance(btnRoute, false)
            }
            1 -> {
                btnUsers.isSelected = false
                btnRoute.isSelected = true
                updateButtonAppearance(btnUsers, false)
                updateButtonAppearance(btnRoute, true)
            }
        }
    }

    private fun updateButtonAppearance(button: View, isSelected: Boolean) {
        button.clearAnimation()

        if (isSelected) {
            // Анимация для выбранной кнопки
            button.animate()
                .scaleX(1.1f)
                .scaleY(1.1f)
                .alpha(1f)
                .setDuration(150)
                .start()
        } else {
            // Анимация для невыбранной кнопки
            button.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .alpha(0.6f)
                .setDuration(150)
                .start()
        }
    }

    private fun setupContainerInteractions() {
        binding.navContainer.setOnClickListener {
            if (!isTransitionInProgress) {
                // Легкая анимация нажатия
                binding.navContainer.animate()
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(100)
                    .withEndAction {
                        binding.navContainer.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .start()
                    }
                    .start()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Отменяем все анимации
        binding.compactUsers.clearAnimation()
        binding.compactRoute.clearAnimation()
        binding.navContainer.clearAnimation()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewPager.adapter = null
        _binding = null
    }
}

class LocationPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> MapFragment()
            1 -> RouteTrackerFragment()
            else -> MapFragment()
        }
    }
}