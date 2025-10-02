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

class FeedFragment : Fragment() {

    private var _binding: FragmentFeedBinding? = null
    private val binding get() = _binding!!
    private lateinit var newsAdapter: NewsAdapter
    private val newsRepository = NewsRepository()
    private lateinit var newsFetcher: NewsFetcher

    private var hasFetchedExternalNewsOnThisResume = false
    private var fetchJob: Job? = null
    private var isFirstLoad = true // Флаг первого открытия

    companion object {
        private const val TAG = "FeedFragment"
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
        loadNews()
        setupFab()

        binding.progressBar.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: called")

        if (!hasFetchedExternalNewsOnThisResume) {
            Log.d(TAG, "onResume: Initiating fetchExternalNews")
            fetchExternalNews()
            hasFetchedExternalNewsOnThisResume = true
        } else {
            Log.d(TAG, "onResume: External news already fetched for this session.")
            binding.progressBar.visibility = View.GONE

            // Если это не первая загрузка, просто скроллим к началу
            if (!isFirstLoad) {
                scrollToTop()
            }
        }

        isFirstLoad = false
    }

    override fun onPause() {
        super.onPause()
        fetchJob?.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fetchJob?.cancel()
        _binding = null
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

            // ПРОСТЕЙШАЯ логика скролла - только FAB
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    // ТОЛЬКО FAB логика - больше ничего
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
        if (isEmpty) {
            binding.rvNews.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvHint.visibility = View.VISIBLE
        } else {
            binding.rvNews.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.GONE
            binding.tvHint.visibility = View.GONE
        }
        binding.progressBar.visibility = View.GONE
        binding.progressBarBottom.visibility = View.GONE
        binding.swipeRefreshLayout.isRefreshing = false
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
                newsRepository.deleteNews(newsItem.id)
                showMessage("Новость удалена")
                dialog.dismiss()
            }
            .setNegativeButton("Отмена") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun loadNews() {
        lifecycleScope.launch {
            newsRepository.getNewsFlow().collectLatest { news ->
                newsAdapter.submitList(news.toMutableList()) {
                    updateUIState(news.isEmpty())
                    Log.d(TAG, "loadNews: Submitted list with ${news.size} items")
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun setupFab() {
        binding.fabAddNews.setOnClickListener {
            (activity as? MainActivity)?.openCreateNewsFragment()
        }

        // Добавляем скролл к началу по клику на FAB
        binding.fabAddNews.setOnLongClickListener {
            scrollToTop()
            showMessage("Прокрутка к началу")
            true
        }

        binding.fabAddNews.show()
    }

    private fun fetchExternalNews() {
        fetchJob?.cancel()

        fetchJob = lifecycleScope.launch {
            try {
                Log.d(TAG, "fetchExternalNews: Starting...")

                binding.progressBar.visibility = View.VISIBLE
                binding.tvHint.visibility = View.GONE

                val newNews = newsFetcher.fetchExternalNews()

                if (newNews.isNotEmpty()) {
                    Log.d(TAG, "fetchExternalNews: Received ${newNews.size} new news items")

                    withContext(Dispatchers.Main) {
                        if (::newsAdapter.isInitialized) {
                            newsAdapter.addNewsToTop(newNews)
                        }
                        showMessage("Добавлено ${newNews.size} новых новостей")
                    }
                } else {
                    Log.d(TAG, "fetchExternalNews: No new news available")
                    withContext(Dispatchers.Main) {
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
            }
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    fun refreshNews() {
        hasFetchedExternalNewsOnThisResume = false
        fetchExternalNews()

        binding.swipeRefreshLayout.postDelayed({
            if (binding.swipeRefreshLayout.isRefreshing) {
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }, 2000)
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
            newsFetcher.clearAllKnownLinks()
            showMessage("Известные ссылки очищены")
            refreshNews()
        }
    }

    fun getDebugInfo(): String {
        return """
            NewsAdapter items: ${if (::newsAdapter.isInitialized) newsAdapter.itemCount else "N/A"}
            NewsFetcher known links: ${if (::newsFetcher.isInitialized) newsFetcher.getKnownLinksCount() else "N/A"}
            Has fetched this session: $hasFetchedExternalNewsOnThisResume
            Fetch job active: ${fetchJob?.isActive == true}
            Is first load: $isFirstLoad
        """.trimIndent()
    }

    fun addTestNews() {
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
        newsAdapter.addNewsItem(testNews)
        showMessage("Тестовая новость добавлена")
    }
}