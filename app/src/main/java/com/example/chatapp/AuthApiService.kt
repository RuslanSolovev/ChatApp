package com.example.chatapp.api

import retrofit2.Call
import retrofit2.http.*

data class AuthRequest(
    val scope: String = "GIGACHAT_API_PERS"
)

data class AuthResponse(
    val access_token: String,
    val token_type: String,
    val expires_in: Int
)

interface AuthApiService {
    @FormUrlEncoded
    @POST("api/v2/oauth")
    fun getAuthToken(
        @Header("RqUID") rqUid: String,
        @Header("Authorization") authHeader: String,
        @Field("scope") scope: String
    ): Call<AuthResponse>
}