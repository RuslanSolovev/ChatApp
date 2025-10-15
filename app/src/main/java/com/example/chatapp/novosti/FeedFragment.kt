package com.example.chatapp.novosti

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.activities.MainActivity
import com.example.chatapp.databinding.FragmentFeedBinding
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

    // НОВЫЕ ПЕРЕМЕННЫЕ ДЛЯ КЭШИРОВАНИЯ
    private var lastLoadTime: Long = 0
    private var isFragmentVisible = false
    private var shouldReloadOnResume = false
    private val CACHE_DURATION = 5 * 60 * 1000L // 5 минут кэширования
    private val FORCE_RELOAD_DURATION = 10 * 60 * 1000L // 10 минут для принудительной перезагрузки
    private var currentNewsCount = 0

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

        newsFetcher = NewsFetcher(newsRepository, requireContext())

        setupRecyclerView()
        setupSwipeRefresh()
        setupFab()

        // Проверяем, нужно ли загружать заново или использовать кэш
        if (shouldReloadFromScratch()) {
            Log.d(TAG, "Loading news from scratch")
            binding.progressBar.visibility = View.VISIBLE
            loadNewsOptimized()
        } else {
            Log.d(TAG, "Using cached data, only background refresh")
            // Данные уже есть в адаптере, просто тихое обновление
            loadCachedNewsWithBackgroundRefresh()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: called, isFragmentVisible: $isFragmentVisible, lastLoad: ${if (lastLoadTime == 0L) "never" else "${(System.currentTimeMillis() - lastLoadTime) / 1000} sec ago"}")

        isFragmentVisible = true

        if (!hasFetchedExternalNewsOnThisResume && !isFirstLoad) {
            Log.d(TAG, "onResume: Background refresh only")
            lifecycleScope.launch {
                refreshNewsQuietly()
            }
            hasFetchedExternalNewsOnThisResume = true
        } else {
            binding.progressBar.visibility = View.GONE
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fetchJob?.cancel()
        _binding = null
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
        binding.progressBar.visibility = View.GONE

        // Если в адаптере уже есть данные, просто запускаем фоновое обновление
        if (::newsAdapter.isInitialized && newsAdapter.itemCount > 0) {
            Log.d(TAG, "loadCachedNewsWithBackgroundRefresh: Using existing ${newsAdapter.itemCount} items")
            updateUIState(false)
            lifecycleScope.launch {
                refreshNewsQuietly()
            }
        } else {
            // Если данных нет, загружаем как обычно
            binding.progressBar.visibility = View.VISIBLE
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
            // Показываем сообщение, что данные актуальны
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

        binding.rvNews.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = newsAdapter
            setHasFixedSize(true)

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    if (dy > 10) {
                        binding.fabAddNews.hide()
                    } else if (dy < -10) {
                        binding.fabAddNews.show()
                    }
                }
            })
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
                    updateUIState(true)
                    showMessage("Ошибка загрузки новостей")
                }
            }
        }
    }

    private suspend fun loadLocalNewsFast() {
        try {
            newsRepository.getNewsFlow().collectLatest { news ->
                withContext(Dispatchers.Main) {
                    // Обновляем адаптер только если данных нет или они устарели
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
            withContext(Dispatchers.Main) {
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

        // Если количество совпадает, проверяем есть ли реально новые новости
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

            withContext(Dispatchers.Main) {
                if (newNews.isNotEmpty()) {
                    newsAdapter.addNewsToTop(newNews)
                    lastLoadTime = System.currentTimeMillis()
                    currentNewsCount = newsAdapter.itemCount
                    Log.d(TAG, "loadExternalNewsInBackground: Added ${newNews.size} new items, total: $currentNewsCount")

                    if (isFirstLoad) {
                        showMessage("Добавлено ${newNews.size} новых новостей")
                    } else {

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

            withContext(Dispatchers.Main) {
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
        if (::newsAdapter.isInitialized && newsAdapter.itemCount > 0) {
            binding.rvNews.smoothScrollToPosition(0)
            Log.d(TAG, "scrollToTop: Scrolled to top")
        }
    }

    fun scrollToTopIfNeeded() {
        try {
            if (isVisible && ::newsAdapter.isInitialized && newsAdapter.itemCount > 0) {
                val layoutManager = binding.rvNews.layoutManager as? LinearLayoutManager
                val firstVisiblePosition = layoutManager?.findFirstVisibleItemPosition() ?: 0

                if (firstVisiblePosition > 5) {
                    binding.rvNews.smoothScrollToPosition(0)
                    Log.d(TAG, "scrollToTopIfNeeded: Плавный скролл к началу с позиции $firstVisiblePosition")
                } else if (firstVisiblePosition > 0) {
                    binding.rvNews.smoothScrollToPosition(0)
                    Log.d(TAG, "scrollToTopIfNeeded: Быстрый скролл к началу с позиции $firstVisiblePosition")
                } else {
                    Log.d(TAG, "scrollToTopIfNeeded: Уже в начале списка")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in scrollToTopIfNeeded", e)
        }
    }

    private fun updateUIState(isEmpty: Boolean) {
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
    }

    private fun setupSwipeRefresh() {
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
                // Обновляем счетчик
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

    private fun fetchExternalNews() {
        if (isLoading) return

        fetchJob?.cancel()
        isLoading = true

        fetchJob = lifecycleScope.launch {
            try {
                Log.d(TAG, "fetchExternalNews: Starting...")

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.tvHint.visibility = View.GONE
                }

                val newNews = withContext(Dispatchers.IO) {
                    newsFetcher.fetchExternalNews()
                }

                withContext(Dispatchers.Main) {
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
                withContext(Dispatchers.Main) {
                    showMessage("Ошибка загрузки новостей: ${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) {
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

    private fun showMessage(message: String) {
        if (isAdded && !isRemoving) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshNews() {
        hasFetchedExternalNewsOnThisResume = false
        lifecycleScope.launch {
            refreshNewsWithProgress()
        }

        binding.swipeRefreshLayout.postDelayed({
            if (binding.swipeRefreshLayout.isRefreshing) {
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }, 2000)
    }

    private suspend fun refreshNewsWithProgress() {
        try {
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.VISIBLE
            }

            val newNews = withContext(Dispatchers.IO) {
                newsFetcher.fetchExternalNews()
            }

            withContext(Dispatchers.Main) {
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
            withContext(Dispatchers.Main) {
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
        """.trimIndent()
    }

    fun getNewsFetcherStats(): String {
        return if (::newsFetcher.isInitialized) {
            newsFetcher.getStats()
        } else {
            "NewsFetcher not initialized"
        }
    }

    fun clearImageCache() {
        if (::newsAdapter.isInitialized) {
            newsAdapter.clearGlideCache()
            showMessage("Кэш изображений очищен")
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
     * Метод для скролла к началу (вызывается из MainActivity)
     */
    fun scrollNewsToTop() {
        scrollToTopIfNeeded()
    }
}