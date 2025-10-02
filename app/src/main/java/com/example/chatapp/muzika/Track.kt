package com.example.chatapp.muzika

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Track(
    val id: String,
    val title: String,
    val creator: String,
    val streamUrl: String,
    var duration: Long,
    val coverArtUrl: String? = null
) : Parcelable