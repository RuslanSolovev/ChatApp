package com.example.chatapp.novosti

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class NewsFetcher(
    private val newsRepository: NewsRepository,
    private val context: Context
) {
    companion object {
        private const val TAG = "NewsFetcher"
        private const val RSS_URL = "https://lenta.ru/rss/news "
        private const val LENTA_LOGO_URL = "https://lenta.ru/favicon.ico "
        private const val EXTERNAL_NEWS_COLOR = "#FFFFFF"
        private const val TIMEOUT_SECONDS = 15L
        private const val MAX_NEWS_TO_PARSE = 50

        private const val MAX_KNOWN_LINKS = 200
        private const val CLEANUP_INTERVAL_DAYS = 7
        private const val PREFS_NAME = "news_fetcher_prefs"
        private const val KEY_KNOWN_LINKS = "known_external_links"
        private const val KEY_LAST_CLEANUP = "last_cleanup_time"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val knownLinksWithTime = mutableMapOf<String, Long>()

    init {
        loadKnownLinksFromPrefs()
        cleanupOldLinksIfNeeded()
    }

    private fun loadKnownLinksFromPrefs() {
        try {
            val storedLinksJson = prefs.getString(KEY_KNOWN_LINKS, "{}")
            val storedMap = try {
                val jsonObject = org.json.JSONObject(storedLinksJson ?: "{}")
                val map = mutableMapOf<String, Long>()
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = jsonObject.getLong(key)
                }
                map
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse stored links from prefs, starting fresh", e)
                mutableMapOf()
            }
            knownLinksWithTime.putAll(storedMap)
            Log.d(TAG, "Loaded ${knownLinksWithTime.size} known links from prefs")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading known links", e)
        }
    }

    private fun cleanupOldLinksIfNeeded() {
        try {
            val lastCleanup = prefs.getLong(KEY_LAST_CLEANUP, 0)
            val currentTime = System.currentTimeMillis()
            val cleanupInterval = CLEANUP_INTERVAL_DAYS * 24 * 60 * 60 * 1000L

            if (currentTime - lastCleanup > cleanupInterval) {
                Log.d(TAG, "Performing periodic cleanup of known links")
                cleanupOldLinks()
                prefs.edit().putLong(KEY_LAST_CLEANUP, currentTime).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in cleanup check", e)
        }
    }

    private fun cleanupOldLinks() {
        try {
            val currentTime = System.currentTimeMillis()
            val cutoffTime = currentTime - (CLEANUP_INTERVAL_DAYS * 24 * 60 * 60 * 1000L)

            val iterator = knownLinksWithTime.iterator()
            var removedCount = 0
            while (iterator.hasNext()) {
                val (link, timestamp) = iterator.next()
                if (timestamp < cutoffTime) {
                    iterator.remove()
                    removedCount++
                }
            }

            if (knownLinksWithTime.size > MAX_KNOWN_LINKS) {
                val linksToRemove = knownLinksWithTime.entries
                    .sortedBy { it.value }
                    .take(knownLinksWithTime.size - MAX_KNOWN_LINKS)

                linksToRemove.forEach { knownLinksWithTime.remove(it.key) }
                removedCount += linksToRemove.size
                Log.d(TAG, "Removed ${linksToRemove.size} oldest links due to size limit")
            }

            if (removedCount > 0) {
                saveKnownLinksToPrefs()
                Log.d(TAG, "Cleanup completed: removed $removedCount old links")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during links cleanup", e)
        }
    }

    private fun saveKnownLinksToPrefs() {
        try {
            val jsonObject = org.json.JSONObject()
            knownLinksWithTime.forEach { (link, timestamp) ->
                jsonObject.put(link, timestamp)
            }
            prefs.edit().putString(KEY_KNOWN_LINKS, jsonObject.toString()).apply()
            Log.d(TAG, "Saved ${knownLinksWithTime.size} known links to prefs")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save known links to prefs", e)
        }
    }

    suspend fun fetchExternalNews(): List<NewsItem> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "fetchExternalNews: Starting async fetch process...")
                Log.d(TAG, "fetchExternalNews: Currently known links count: ${knownLinksWithTime.size}")

                val request = Request.Builder().url(RSS_URL).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "fetchExternalNews: HTTP error: ${response.code} - ${response.message}")
                    return@withContext emptyList()
                }

                val parsedNews = response.body?.byteStream()?.use { stream ->
                    parseRssStream(stream)
                } ?: run {
                    Log.w(TAG, "fetchExternalNews: Response body stream is null")
                    emptyList()
                }

                Log.d(TAG, "fetchExternalNews: Parsed ${parsedNews.size} news items from RSS")

                processAndSaveNews(parsedNews)

            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "fetchExternalNews: Network timeout occurred", e)
                emptyList()
            } catch (e: IOException) {
                Log.e(TAG, "fetchExternalNews: Network IO error", e)
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "fetchExternalNews: Unexpected error during fetch", e)
                emptyList()
            }
        }
    }

    private suspend fun processAndSaveNews(parsedNews: List<NewsItem>): List<NewsItem> {
        val currentTime = System.currentTimeMillis()
        val newNewsToSave = mutableListOf<NewsItem>()
        var newLinksAdded = 0

        for (newsItem in parsedNews) {
            if (newsItem.externalLink != null) {
                val normalizedLink = newsItem.externalLink.trim().lowercase()

                val isDuplicate = knownLinksWithTime.any { (link, _) ->
                    link.trim().lowercase() == normalizedLink
                }

                if (!isDuplicate) {
                    val styledItem = newsItem.copy(
                        source = "Lenta.ru",
                        sourceLogoUrl = LENTA_LOGO_URL,
                        backgroundColor = EXTERNAL_NEWS_COLOR,
                        id = "ext_${System.currentTimeMillis()}_${newsItem.externalLink.hashCode()}"
                    )
                    newNewsToSave.add(styledItem)
                    knownLinksWithTime[newsItem.externalLink] = currentTime
                    newLinksAdded++
                    Log.d(TAG, "fetchExternalNews: NEW news: ${styledItem.content.take(50)}...")
                } else {
                    knownLinksWithTime[newsItem.externalLink] = currentTime
                    Log.d(TAG, "fetchExternalNews: DUPLICATE (updating timestamp): ${newsItem.content.take(50)}...")
                }
            }
        }

        Log.d(TAG, "fetchExternalNews: Identified ${newNewsToSave.size} new news items to save")
        Log.d(TAG, "fetchExternalNews: Added $newLinksAdded new links to known links")

        if (newNewsToSave.isNotEmpty()) {
            // Асинхронное сохранение всех новостей
            saveNewsToRepository(newNewsToSave)

            // Сохраняем обновлённый список известных ссылок
            saveKnownLinksToPrefs()

            if (newLinksAdded > 10) {
                cleanupOldLinks()
            }

            return newNewsToSave
        } else {
            Log.d(TAG, "fetchExternalNews: No new unique news items to add")
            saveKnownLinksToPrefs()
            return emptyList()
        }
    }

    private suspend fun saveNewsToRepository(newsList: List<NewsItem>) {
        try {
            newsList.forEach { news ->
                try {
                    newsRepository.addNews(news)
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving news item: ${news.id}", e)
                }
            }
            Log.d(TAG, "saveNewsToRepository: Successfully added ${newsList.size} news items to repository")
        } catch (e: Exception) {
            Log.e(TAG, "saveNewsToRepository: Error saving news batch", e)
        }
    }

    private fun parseRssStream(inputStream: InputStream): List<NewsItem> {
        Log.d(TAG, "parseRssStream: Starting to parse RSS stream...")
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(inputStream, null)

        val parsedNews = mutableListOf<NewsItem>()
        var eventType = parser.eventType
        var currentItem: NewsItem? = null
        var currentImageUrl: String? = null

        try {
            while (eventType != XmlPullParser.END_DOCUMENT && parsedNews.size < MAX_NEWS_TO_PARSE) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "item" -> {
                            currentItem = NewsItem(
                                id = "",
                                content = "",
                                timestamp = System.currentTimeMillis(),
                                isExternal = true,
                                source = "Lenta.ru",
                                sourceLogoUrl = LENTA_LOGO_URL,
                                backgroundColor = EXTERNAL_NEWS_COLOR
                            )
                            currentImageUrl = null
                        }
                        "title" -> {
                            currentItem = currentItem?.copy(content = parser.nextText().trim())
                        }
                        "link" -> {
                            currentItem = currentItem?.copy(externalLink = parser.nextText().trim())
                        }
                        "pubDate" -> {
                            val dateStr = parser.nextText().trim()
                            try {
                                val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(dateStr)
                                currentItem = currentItem?.copy(timestamp = date?.time ?: System.currentTimeMillis())
                            } catch (e: Exception) {
                                Log.w(TAG, "parseRssStream: Error parsing date '$dateStr'", e)
                            }
                        }
                        "description" -> {
                            val description = parser.nextText().trim()
                            currentItem = currentItem?.copy(summary = description)
                            if (currentImageUrl == null) {
                                val imgRegex = """<img[^>]*src=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
                                val matchResult = imgRegex.find(description)
                                matchResult?.groups?.get(1)?.value?.let { imgUrl ->
                                    Log.d(TAG, "parseRssStream: Found image URL in description: $imgUrl")
                                    currentImageUrl = imgUrl.trim()
                                }
                            }
                        }
                        "enclosure" -> {
                            val type = parser.getAttributeValue(null, "type")
                            if (type?.startsWith("image/", ignoreCase = true) == true) {
                                val url = parser.getAttributeValue(null, "url")?.trim()
                                if (url != null && url.isNotBlank()) {
                                    Log.d(TAG, "parseRssStream: Found image URL in enclosure: $url")
                                    currentImageUrl = url
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item" && currentItem != null) {
                            val finalItem = currentItem.copy(
                                id = "ext_${System.currentTimeMillis()}_${currentItem.externalLink.hashCode()}",
                                imageUrl = currentImageUrl?.takeIf { it.isNotBlank() }
                            )
                            parsedNews.add(finalItem)
                            Log.d(TAG, "parseRssStream: Added news item: ${finalItem.content.take(50)}... | Link: ${finalItem.externalLink}")
                            currentItem = null
                            currentImageUrl = null
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseRssStream: Error occurred during parsing", e)
        }
        Log.d(TAG, "parseRssStream: Finished parsing. Total items parsed: ${parsedNews.size}")
        return parsedNews
    }

    fun forceCleanup() {
        cleanupOldLinks()
    }

    fun getStats(): String {
        val oldest = knownLinksWithTime.values.minOrNull()
        val newest = knownLinksWithTime.values.maxOrNull()

        return "Known links: ${knownLinksWithTime.size}\n" +
                "Oldest: ${oldest?.let { Date(it) } ?: "N/A"}\n" +
                "Newest: ${newest?.let { Date(it) } ?: "N/A"}"
    }

    fun getKnownLinksCount(): Int {
        return knownLinksWithTime.size
    }

    fun clearAllKnownLinks() {
        knownLinksWithTime.clear()
        prefs.edit().remove(KEY_KNOWN_LINKS).apply()
        Log.d(TAG, "Cleared all known links")
    }
}