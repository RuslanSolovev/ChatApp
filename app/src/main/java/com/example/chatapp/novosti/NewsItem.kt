package com.example.chatapp.novosti

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
data class NewsItem(
    val id: String = "",
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val authorId: String? = null,
    val authorName: String? = null,
    val authorAvatarUrl: String? = null,
    val imageUrl: String? = null,
    val isExternal: Boolean = false,
    val externalLink: String? = null,
    val source: String? = null,
    val sourceLogoUrl: String? = null,
    val backgroundColor: String? = null,
    val summary: String? = null
) : Parcelable {

    fun toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "content" to content,
            "timestamp" to timestamp,
            "authorId" to authorId,
            "authorName" to authorName,
            "authorAvatarUrl" to authorAvatarUrl,
            "imageUrl" to imageUrl,
            "isExternal" to isExternal,
            "externalLink" to externalLink,
            "source" to source,
            "sourceLogoUrl" to sourceLogoUrl,
            "backgroundColor" to backgroundColor,
            "summary" to summary
        )
    }

    companion object {
        fun fromMap(map: Map<String, Any>): NewsItem {
            return NewsItem(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                content = map["content"] as? String ?: "",
                timestamp = (map["timestamp"] as? Long) ?: System.currentTimeMillis(),
                authorId = map["authorId"] as? String,
                authorName = map["authorName"] as? String,
                authorAvatarUrl = map["authorAvatarUrl"] as? String,
                imageUrl = map["imageUrl"] as? String,
                isExternal = map["isExternal"] as? Boolean ?: false,
                externalLink = map["externalLink"] as? String,
                source = map["source"] as? String,
                sourceLogoUrl = map["sourceLogoUrl"] as? String,
                backgroundColor = map["backgroundColor"] as? String,
                summary = map["summary"] as? String
            )
        }
    }
}