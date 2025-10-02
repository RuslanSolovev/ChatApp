package com.example.chatapp.utils

import java.text.SimpleDateFormat
import java.util.*

object Utils {
    fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60 * 1000 -> "только что"
            diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)} мин назад"
            diff < 24 * 60 * 60 * 1000 -> {
                val format = SimpleDateFormat("HH:mm", Locale.getDefault())
                format.format(Date(timestamp))
            }
            else -> {
                val format = SimpleDateFormat("dd.MM.yy", Locale.getDefault())
                format.format(Date(timestamp))
            }
        }
    }
}