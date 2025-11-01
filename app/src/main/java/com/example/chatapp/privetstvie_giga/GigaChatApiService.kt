package com.example.chatapp.api

import retrofit2.Call
import retrofit2.http.*

data class GigaChatRequest(
    val model: String,
    val messages: List<Message>,
    val max_tokens: Int
)

data class Message(
    val role: String,
    val content: String
)

data class GigaChatResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: Message
)

interface GigaChatApiService {
    @Headers("Accept: application/json")
    @POST("api/v1/chat/completions")
    fun sendMessage(
        @Header("Authorization") token: String,
        @Body request: GigaChatRequest
    ): Call<GigaChatResponse>
}