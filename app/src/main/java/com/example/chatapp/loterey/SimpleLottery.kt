package com.example.chatapp.loterey

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class SimpleLottery(
    val id: String = "",
    val currentPrize: Double = 0.0,
    val ticketPrice: Double = 100.0,
    val endTime: Long = System.currentTimeMillis() + 24 * 60 * 60 * 1000,
    val status: String = "ACTIVE",
    val winnerId: String? = null,
    val winnerName: String? = null,
    val prizeAmount: Double = 0.0
)

@IgnoreExtraProperties
data class LotteryTicket(
    val id: String = "",
    val userId: String = "",
    val userName: String = "",
    val userEmail: String = "",
    val purchaseTime: Long = System.currentTimeMillis(),
    val lotteryId: String = "",
    val isWinner: Boolean = false
)

@IgnoreExtraProperties
data class ManualPayment(
    val id: String = "",
    val userId: String = "",
    val userName: String = "",
    val userEmail: String = "",
    val amount: Double = 0.0,
    val status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis(),
    val confirmedAt: Long = 0,
    val ticketsAdded: Int = 0
)