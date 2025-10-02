package com.example.chatapp.novosti

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.getValue
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class NewsRepository {
    private val database = FirebaseDatabase.getInstance()
    private val newsRef = database.getReference("news")
    private val MAX_NEWS_COUNT = 100

    fun addNews(news: NewsItem) {
        newsRef.child(news.id).setValue(news.toMap())
            .addOnSuccessListener {
                enforceMaxLimit()
            }
            .addOnFailureListener { e ->
                e.printStackTrace()
            }
    }

    fun getNewsFlow(): Flow<List<NewsItem>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
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
                        e.printStackTrace()
                    }
                }

                // Сортируем по времени и убираем дубликаты по ID
                val distinctNews = newsList
                    .sortedByDescending { it.timestamp }
                    .distinctBy { it.id }

                trySend(distinctNews)
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }

        newsRef.addValueEventListener(listener)
        awaitClose { newsRef.removeEventListener(listener) }
    }

    private fun isDuplicateContent(existingList: List<NewsItem>, newItem: NewsItem): Boolean {
        return existingList.any { existing ->
            existing.content == newItem.content &&
                    existing.timestamp == newItem.timestamp &&
                    existing.isExternal == newItem.isExternal
        }
    }

    suspend fun getAllExternalLinks(): Set<String> {
        return try {
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
                    e.printStackTrace()
                }
            }
            links
        } catch (e: Exception) {
            e.printStackTrace()
            emptySet()
        }
    }

    fun deleteNews(newsId: String) {
        newsRef.child(newsId).removeValue()
    }

    fun updateNews(news: NewsItem) {
        newsRef.child(news.id).setValue(news.toMap())
    }

    private fun enforceMaxLimit() {
        newsRef.orderByChild("timestamp").addListenerForSingleValueEvent(
            object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
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
                                e.printStackTrace()
                            }
                        }

                        val toRemove = newsWithKeys
                            .sortedBy { (_, news) -> news.timestamp }
                            .take((snapshot.childrenCount - MAX_NEWS_COUNT).toInt())

                        toRemove.forEach { (key, _) ->
                            newsRef.child(key).removeValue()
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    error.toException().printStackTrace()
                }
            }
        )
    }
}