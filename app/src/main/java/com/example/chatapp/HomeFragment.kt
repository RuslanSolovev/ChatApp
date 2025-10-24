package com.example.chatapp.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.example.chatapp.R
import com.example.chatapp.adapters.HomePagerAdapter
import com.example.chatapp.novosti.FeedFragment

class HomeFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var btnNews: androidx.appcompat.widget.AppCompatImageView
    private lateinit var btnChats: androidx.appcompat.widget.AppCompatImageView
    private lateinit var btnGroups: androidx.appcompat.widget.AppCompatImageView
    private var homePagerAdapter: HomePagerAdapter? = null

    companion object {
        private const val TAG = "HomeFragment"
        private const val ARG_OPEN_NEWS_TAB = "open_news_tab"

        fun newInstance(openNewsTab: Boolean = false): HomeFragment {
            return HomeFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_OPEN_NEWS_TAB, openNewsTab)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!isAdded || isDetached) {
            return
        }

        try {
            setupViewPager(view)
            setupCompactNavigation()

            // Проверяем, нужно ли открыть вкладку новостей
            if (arguments?.getBoolean(ARG_OPEN_NEWS_TAB, false) == true) {
                viewPager.currentItem = 0
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in onViewCreated", e)
        }
    }

    private fun setupViewPager(view: View) {
        viewPager = view.findViewById(R.id.viewPager)
        btnNews = view.findViewById(R.id.btnNews)
        btnChats = view.findViewById(R.id.btnChats)
        btnGroups = view.findViewById(R.id.btnGroups)

        // Используем childFragmentManager и viewLifecycleOwner.lifecycle
        homePagerAdapter = HomePagerAdapter(childFragmentManager, viewLifecycleOwner.lifecycle)
        viewPager.adapter = homePagerAdapter

        // Оптимизация производительности ViewPager2
        viewPager.offscreenPageLimit = 1
        viewPager.setPageTransformer { page, position ->
            page.alpha = 1 - kotlin.math.abs(position) * 0.3f
        }
    }

    private fun setupCompactNavigation() {
        // Обработка кликов по кнопкам
        btnNews.setOnClickListener {
            switchToPage(0)
        }
        btnChats.setOnClickListener {
            switchToPage(1)
        }
        btnGroups.setOnClickListener {
            switchToPage(2)
        }

        // Слушатель изменения страниц для обновления UI
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateNavigationUI(position)
            }
        })

        // Устанавливаем начальное состояние
        updateNavigationUI(viewPager.currentItem)
    }

    private fun switchToPage(position: Int) {
        if (viewPager.currentItem == position) {
            // Если нажали на активную вкладку, скроллим к началу
            if (position == 0) {
                scrollNewsToTop()
            }
        } else {
            viewPager.currentItem = position
        }
    }

    private fun updateNavigationUI(selectedPosition: Int) {
        // Сбрасываем все цвета на неактивные
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.bg_message_right)
        btnNews.setColorFilter(inactiveColor)
        btnChats.setColorFilter(inactiveColor)
        btnGroups.setColorFilter(inactiveColor)

        // Устанавливаем активный цвет
        val activeColor = ContextCompat.getColor(requireContext(), R.color.black)
        when (selectedPosition) {
            0 -> btnNews.setColorFilter(activeColor)
            1 -> btnChats.setColorFilter(activeColor)
            2 -> btnGroups.setColorFilter(activeColor)
        }
    }

    /**
     * Скроллит новости к началу при повторном нажатии на вкладку
     */
    fun scrollNewsToTop() {
        try {
            if (!isViewInitialized() || !isAdded) {
                return
            }

            Log.d(TAG, "scrollNewsToTop: Попытка скролла новостей к началу")

            if (viewPager.currentItem == 0) {
                val feedFragment = getCurrentFeedFragment()
                if (feedFragment != null && feedFragment.isAdded) {
                    Log.d(TAG, "scrollNewsToTop: FeedFragment найден, вызываем scrollToTopIfNeeded")
                    feedFragment.scrollToTopIfNeeded()
                } else {
                    Log.d(TAG, "scrollNewsToTop: FeedFragment не найден или не добавлен")
                    // Альтернативная попытка получить FeedFragment
                    getFeedFragmentFromAdapter()?.scrollToTopIfNeeded()
                }
            } else {
                Log.d(TAG, "scrollNewsToTop: Не на вкладке новостей, текущая позиция: ${viewPager.currentItem}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in scrollNewsToTop", e)
        }
    }

    /**
     * Получает текущий FeedFragment из ViewPager
     */
    private fun getCurrentFeedFragment(): FeedFragment? {
        if (!isAdded) return null

        return try {
            // Для ViewPager2 используем getItemId и findFragmentByTag
            val fragmentId = homePagerAdapter?.getItemId(viewPager.currentItem) ?: return null

            // Тег формируется по шаблону "f" + fragmentId
            val fragmentTag = "f$fragmentId"

            val fragment = childFragmentManager.findFragmentByTag(fragmentTag)
            if (fragment is FeedFragment && fragment.isAdded) {
                Log.d(TAG, "getCurrentFeedFragment: FeedFragment найден по тегу $fragmentTag")
                fragment
            } else {
                Log.d(TAG, "getCurrentFeedFragment: Фрагмент по тегу $fragmentTag не является FeedFragment или не добавлен")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current FeedFragment", e)
            null
        }
    }

    /**
     * Альтернативный метод получения FeedFragment через адаптер
     */
    private fun getFeedFragmentFromAdapter(): FeedFragment? {
        if (!isAdded) return null

        return try {
            if (viewPager.currentItem == 0) {
                val fragment = homePagerAdapter?.createFragment(0)
                if (fragment is FeedFragment && fragment.isAdded) {
                    Log.d(TAG, "getFeedFragmentFromAdapter: FeedFragment получен из адаптера")
                    fragment
                } else {
                    Log.d(TAG, "getFeedFragmentFromAdapter: Фрагмент из адаптера не является FeedFragment или не добавлен")
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting FeedFragment from adapter", e)
            null
        }
    }

    /**
     * Переключается на вкладку новостей
     */
    fun switchToNewsTab() {
        if (isViewInitialized() && isAdded) {
            viewPager.currentItem = 0
        }
    }

    /**
     * Проверяет, инициализирован ли View и Fragment добавлен
     */
    private fun isViewInitialized(): Boolean {
        return this::viewPager.isInitialized && isAdded && !isDetached
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView: HomeFragment destroyed")

        // Очищаем ссылки на View чтобы избежать утечек памяти
        homePagerAdapter = null
    }

    override fun onDetach() {
        super.onDetach()
        Log.d(TAG, "onDetach: HomeFragment detached")
    }
}