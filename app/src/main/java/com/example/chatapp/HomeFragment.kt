package com.example.chatapp.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.viewpager2.widget.ViewPager2
import com.example.chatapp.R
import com.example.chatapp.adapters.HomePagerAdapter
import com.example.chatapp.novosti.FeedFragment
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class HomeFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
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

        // Проверяем, что Fragment еще attached к Activity
        if (!isAdded || isDetached) {
            return
        }

        try {
            setupViewPager(view)
            setupTabs()

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
        tabLayout = view.findViewById(R.id.tabLayout)

        // Используем childFragmentManager и viewLifecycleOwner.lifecycle
        homePagerAdapter = HomePagerAdapter(childFragmentManager, viewLifecycleOwner.lifecycle)
        viewPager.adapter = homePagerAdapter

        // Оптимизация производительности ViewPager2
        viewPager.offscreenPageLimit = 1
        viewPager.setPageTransformer { page, position ->
            // Простая трансформация для производительности
            page.alpha = 1 - kotlin.math.abs(position) * 0.3f
        }
    }

    private fun setupTabs() {
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Лента"
                1 -> "Мои чаты"
                2 -> "Беседы"
                else -> null
            }
        }.attach()

        // Добавляем слушатель изменения страниц для отладки
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                Log.d(TAG, "onPageSelected: position=$position")
            }

            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                // Можно добавить логику при изменении состояния скролла
            }
        })
    }

    fun switchToNewsTab() {
        if (isViewInitialized() && isAdded) {
            viewPager.currentItem = 0
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

            // Проверяем, что мы на вкладке новостей (позиция 0)
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