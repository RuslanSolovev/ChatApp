package com.example.chatapp.muzika.api

import com.example.chatapp.muzika.Track
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface ArchiveSearchService {
    @GET("advancedsearch.php")
    suspend fun searchAudio(
        @Query("q") query: String,
        @Query("fl") fields: String = "identifier,title,creator",
        @Query("rows") limit: Int = 20,
        @Query("output") output: String = "json",
        @Query("sort") sort: String = "downloads desc"
    ): ArchiveSearchResponse
}

interface ArchiveMetadataService {
    @GET("metadata/{identifier}")
    suspend fun getMetadata(@Path("identifier") identifier: String): ArchiveMetadataResponse
}

data class ArchiveSearchResponse(val response: ResponseDocs)
data class ResponseDocs(val docs: List<AudioItem>)
data class AudioItem(
    val identifier: String,
    val title: String?,
    val creator: String?
)

data class ArchiveMetadataResponse(val metadata: Metadata, val files: List<ArchiveFile>?)
data class Metadata(val title: String?, val creator: String?)
data class ArchiveFile(
    val name: String?,
    val source: String?,
    val format: String?
)

object ArchiveApi {
    private const val BASE_URL = "https://archive.org/"
    private const val CONNECTION_TIMEOUT = 15L
    private const val READ_TIMEOUT = 15L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECTION_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .build()

    private val searchApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ArchiveSearchService::class.java)

    private val metadataApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ArchiveMetadataService::class.java)

    suspend fun searchMusic(query: String): List<Track> {
        return try {
            val fullQuery = "mediatype:audio AND ($query)"
            val response = searchApi.searchAudio(
                query = fullQuery,
                fields = "identifier,title,creator",
                limit = 20,
                output = "json"
            )

            response.response.docs.mapNotNull { item ->
                try {
                    val metadataResponse = metadataApi.getMetadata(item.identifier)
                    val audioFile = metadataResponse.files?.find { file ->
                        file.format?.contains("MP3", ignoreCase = true) == true ||
                                file.format?.contains("FLAC", ignoreCase = true) == true ||
                                file.format?.contains("Ogg", ignoreCase = true) == true
                    }

                    if (audioFile?.name != null) {
                        val streamUrl = "https://archive.org/download/${item.identifier}/${audioFile.name}"
                        Track(
                            id = item.identifier,
                            title = metadataResponse.metadata.title ?: item.title ?: "Unknown Track",
                            creator = metadataResponse.metadata.creator ?: item.creator ?: "Unknown Artist",
                            streamUrl = streamUrl,
                            duration = 0
                        )
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}