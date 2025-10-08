package com.example.chatapp.muzika.utils

import android.content.Context
import com.example.chatapp.muzika.Playlist
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object PlaylistStorage {
    private const val PREFS_NAME = "music_player_prefs"
    private const val PLAYLISTS_KEY = "playlists"

    fun savePlaylists(context: Context, playlists: List<Playlist>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = Gson().toJson(playlists)
        prefs.edit().putString(PLAYLISTS_KEY, json).apply()
    }

    fun loadPlaylists(context: Context): List<Playlist> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(PLAYLISTS_KEY, null)
        return if (json != null) {
            val type = object : TypeToken<List<Playlist>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } else {
            emptyList()
        }
    }
}