package com.example.chatapp.muzika

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Playlist(
    val id: String,
    val name: String,
    val description: String? = null,
    val tracks: MutableList<Track> = mutableListOf(),
    val coverArtUrl: String? = null
) : Parcelable