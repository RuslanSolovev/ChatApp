package com.example.chatapp.novosti

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.activities.MainActivity
import com.example.chatapp.databinding.FragmentFeedBinding
import com.example.chatapp.fragments.HomeFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class FeedFragment : Fragment() {

    private var _binding: FragmentFeedBinding? = null
    private val binding get() = _binding!!
    private lateinit var newsAdapter: NewsAdapter
    private val newsRepository = NewsRepository()
    private lateinit var newsFetcher: NewsFetcher

    private var hasFetchedExternalNewsOnThisResume = false
    private var fetchJob: Job? = null
    private var isFirstLoad = true
    private var isLoading = false

    // КЭШИРОВАНИЕ
    private var lastLoadTime: Long = 0
    private var isFragmentVisible = false
    private var shouldReloadOnResume = false
    private val CACHE_DURATION = 5 * 60 * 1000L
    private val FORCE_RELOAD_DURATION = 10 * 60 * 1000L
    private var currentNewsCount = 0

    // Обновленный интерфейс для управления навигацией
    interface OnNavigationVisibilityChangeListener {
        // Основные методы
        fun onHideFullNavigation()
        fun onShowFullNavigation()

        // Методы для обратной совместимости с дефолтными реализациями
        fun onHideNavigation() = onHideFullNavigation()
        fun onShowNavigation() = onShowFullNavigation()
        fun onHideTopNavigation() = onHideFullNavigation()
        fun onShowTopNavigation() = onShowFullNavigation()
    }



    private var isProcessingNavigation = false
    private var isNavigationHidden = false
    private var lastScrollY = 0
    private var totalScrolled = 0
    private var navigationListener: OnNavigationVisibilityChangeListener? = null
    private var isScrollingUp = false
    private var isScrollingDown = false
    private var lastScrollDirection = 0

    companion object {
        private const val TAG = "FeedFragment"
        private const val LOADING_DELAY = 500L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (_binding == null) {
            Log.e(TAG, "onViewCreated: Binding is null, cannot setup UI")
            return
        }

        newsFetcher = NewsFetcher(newsRepository, requireContext())

        setupRecyclerView()
        setupSwipeRefresh()
        setupFab()

        // Настраиваем слушатель скролла ДО загрузки данных
        setupScrollListener()

        // Проверяем, нужно ли загружать заново или использовать кэш
        if (shouldReloadFromScratch()) {
            Log.d(TAG, "Loading news from scratch")
            safeUpdateUI { binding ->
                binding.progressBar.visibility = View.VISIBLE
            }
            loadNewsOptimized()
        } else {
            Log.d(TAG, "Using cached data, only background refresh")
            loadCachedNewsWithBackgroundRefresh()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: called, isFragmentVisible: $isFragmentVisible, lastLoad: ${if (lastLoadTime == 0L) "never" else "${(System.currentTimeMillis() - lastLoadTime) / 1000} sec ago"}")

        isFragmentVisible = true

        // При возвращении на фрагмент всегда показываем ВСЮ навигацию
        showNavigationWithDelay()

        if (!hasFetchedExternalNewsOnThisResume && !isFirstLoad) {
            Log.d(TAG, "onResume: Background refresh only")
            lifecycleScope.launch {
                refreshNewsQuietly()
            }
            hasFetchedExternalNewsOnThisResume = true
        } else {
            safeUpdateUI { binding ->
                binding.progressBar.visibility = View.GONE
            }
        }

        // Если был запрос на перезагрузку при возвращении
        if (shouldReloadOnResume) {
            Log.d(TAG, "onResume: Performing requested reload")
            shouldReloadOnResume = false
            lifecycleScope.launch {
                reloadIfNeeded()
            }
        }

        isFirstLoad = false
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: fragment becoming invisible")
        isFragmentVisible = false
        fetchJob?.cancel()
        isLoading = false

        // При уходе с фрагмента сбрасываем состояние скролла
        resetScrollState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView: Cleaning up resources")
        fetchJob?.cancel()
        isLoading = false

        // При уничтожении View обязательно показываем всю навигацию
        if (isNavigationHidden) {
            showNavigationSmoothly()
        }

        _binding = null
    }

    /**
     * Улучшенная обработка скролла с защитой от множественных вызовов
     */
    private fun setupScrollListener() {
        safeUpdateUI { binding ->
            binding.rvNews.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                private var lastScrollY = 0
                private var accumulatedDy = 0
                private val SCROLL_THRESHOLD = 150 // Увеличенный порог
                private var isProcessingNavigation = false
                private var lastNavigationTime = 0L
                private val NAVIGATION_COOLDOWN = 300L // Задержка между вызовами

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    // Защита от слишком частых вызовов
                    val now = System.currentTimeMillis()
                    if (now - lastNavigationTime < NAVIGATION_COOLDOWN) {
                        return
                    }

                    accumulatedDy += dy
                    lastScrollY += dy

                    // Определяем направление скролла с гистерезисом
                    if (Math.abs(accumulatedDy) >= SCROLL_THRESHOLD && !isProcessingNavigation) {
                        if (accumulatedDy > 0 && !isNavigationHidden) {
                            // Скролл вниз - плавно скрываем навигацию
                            hideNavigationSmoothly()
                            accumulatedDy = 0
                            lastNavigationTime = now
                        } else if (accumulatedDy < 0 && isNavigationHidden) {
                            // Скролл вверх - плавно показываем навигацию
                            showNavigationSmoothly()
                            accumulatedDy = 0
                            lastNavigationTime = now
                        }
                    }

                    // Скрытие FAB при скролле вниз
                    if (dy > 10) {
                        binding.fabAddNews.hide()
                    } else if (dy < -10) {
                        binding.fabAddNews.show()
                    }
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)

                    when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> {
                            // При остановке скролла проверяем позицию
                            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                            val firstVisiblePosition = layoutManager?.findFirstVisibleItemPosition() ?: 0

                            // Если мы в самом верху списка, показываем навигацию
                            if (firstVisiblePosition == 0 && isNavigationHidden) {
                                val now = System.currentTimeMillis()
                                if (now - lastNavigationTime >= NAVIGATION_COOLDOWN) {
                                    showNavigationSmoothly()
                                    lastNavigationTime = now
                                }
                            }

                            accumulatedDy = 0
                        }
                    }
                }
            })
        }
    }

    /**
     * Плавное скрытие навигации с защитой от повторных вызовов
     */
    private fun hideNavigationSmoothly() {
        if (isNavigationHidden || isProcessingNavigation) return

        isProcessingNavigation = true
        Log.d(TAG, "hideNavigationSmoothly: Плавное скрытие навигации")
        isNavigationHidden = true

        // 1. Сначала анимируем FAB
        safeUpdateUI { binding ->
            binding.fabAddNews.animate()
                .translationY(binding.fabAddNews.height.toFloat() + 100f)
                .setDuration(300)
                .setInterpolator(AccelerateInterpolator())
                .withLayer()
                .withEndAction {
                    isProcessingNavigation = false
                }
                .start()
        }

        // 2. С небольшой задержкой скрываем основную навигацию
        Handler(Looper.getMainLooper()).postDelayed({
            // Уведомляем слушателей о скрытии всей навигации
            navigationListener?.onHideFullNavigation()

            // Также скрываем через родительский Fragment
            (parentFragment as? HomeFragment)?.hideNavigationInParent()

            Log.d(TAG, "hideNavigationSmoothly: Навигация скрыта")
        }, 50)
    }

    /**
     * Плавное отображение навигации с защитой от повторных вызовов
     */
    private fun showNavigationSmoothly() {
        if (!isNavigationHidden || isProcessingNavigation) return

        isProcessingNavigation = true
        Log.d(TAG, "showNavigationSmoothly: Плавное отображение навигации")
        isNavigationHidden = false

        // 1. Уведомляем слушателей о показе всей навигации
        navigationListener?.onShowFullNavigation()

        // 2. Показываем через родительский Fragment
        (parentFragment as? HomeFragment)?.showNavigationInParent()

        // 3. Показываем FAB с небольшой задержкой
        Handler(Looper.getMainLooper()).postDelayed({
            safeUpdateUI { binding ->
                binding.fabAddNews.animate()
                    .translationY(0f)
                    .setDuration(300)
                    .setInterpolator(DecelerateInterpolator())
                    .withLayer()
                    .withEndAction {
                        isProcessingNavigation = false
                    }
                    .start()
            }

            Log.d(TAG, "showNavigationSmoothly: Навигация показана")
        }, 100)
    }

    /**
     * Показ навигации с задержкой при возвращении на фрагмент
     */
    private fun showNavigationWithDelay() {
        // Если навигация скрыта, показываем ее с задержкой при возвращении
        if (isNavigationHidden) {
            Handler(Looper.getMainLooper()).postDelayed({
                showNavigationSmoothly()
                isNavigationHidden = false
                resetScrollState()
            }, 300)
        }
    }

    /**
     * Сброс состояния скролла
     */
    private fun resetScrollState() {
        lastScrollY = 0
        totalScrolled = 0
        isScrollingUp = false
        isScrollingDown = false
        lastScrollDirection = 0
    }

    /**
     * Сброс состояния всей навигации
     */
    private fun resetNavigationState() {
        isNavigationHidden = false
        resetScrollState()
        showNavigationSmoothly()
    }

    /**
     * Определяет, нужно ли загружать данные с нуля
     */
    private fun shouldReloadFromScratch(): Boolean {
        return isFirstLoad ||
                lastLoadTime == 0L ||
                System.currentTimeMillis() - lastLoadTime > FORCE_RELOAD_DURATION ||
                !::newsAdapter.isInitialized ||
                newsAdapter.itemCount == 0
    }

    /**
     * Загрузка с использованием кэшированных данных + фоновая синхронизация
     */
    private fun loadCachedNewsWithBackgroundRefresh() {
        safeUpdateUI { binding ->
            binding.progressBar.visibility = View.GONE
        }

        if (::newsAdapter.isInitialized && newsAdapter.itemCount > 0) {
            Log.d(TAG, "loadCachedNewsWithBackgroundRefresh: Using existing ${newsAdapter.itemCount} items")
            safeUpdateUI { binding ->
                updateUIState(false)
            }
            lifecycleScope.launch {
                refreshNewsQuietly()
            }
        } else {
            safeUpdateUI { binding ->
                binding.progressBar.visibility = View.VISIBLE
            }
            loadNewsOptimized()
        }
    }

    /**
     * Перезагружает данные только если прошло достаточно времени
     */
    private suspend fun reloadIfNeeded() {
        val timeSinceLastLoad = System.currentTimeMillis() - lastLoadTime
        if (timeSinceLastLoad > CACHE_DURATION) {
            Log.d(TAG, "reloadIfNeeded: Cache expired, reloading (${timeSinceLastLoad/1000} sec old)")
            refreshNewsWithProgress()
        } else {
            Log.d(TAG, "reloadIfNeeded: Cache still fresh (${timeSinceLastLoad/1000} sec old)")
            withContext(Dispatchers.Main) {
                showMessage("Новости актуальны")
            }
        }
    }

    private fun setupRecyclerView() {
        newsAdapter = NewsAdapter(
            onNewsClick = { newsItem ->
                handleNewsClick(newsItem)
            },
            onNewsLongClick = { newsItem ->
                handleNewsLongClick(newsItem)
            }
        )

        safeUpdateUI { binding ->
            binding.rvNews.apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = newsAdapter
                setHasFixedSize(true)
            }
        }
    }

    /**
     * Оптимизированная загрузка новостей - сначала локальные, потом внешние
     */
    private fun loadNewsOptimized() {
        lifecycleScope.launch {
            try {
                // Шаг 1: Быстрая загрузка локальных новостей
                loadLocalNewsFast()

                // Шаг 2: Фоновая загрузка внешних новостей с задержкой
                delay(LOADING_DELAY)
                loadExternalNewsInBackground()

                lastLoadTime = System.currentTimeMillis()
                currentNewsCount = newsAdapter.itemCount

            } catch (e: Exception) {
                Log.e(TAG, "loadNewsOptimized: Error", e)
                withContext(Dispatchers.Main) {
                    safeUpdateUI { binding ->
                        updateUIState(true)
                    }
                    showMessage("Ошибка загрузки новостей")
                }
            }
        }
    }

    private suspend fun loadLocalNewsFast() {
        try {
            newsRepository.getNewsFlow().collectLatest { news ->
                safeUpdateUI { binding ->
                    if (newsAdapter.itemCount == 0 || shouldUpdateAdapter(news)) {
                        newsAdapter.submitList(news.toMutableList()) {
                            updateUIState(news.isEmpty())
                            binding.progressBar.visibility = View.GONE
                            currentNewsCount = news.size
                            Log.d(TAG, "loadLocalNewsFast: Loaded ${news.size} local items")
                        }
                    } else {
                        binding.progressBar.visibility = View.GONE
                        Log.d(TAG, "loadLocalNewsFast: Using cached data, ${newsAdapter.itemCount} items")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadLocalNewsFast: Error", e)
            safeUpdateUI { binding ->
                binding.progressBar.visibility = View.GONE
                updateUIState(true)
            }
        }
    }

    /**
     * Проверяет, нужно ли обновлять адаптер новыми данными
     */
    private fun shouldUpdateAdapter(newNews: List<NewsItem>): Boolean {
        if (newsAdapter.itemCount != newNews.size) return true

        val currentIds = newsAdapter.currentList.map { it.id }.toSet()
        val newIds = newNews.map { it.id }.toSet()

        return currentIds != newIds || System.currentTimeMillis() - lastLoadTime > CACHE_DURATION
    }

    private suspend fun loadExternalNewsInBackground() {
        if (isLoading) return

        isLoading = true
        try {
            Log.d(TAG, "loadExternalNewsInBackground: Starting background fetch")

            val newNews = withContext(Dispatchers.IO) {
                newsFetcher.fetchExternalNews()
            }

            safeUpdateUI { binding ->
                if (newNews.isNotEmpty()) {
                    newsAdapter.addNewsToTop(newNews)
                    lastLoadTime = System.currentTimeMillis()
                    currentNewsCount = newsAdapter.itemCount
                    Log.d(TAG, "loadExternalNewsInBackground: Added ${newNews.size} new items, total: $currentNewsCount")

                    if (isFirstLoad) {
                        showMessage("Добавлено ${newNews.size} новых новостей")
                    }
                } else {
                    Log.d(TAG, "loadExternalNewsInBackground: No new news")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadExternalNewsInBackground: Error", e)
        } finally {
            isLoading = false
        }
    }

    private suspend fun refreshNewsQuietly() {
        if (isLoading) return

        try {
            Log.d(TAG, "refreshNewsQuietly: Silent refresh")

            val newNews = withContext(Dispatchers.IO) {
                newsFetcher.fetchExternalNews()
            }

            safeUpdateUI { binding ->
                if (newNews.isNotEmpty()) {
                    newsAdapter.addNewsToTop(newNews)
                    lastLoadTime = System.currentTimeMillis()
                    currentNewsCount = newsAdapter.itemCount
                    Log.d(TAG, "refreshNewsQuietly: Added ${newNews.size} items silently, total: $currentNewsCount")
                } else {
                    Log.d(TAG, "refreshNewsQuietly: No new news in silent refresh")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshNewsQuietly: Error", e)
        }
    }

    /**
     * Скроллит к началу списка
     */
    private fun scrollToTop() {
        safeUpdateUI { binding ->
            if (::newsAdapter.isInitialized && newsAdapter.itemCount > 0) {
                binding.rvNews.smoothScrollToPosition(0)
                Log.d(TAG, "scrollToTop: Scrolled to top")
            }
        }
    }


    /**
     * БЕЗОПАСНЫЙ МЕТОД ОБНОВЛЕНИЯ UI
     */
    private fun updateUIState(isEmpty: Boolean) {
        if (_binding == null || !isAdded || isDetached || activity == null || view == null) {
            Log.w(TAG, "updateUIState: Fragment not ready, skipping UI update")
            return
        }

        try {
            with(binding) {
                if (isEmpty) {
                    rvNews.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                    tvHint.visibility = View.VISIBLE
                } else {
                    rvNews.visibility = View.VISIBLE
                    tvEmpty.visibility = View.GONE
                    tvHint.visibility = View.GONE
                }
                progressBar.visibility = View.GONE
                progressBarBottom.visibility = View.GONE
                swipeRefreshLayout.isRefreshing = false
            }
            Log.d(TAG, "updateUIState: UI updated successfully, isEmpty: $isEmpty")
        } catch (e: Exception) {
            Log.e(TAG, "updateUIState: Error updating UI", e)
        }
    }

    /**
     * БЕЗОПАСНЫЙ МЕТОД ДЛЯ АСИНХРОННЫХ ОБНОВЛЕНИЙ UI
     */
    private fun safeUpdateUI(updateAction: (FragmentFeedBinding) -> Unit) {
        view?.post {
            if (_binding != null && isAdded && !isDetached && activity != null && view != null) {
                try {
                    updateAction(binding)
                } catch (e: Exception) {
                    Log.e(TAG, "safeUpdateUI: Error in update action", e)
                }
            } else {
                Log.w(TAG, "safeUpdateUI: Fragment not ready for UI update")
            }
        }
    }

    private fun setupSwipeRefresh() {
        safeUpdateUI { binding ->
            binding.swipeRefreshLayout.setColorSchemeColors(
                ContextCompat.getColor(requireContext(), R.color.red),
                ContextCompat.getColor(requireContext(), R.color.colorSurface),
                ContextCompat.getColor(requireContext(), R.color.yellow_background)
            )

            binding.swipeRefreshLayout.setOnRefreshListener {
                Log.d(TAG, "SwipeRefresh: Pull to refresh triggered")
                refreshNews()
            }
        }
    }

    private fun handleNewsClick(newsItem: NewsItem) {
        if (newsItem.imageUrl != null && !newsItem.isExternal) {
            (activity as? MainActivity)?.openFullScreenImage(newsItem.imageUrl)
        } else if (newsItem.isExternal && newsItem.externalLink != null) {
            openExternalLink(newsItem.externalLink)
        } else if (!newsItem.isExternal) {
            showNewsDetails(newsItem)
        }
    }

    private fun openExternalLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open external link: $url", e)
            showMessage("Не удалось открыть ссылку")
        }
    }

    private fun showNewsDetails(newsItem: NewsItem) {
        val message = if (newsItem.isExternal) {
            "Новость от ${newsItem.source ?: "неизвестного источника"}"
        } else {
            "Новость от ${newsItem.authorName ?: "неизвестного автора"}"
        }
        showMessage(message)
    }

    private fun handleNewsLongClick(newsItem: NewsItem): Boolean {
        if (!newsItem.isExternal && newsItem.id.startsWith("user_")) {
            showDeleteConfirmation(newsItem)
            return true
        } else if (newsItem.isExternal) {
            showMessage("Внешняя новость от ${newsItem.source ?: "Lenta.ru"}")
            return true
        }
        return false
    }

    private fun showDeleteConfirmation(newsItem: NewsItem) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Удаление новости")
            .setMessage("Вы уверены, что хотите удалить эту новость?")
            .setPositiveButton("Удалить") { dialog, _ ->
                lifecycleScope.launch {
                    deleteNewsAsync(newsItem.id)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Отмена") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private suspend fun deleteNewsAsync(newsId: String) {
        try {
            withContext(Dispatchers.IO) {
                newsRepository.deleteNews(newsId)
            }
            withContext(Dispatchers.Main) {
                showMessage("Новость удалена")
                currentNewsCount = newsAdapter.itemCount
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteNewsAsync: Error", e)
            withContext(Dispatchers.Main) {
                showMessage("Ошибка удаления новости")
            }
        }
    }

    private fun setupFab() {
        safeUpdateUI { binding ->
            binding.fabAddNews.setOnClickListener {
                (activity as? MainActivity)?.openCreateNewsFragment()
            }

            binding.fabAddNews.setOnLongClickListener {
                scrollToTop()
                showMessage("Прокрутка к началу")
                true
            }

            binding.fabAddNews.show()
        }
    }

    private fun fetchExternalNews() {
        if (isLoading) return

        fetchJob?.cancel()
        isLoading = true

        fetchJob = lifecycleScope.launch {
            try {
                Log.d(TAG, "fetchExternalNews: Starting...")

                safeUpdateUI { binding ->
                    binding.progressBar.visibility = View.VISIBLE
                    binding.tvHint.visibility = View.GONE
                }

                val newNews = withContext(Dispatchers.IO) {
                    newsFetcher.fetchExternalNews()
                }

                safeUpdateUI { binding ->
                    if (newNews.isNotEmpty()) {
                        Log.d(TAG, "fetchExternalNews: Received ${newNews.size} new news items")

                        if (::newsAdapter.isInitialized) {
                            newsAdapter.addNewsToTop(newNews)
                            currentNewsCount = newsAdapter.itemCount
                        }
                        showMessage("Добавлено ${newNews.size} новых новостей")
                    } else {
                        Log.d(TAG, "fetchExternalNews: No new news available")
                        showMessage("Новых новостей пока нет")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "fetchExternalNews: Error", e)
                safeUpdateUI { binding ->
                    showMessage("Ошибка загрузки новостей: ${e.message}")
                }
            } finally {
                safeUpdateUI { binding ->
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefreshLayout.isRefreshing = false
                    binding.progressBarBottom.visibility = View.GONE

                    if (newsAdapter.itemCount == 0) {
                        binding.tvHint.visibility = View.VISIBLE
                    }
                }
                isLoading = false
            }
        }
    }

    /**
     * БЕЗОПАСНЫЙ МЕТОД ПОКАЗА СООБЩЕНИЙ
     */
    private fun showMessage(message: String) {
        if (isAdded && !isRemoving && !isDetached && activity != null) {
            try {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "showMessage: Error showing toast", e)
            }
        } else {
            Log.w(TAG, "showMessage: Fragment not ready, message: $message")
        }
    }

    fun refreshNews() {
        hasFetchedExternalNewsOnThisResume = false
        resetNavigationState() // <-- Сбрасываем состояние навигации при обновлении

        lifecycleScope.launch {
            refreshNewsWithProgress()
        }

        safeUpdateUI { binding ->
            binding.swipeRefreshLayout.postDelayed({
                if (binding.swipeRefreshLayout.isRefreshing) {
                    binding.swipeRefreshLayout.isRefreshing = false
                }
            }, 2000)
        }
    }






    /**
     * Прокручивает список новостей к последней новости
     */
    fun scrollNewsToBottom() {
        try {
            if (!isAdded || !isVisible) {
                return
            }

            lifecycleScope.launch(Dispatchers.Main) {
                delay(100) // Небольшая задержка для стабилизации layout

                binding?.let { binding ->
                    val adapter = binding.rvNews.adapter as? NewsAdapter
                    val itemCount = adapter?.itemCount ?: 0

                    if (itemCount > 0) {
                        binding.rvNews.scrollToPosition(itemCount - 1) // Прокрутка к последней новости

                        // Опционально: плавная анимация прокрутки
                        val layoutManager = binding.rvNews.layoutManager as? LinearLayoutManager
                        layoutManager?.smoothScrollToPosition(binding.rvNews, null, itemCount - 1)

                        Log.d(TAG, "Прокрутка к последней новости, позиция: ${itemCount - 1}")
                    } else {
                        Log.d(TAG, "Нет новостей для прокрутки")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при прокрутке к последней новости", e)
        }
    }

    /**
     * Прокручивает список новостей к первой новости
     */
    fun scrollNewsToTop() {
        try {
            if (!isAdded || !isVisible) {
                return
            }

            lifecycleScope.launch(Dispatchers.Main) {
                delay(100) // Небольшая задержка для стабилизации layout

                binding?.let { binding ->
                    val itemCount = (binding.rvNews.adapter as? NewsAdapter)?.itemCount ?: 0

                    if (itemCount > 0) {
                        binding.rvNews.scrollToPosition(0) // Прокрутка к первой новости

                        // Опционально: плавная анимация прокрутки
                        val layoutManager = binding.rvNews.layoutManager as? LinearLayoutManager
                        layoutManager?.smoothScrollToPosition(binding.rvNews, null, 0)

                        Log.d(TAG, "Прокрутка к первой новости")
                    } else {
                        Log.d(TAG, "Нет новостей для прокрутки")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при прокрутке к первой новости", e)
        }
    }

    // Переименуйте существующий метод для ясности
    fun scrollToTopIfNeeded() {
        scrollNewsToTop()
    }






    private suspend fun refreshNewsWithProgress() {
        try {
            safeUpdateUI { binding ->
                binding.progressBar.visibility = View.VISIBLE
            }

            val newNews = withContext(Dispatchers.IO) {
                newsFetcher.fetchExternalNews()
            }

            safeUpdateUI { binding ->
                if (newNews.isNotEmpty()) {
                    newsAdapter.addNewsToTop(newNews)
                    lastLoadTime = System.currentTimeMillis()
                    currentNewsCount = newsAdapter.itemCount
                    showMessage("Обновлено ${newNews.size} новостей")
                } else {
                    showMessage("Новых новостей нет")
                }
                binding.progressBar.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshNewsWithProgress: Error", e)
            safeUpdateUI { binding ->
                binding.progressBar.visibility = View.GONE
                showMessage("Ошибка обновления")
            }
        }
    }

    /**
     * Внешний метод для принудительной перезагрузки при возвращении на фрагмент
     */
    fun requestReloadOnNextResume() {
        shouldReloadOnResume = true
        Log.d(TAG, "requestReloadOnNextResume: Reload scheduled for next resume")
    }

    /**
     * Получает информацию о кэше для отладки
     */
    fun getCacheInfo(): String {
        return """
            FeedFragment Cache Info:
            ----------------------
            Last load: ${if (lastLoadTime == 0L) "Never" else "${(System.currentTimeMillis() - lastLoadTime) / 1000} sec ago"}
            Items in adapter: ${if (::newsAdapter.isInitialized) newsAdapter.itemCount else "N/A"}
            Current news count: $currentNewsCount
            Is loading: $isLoading
            Should reload on resume: $shouldReloadOnResume
            Fragment visible: $isFragmentVisible
            Is first load: $isFirstLoad
            Has fetched this session: $hasFetchedExternalNewsOnThisResume
            Navigation hidden: $isNavigationHidden
            Last scroll position: $lastScrollY
        """.trimIndent()
    }

    fun getNewsFetcherStats(): String {
        return if (::newsFetcher.isInitialized) {
            newsFetcher.getStats()
        } else {
            "NewsFetcher not initialized"
        }
    }


    fun forceClearKnownLinks() {
        if (::newsFetcher.isInitialized) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    newsFetcher.clearAllKnownLinks()
                }
                withContext(Dispatchers.Main) {
                    showMessage("Известные ссылки очищены")
                    refreshNews()
                }
            }
        }
    }

    fun getDebugInfo(): String {
        return """
            NewsAdapter items: ${if (::newsAdapter.isInitialized) newsAdapter.itemCount else "N/A"}
            NewsFetcher known links: ${if (::newsFetcher.isInitialized) newsFetcher.getKnownLinksCount() else "N/A"}
            Has fetched this session: $hasFetchedExternalNewsOnThisResume
            Fetch job active: ${fetchJob?.isActive == true}
            Is first load: $isFirstLoad
            Is loading: $isLoading
            Last load time: ${if (lastLoadTime == 0L) "Never" else "${(System.currentTimeMillis() - lastLoadTime) / 1000}s ago"}
            Navigation hidden: $isNavigationHidden
            Scroll position: $lastScrollY
        """.trimIndent()
    }

    fun addTestNews() {
        lifecycleScope.launch {
            val testNews = NewsItem(
                id = "test_${System.currentTimeMillis()}",
                content = "Тестовая новость - ${System.currentTimeMillis()}",
                timestamp = System.currentTimeMillis(),
                authorId = "test_user",
                authorName = "Тестовый пользователь",
                authorAvatarUrl = null,
                imageUrl = null,
                isExternal = false
            )

            try {
                withContext(Dispatchers.IO) {
                    newsRepository.addNews(testNews)
                }
                withContext(Dispatchers.Main) {
                    showMessage("Тестовая новость добавлена")
                    currentNewsCount = newsAdapter.itemCount
                }
            } catch (e: Exception) {
                Log.e(TAG, "addTestNews: Error", e)
                withContext(Dispatchers.Main) {
                    showMessage("Ошибка добавления тестовой новости")
                }
            }
        }
    }

    /**
     * Установка слушателя для управления навигацией
     */
    fun setNavigationVisibilityListener(listener: OnNavigationVisibilityChangeListener) {
        this.navigationListener = listener
    }
}