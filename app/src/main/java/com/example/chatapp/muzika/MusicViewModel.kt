package com.example.chatapp.muzika

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.chatapp.muzika.api.ArchiveApi
import com.example.chatapp.muzika.utils.PlaylistStorage
import kotlinx.coroutines.launch
import java.util.UUID

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    val tracks = MutableLiveData<List<Track>>(emptyList())
    val playlists = MutableLiveData<List<Playlist>?>(emptyList())
    val currentPlaylist = MutableLiveData<Playlist?>(null)
    val currentPlaylistTracks = MutableLiveData<List<Track>>(emptyList())
    val isLoading = MutableLiveData(false)
    val errorMessage = MutableLiveData<String?>(null)

    init {
        loadSavedPlaylists()
        if (playlists.value.isNullOrEmpty()) {

        }
    }

    private fun loadSavedPlaylists() {
        playlists.value = PlaylistStorage.loadPlaylists(getApplication())
    }

    private fun savePlaylists() {
        playlists.value?.let {
            PlaylistStorage.savePlaylists(getApplication(), it)
        }
    }

    fun searchMusic(query: String) {
        if (query.isEmpty()) {
            errorMessage.value = "Please enter search query"
            return
        }

        val processedQuery = query.trim().replace("\"", "")
        isLoading.value = true
        errorMessage.value = null

        viewModelScope.launch {
            try {
                val result = ArchiveApi.searchMusic(processedQuery)
                tracks.value = result.map { it.copy(coverArtUrl = getCoverArtUrl(it.id)) }

                if (result.isEmpty()) {
                    errorMessage.value = "No tracks found. Try: 'The Beatles', 'Jazz' or 'collection:etree'"
                }
            } catch (e: Exception) {
                errorMessage.value = "Error: ${e.localizedMessage ?: "Unknown error"}"
                tracks.value = emptyList()
            } finally {
                isLoading.value = false
            }
        }
    }

    fun createNewPlaylist(name: String, description: String? = null) {
        val newPlaylist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            tracks = mutableListOf()
        )
        playlists.value = playlists.value.orEmpty() + newPlaylist
        savePlaylists()
    }

    fun deletePlaylist(playlistId: String) {
        val updatedPlaylists = playlists.value?.filterNot { it.id == playlistId }
        playlists.value = updatedPlaylists
        savePlaylists()

        if (currentPlaylist.value?.id == playlistId) {
            clearCurrentPlaylist()
        }
    }

    fun addToPlaylist(playlistId: String, track: Track) {
        val updatedPlaylists = playlists.value?.map { playlist ->
            if (playlist.id == playlistId) {
                if (!playlist.tracks.any { it.id == track.id }) {
                    playlist.copy(tracks = (playlist.tracks + track).toMutableList())
                } else {
                    playlist
                }
            } else {
                playlist
            }
        }
        playlists.value = updatedPlaylists
        savePlaylists()

        if (currentPlaylist.value?.id == playlistId) {
            currentPlaylist.value = updatedPlaylists?.find { it.id == playlistId }
            currentPlaylistTracks.value = currentPlaylist.value?.tracks ?: emptyList()
        }
    }

    fun removeFromPlaylist(playlistId: String, trackId: String) {
        val updatedPlaylists = playlists.value?.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(tracks = playlist.tracks.filterNot { it.id == trackId }.toMutableList())
            } else {
                playlist
            }
        }
        playlists.value = updatedPlaylists
        savePlaylists()

        if (currentPlaylist.value?.id == playlistId) {
            currentPlaylist.value = updatedPlaylists?.find { it.id == playlistId }
            currentPlaylistTracks.value = currentPlaylist.value?.tracks ?: emptyList()
        }
    }

    fun setCurrentPlaylist(playlist: Playlist) {
        currentPlaylist.value = playlist
        currentPlaylistTracks.value = playlist.tracks
    }

    fun clearTracks() {
        tracks.value = emptyList()
        currentPlaylistTracks.value = emptyList()
    }

    fun clearCurrentPlaylist() {
        currentPlaylist.value = null
        currentPlaylistTracks.value = emptyList()
    }

    private fun getCoverArtUrl(identifier: String): String? {
        return "https://archive.org/services/img/$identifier"
    }


}