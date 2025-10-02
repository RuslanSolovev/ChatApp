package com.example.chatapp.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
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
        viewPager = view.findViewById(R.id.viewPager)
        tabLayout = view.findViewById(R.id.tabLayout)

        homePagerAdapter = HomePagerAdapter(childFragmentManager, lifecycle)
        viewPager.adapter = homePagerAdapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Лента"
                1 -> "Мои чаты"
                2 -> "Беседы"
                else -> null
            }
        }.attach()

        // Проверяем, нужно ли открыть вкладку новостей
        if (arguments?.getBoolean("open_news_tab", false) == true) {
            viewPager.currentItem = 0
        }

        // Добавляем слушатель изменения страниц для отладки
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                Log.d(TAG, "onPageSelected: position=$position")
            }
        })
    }

    fun switchToNewsTab() {
        if (this::viewPager.isInitialized) {
            viewPager.currentItem = 0
        }
    }

    /**
     * Скроллит новости к началу при повторном нажатии на вкладку
     */
    fun scrollNewsToTop() {
        try {
            Log.d(TAG, "scrollNewsToTop: Попытка скролла новостей к началу")

            // Проверяем, что мы на вкладке новостей (позиция 0)
            if (this::viewPager.isInitialized && viewPager.currentItem == 0) {
                val feedFragment = getCurrentFeedFragment()
                if (feedFragment != null) {
                    Log.d(TAG, "scrollNewsToTop: FeedFragment найден, вызываем scrollToTopIfNeeded")
                    feedFragment.scrollToTopIfNeeded()
                } else {
                    Log.d(TAG, "scrollNewsToTop: FeedFragment не найден")
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
        return try {
            // Для ViewPager2 используем getItemId и findFragmentByTag
            val fragmentId = homePagerAdapter?.getItemId(viewPager.currentItem) ?: return null

            // Тег формируется по шаблону "f" + fragmentId
            val fragmentTag = "f$fragmentId"

            val fragment = childFragmentManager.findFragmentByTag(fragmentTag)
            if (fragment is FeedFragment) {
                Log.d(TAG, "getCurrentFeedFragment: FeedFragment найден по тегу $fragmentTag")
                fragment
            } else {
                Log.d(TAG, "getCurrentFeedFragment: Фрагмент по тегу $fragmentTag не является FeedFragment: ${fragment?.javaClass?.simpleName}")
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
        return try {
            if (viewPager.currentItem == 0) {
                val fragment = homePagerAdapter?.createFragment(0)
                if (fragment is FeedFragment) {
                    Log.d(TAG, "getFeedFragmentFromAdapter: FeedFragment получен из адаптера")
                    fragment
                } else {
                    Log.d(TAG, "getFeedFragmentFromAdapter: Фрагмент из адаптера не является FeedFragment")
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

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView: HomeFragment destroyed")
    }
}