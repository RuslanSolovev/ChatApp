package com.example.chatapp.novosti

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NewsRepository {
    private val database = FirebaseDatabase.getInstance()
    private val newsRef = database.getReference("news")
    private val MAX_NEWS_COUNT = 100

    suspend fun addNews(news: NewsItem) {
        try {
            withContext(Dispatchers.IO) {
                newsRef.child(news.id).setValue(news.toMap()).await()
            }
            // Запускаем ограничение в фоне без блокировки
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    enforceMaxLimitAsync()
                } catch (e: Exception) {
                    Log.e("NewsRepository", "Error in background limit enforcement", e)
                }
            }
        } catch (e: Exception) {
            Log.e("NewsRepository", "Error adding news", e)
            throw e
        }
    }

    fun getNewsFlow(): Flow<List<NewsItem>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val newsList = mutableListOf<NewsItem>()
                    val seenIds = mutableSetOf<String>()

                    snapshot.children.forEach { child ->
                        try {
                            val newsMap = child.getValue<Map<String, Any>>()
                            newsMap?.let {
                                val newsItem = NewsItem.fromMap(it)
                                // Проверяем на дубликаты по ID и содержанию
                                if (!seenIds.contains(newsItem.id) &&
                                    !isDuplicateContent(newsList, newsItem)) {
                                    seenIds.add(newsItem.id)
                                    newsList.add(newsItem)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("NewsRepository", "Error parsing news item", e)
                        }
                    }

                    // Сортируем по времени и убираем дубликаты по ID
                    val distinctNews = newsList
                        .sortedByDescending { it.timestamp }
                        .distinctBy { it.id }

                    trySend(distinctNews)
                    Log.d("NewsRepository", "Sent ${distinctNews.size} news items to flow")
                } catch (e: Exception) {
                    Log.e("NewsRepository", "Error processing data change", e)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("NewsRepository", "Database error", error.toException())
                close(error.toException())
            }
        }

        newsRef.addValueEventListener(listener)
        awaitClose {
            try {
                newsRef.removeEventListener(listener)
            } catch (e: Exception) {
                Log.w("NewsRepository", "Error removing listener", e)
            }
        }
    }

    private fun isDuplicateContent(existingList: List<NewsItem>, newItem: NewsItem): Boolean {
        return existingList.any { existing ->
            existing.content == newItem.content &&
                    existing.timestamp == newItem.timestamp &&
                    existing.isExternal == newItem.isExternal
        }
    }

    suspend fun getAllExternalLinks(): Set<String> {
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = newsRef.get().await()
                val links = mutableSetOf<String>()
                snapshot.children.forEach { child ->
                    try {
                        val newsMap = child.getValue<Map<String, Any>>()
                        if (newsMap != null && newsMap["isExternal"] as? Boolean == true) {
                            val link = newsMap["externalLink"] as? String
                            if (!link.isNullOrBlank()) {
                                links.add(link)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("NewsRepository", "Error parsing external link", e)
                    }
                }
                links
            } catch (e: Exception) {
                Log.e("NewsRepository", "Error getting external links", e)
                emptySet()
            }
        }
    }

    suspend fun deleteNews(newsId: String) {
        withContext(Dispatchers.IO) {
            try {
                newsRef.child(newsId).removeValue().await()
                Log.d("NewsRepository", "News $newsId deleted successfully")
            } catch (e: Exception) {
                Log.e("NewsRepository", "Error deleting news $newsId", e)
                throw e
            }
        }
    }

    suspend fun updateNews(news: NewsItem) {
        withContext(Dispatchers.IO) {
            try {
                newsRef.child(news.id).setValue(news.toMap()).await()
                Log.d("NewsRepository", "News ${news.id} updated successfully")
            } catch (e: Exception) {
                Log.e("NewsRepository", "Error updating news ${news.id}", e)
                throw e
            }
        }
    }

    suspend fun enforceMaxLimitAsync() {
        withContext(Dispatchers.IO) {
            try {
                val snapshot = newsRef.orderByChild("timestamp").get().await()
                if (snapshot.childrenCount > MAX_NEWS_COUNT) {
                    val newsWithKeys = mutableListOf<Pair<String, NewsItem>>()
                    snapshot.children.forEach { child ->
                        try {
                            val newsMap = child.getValue<Map<String, Any>>()
                            if (newsMap != null && child.key != null) {
                                val recreatedNews = NewsItem.fromMap(newsMap)
                                newsWithKeys.add(child.key!! to recreatedNews)
                            }
                        } catch (e: Exception) {
                            Log.w("NewsRepository", "Error processing news item for limit enforcement", e)
                        }
                    }

                    val toRemove = newsWithKeys
                        .sortedBy { (_, news) -> news.timestamp }
                        .take((snapshot.childrenCount - MAX_NEWS_COUNT).toInt())

                    // Удаляем в цикле асинхронно
                    toRemove.forEach { (key, _) ->
                        try {
                            newsRef.child(key).removeValue().await()
                        } catch (e: Exception) {
                            Log.w("NewsRepository", "Error removing old news $key", e)
                        }
                    }
                    Log.d("NewsRepository", "Enforced max limit, removed ${toRemove.size} old items")
                } else {

                }
            } catch (e: Exception) {
                Log.e("NewsRepository", "Error enforcing max limit", e)
            }
        }
    }

    suspend fun getNewsCount(): Long {
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = newsRef.get().await()
                snapshot.childrenCount
            } catch (e: Exception) {
                Log.e("NewsRepository", "Error getting news count", e)
                0
            }
        }
    }

    suspend fun clearAllNews() {
        withContext(Dispatchers.IO) {
            try {
                newsRef.removeValue().await()
                Log.d("NewsRepository", "All news cleared successfully")
            } catch (e: Exception) {
                Log.e("NewsRepository", "Error clearing all news", e)
                throw e
            }
        }
    }
}