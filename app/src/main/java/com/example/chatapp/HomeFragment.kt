package com.example.chatapp.fragments

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.example.chatapp.R
import com.example.chatapp.activities.MainActivity
import com.example.chatapp.adapters.HomePagerAdapter
import com.example.chatapp.novosti.FeedFragment

class HomeFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var btnNews: androidx.appcompat.widget.AppCompatImageView
    private lateinit var btnChats: androidx.appcompat.widget.AppCompatImageView
    private lateinit var btnGroups: androidx.appcompat.widget.AppCompatImageView
    private lateinit var navContainer: LinearLayout
    private var homePagerAdapter: HomePagerAdapter? = null

    // Переменные для управления видимостью навигации
    private var isNavHidden = false
    private var isInitialized = false
    private val handler = Handler(Looper.getMainLooper())

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
            isInitialized = true

            // Проверяем, нужно ли открыть вкладку новостей
            if (arguments?.getBoolean(ARG_OPEN_NEWS_TAB, false) == true) {
                viewPager.currentItem = 0
            }

            // Устанавливаем начальное состояние навигации (видима)
            resetNavigationState()

        } catch (e: Exception) {
            Log.e(TAG, "Error in onViewCreated", e)
        }
    }

    private fun setupViewPager(view: View) {
        viewPager = view.findViewById(R.id.viewPager)
        btnNews = view.findViewById(R.id.btnNews)
        btnChats = view.findViewById(R.id.btnChats)
        btnGroups = view.findViewById(R.id.btnGroups)
        navContainer = view.findViewById(R.id.navContainer)

        // Используем childFragmentManager и viewLifecycleOwner.lifecycle
        homePagerAdapter = HomePagerAdapter(childFragmentManager, viewLifecycleOwner.lifecycle)
        viewPager.adapter = homePagerAdapter

        // Оптимизация производительности ViewPager2
        viewPager.offscreenPageLimit = 1
        viewPager.setPageTransformer { page, position ->
            page.alpha = 1 - kotlin.math.abs(position) * 0.3f
        }

        // Слушатель изменения страниц для обновления UI
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateNavigationUI(position)
                handlePageChange(position)
            }

            override fun onPageScrollStateChanged(state: Int) {
                // При остановке скролла между страницами гарантируем показ навигации
                if (state == ViewPager2.SCROLL_STATE_IDLE && viewPager.currentItem == 0) {
                    showNavigationInParent()
                }
            }
        })
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
        btnNews.setImageResource(
            if (selectedPosition == 0) R.drawable.ic_external_news_active else R.drawable.ic_external_news_inactive
        )
        btnChats.setImageResource(
            if (selectedPosition == 1) R.drawable.ic_chat_active else R.drawable.ic_chat_inactive
        )
        btnGroups.setImageResource(
            if (selectedPosition == 2) R.drawable.ic_group_active else R.drawable.ic_group_inactive
        )

        if (selectedPosition == 0) {
            setupFeedFragmentNavigationListener()
        }
    }

    /**
     * Обновленная реализация интерфейса управления навигацией
     */
    private fun setupFeedFragmentNavigationListener() {
        val feedFragment = getCurrentFeedFragment()
        feedFragment?.setNavigationVisibilityListener(
            object : FeedFragment.OnNavigationVisibilityChangeListener {
                override fun onHideFullNavigation() {
                    // Скрываем компактную навигацию
                    hideNavigationInParent()
                    // Уведомляем MainActivity о скрытии верхней навигации
                    (activity as? MainActivity)?.hideTopNavigation()
                }

                override fun onShowFullNavigation() {
                    // Показываем компактную навигацию
                    showNavigationInParent()
                    // Уведомляем MainActivity о показе верхней навигации
                    (activity as? MainActivity)?.showTopNavigation()
                }

                // Реализации методов для обратной совместимости
                override fun onHideNavigation() {
                    onHideFullNavigation()
                }

                override fun onShowNavigation() {
                    onShowFullNavigation()
                }

                override fun onHideTopNavigation() {
                    onHideFullNavigation()
                }

                override fun onShowTopNavigation() {
                    onShowFullNavigation()
                }
            }
        )
    }

    /**
     * Улучшенное скрытие компактной навигации с плавной анимацией
     */
    fun hideNavigationInParent() {
        if (!isViewInitialized() || isNavHidden) return

        try {
            isNavHidden = true

            // Используем ValueAnimator для более плавной анимации
            val animator = ValueAnimator.ofFloat(1f, 0.5f).apply { // Оставляем полупрозрачной
                duration = 350
                interpolator = AccelerateInterpolator(1.2f)

                addUpdateListener { animation ->
                    val value = animation.animatedValue as Float
                    navContainer.alpha = value
                    navContainer.scaleX = 0.9f + 0.1f * value
                    navContainer.scaleY = 0.9f + 0.1f * value
                }

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // Не скрываем полностью, оставляем видимой
                        Log.d(TAG, "hideNavigationInParent: Компактная навигация стала полупрозрачной")
                    }
                })
            }

            animator.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error hiding parent navigation", e)
            isNavHidden = false
        }
    }

    /**
     * Улучшенное отображение компактной навигации с плавной анимацией
     */
    fun showNavigationInParent() {
        if (!isViewInitialized() || !isNavHidden) return

        try {
            isNavHidden = false

            // Используем ValueAnimator для более плавной анимации
            val animator = ValueAnimator.ofFloat(navContainer.alpha, 1f).apply {
                duration = 400
                interpolator = OvershootInterpolator(0.8f)

                addUpdateListener { animation ->
                    val value = animation.animatedValue as Float
                    navContainer.alpha = value
                    navContainer.scaleX = 0.9f + 0.1f * value
                    navContainer.scaleY = 0.9f + 0.1f * value
                }

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        navContainer.scaleX = 1f
                        navContainer.scaleY = 1f
                    }
                })
            }

            animator.start()
            Log.d(TAG, "showNavigationInParent: Компактная навигация плавно показана")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing parent navigation", e)
            isNavHidden = true
        }
    }

    /**
     * Обработка смены страниц
     */
    private fun handlePageChange(position: Int) {
        if (position == 0) {
            // На вкладке новостей - настраиваем слушатель для FeedFragment
            setupFeedFragmentNavigationListener()
            // Гарантируем показ навигации при переходе на вкладку новостей
            showNavigationInParent()
        } else {
            // На других вкладках - всегда показываем навигацию
            showNavigationInParent()
        }
    }

    /**
     * Сброс состояния навигации к видимому состоянию
     */
    private fun resetNavigationState() {
        isNavHidden = false

        if (isViewInitialized()) {
            navContainer.visibility = View.VISIBLE
            navContainer.translationY = 0f
            navContainer.alpha = 1f
            navContainer.scaleX = 1f
            navContainer.scaleY = 1f
        }
    }

    /**
     * Сброс всей навигации (компактной и верхней)
     */
    fun resetAllNavigation() {
        showNavigationInParent()
        (activity as? MainActivity)?.showTopNavigation()
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

                    // При скролле к началу гарантируем показ всей навигации
                    showNavigationInParent()
                    (activity as? MainActivity)?.showTopNavigation()
                } else {
                    Log.d(TAG, "scrollNewsToTop: FeedFragment не найден или не добавлен")
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
            val fragmentId = homePagerAdapter?.getItemId(viewPager.currentItem) ?: return null
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
            // При переключении на вкладку новостей гарантируем показ всей навигации
            showNavigationInParent()
            (activity as? MainActivity)?.showTopNavigation()
        }
    }

    /**
     * Проверяет, инициализирован ли View и Fragment добавлен
     */
    private fun isViewInitialized(): Boolean {
        return isInitialized &&
                this::viewPager.isInitialized &&
                this::navContainer.isInitialized &&
                isAdded &&
                !isDetached
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: HomeFragment resumed")

        // При возобновлении фрагмента гарантируем показ всей навигации
        if (isViewInitialized() && viewPager.currentItem == 0) {
            // Задержка для плавного появления
            handler.postDelayed({
                showNavigationInParent()
                (activity as? MainActivity)?.showTopNavigation()
                setupFeedFragmentNavigationListener()
            }, 100)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: HomeFragment paused")

        // При паузе сбрасываем состояние навигации
        isNavHidden = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView: HomeFragment destroyed")

        // Убираем все отложенные задачи
        handler.removeCallbacksAndMessages(null)

        // Сбрасываем флаги
        isInitialized = false
        isNavHidden = false

        // Очищаем ссылки
        homePagerAdapter = null
    }

    override fun onDetach() {
        super.onDetach()
        Log.d(TAG, "onDetach: HomeFragment detached")
    }

    /**
     * Возвращает информацию о состоянии навигации
     */
    fun getNavigationState(): String {
        return """
            HomeFragment Navigation State:
            ----------------------------
            Is initialized: $isInitialized
            Is navigation hidden: $isNavHidden
            Current page: ${viewPager.currentItem}
            View initialized: ${isViewInitialized()}
            Nav container visible: ${navContainer.visibility == View.VISIBLE}
            Nav container alpha: ${navContainer.alpha}
        """.trimIndent()
    }

    /**
     * Принудительное скрытие навигации (для тестов)
     */
    fun forceHideNavigation() {
        hideNavigationInParent()
    }

    /**
     * Принудительное отображение навигации (для тестов)
     */
    fun forceShowNavigation() {
        showNavigationInParent()
    }

    /**
     * Получить текущее состояние навигации
     */
    fun isNavigationVisible(): Boolean {
        return isViewInitialized() && !isNavHidden && navContainer.visibility == View.VISIBLE
    }
}