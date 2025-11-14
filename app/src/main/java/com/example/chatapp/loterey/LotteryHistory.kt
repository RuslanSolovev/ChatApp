package com.example.chatapp.loterey

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class LotteryHistory(
    val id: String = "",
    val lotteryId: String = "",
    val winnerId: String = "",
    val winnerName: String = "",
    val winnerEmail: String = "",
    val prizeAmount: Double = 0.0,
    val drawTime: Long = System.currentTimeMillis(),
    val ticketCount: Int = 0,
    val totalParticipants: Int = 0
)