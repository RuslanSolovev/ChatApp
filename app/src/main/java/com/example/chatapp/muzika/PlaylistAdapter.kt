package com.example.chatapp.muzika.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.chatapp.R
import com.example.chatapp.muzika.Playlist

class PlaylistAdapter(
    private var playlists: List<Playlist>,
    private val onItemClick: (Playlist) -> Unit,
    private val onDeleteClick: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.playlistName)
        private val descriptionView: TextView = itemView.findViewById(R.id.playlistDescription)
        private val coverArt: ImageView = itemView.findViewById(R.id.coverArt)
        private val trackCount: TextView = itemView.findViewById(R.id.trackCount)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        fun bind(playlist: Playlist) {
            nameView.text = playlist.name
            descriptionView.text = playlist.description ?: ""
            trackCount.text = "${playlist.tracks.size} треков"

            playlist.coverArtUrl?.let { url ->
                Glide.with(itemView.context)
                    .load(url)
                    .placeholder(R.drawable.ic_playlist)
                    .error(R.drawable.ic_playlist)
                    .into(coverArt)
            } ?: run {
                coverArt.setImageResource(R.drawable.ic_playlist)
            }

            itemView.setOnClickListener { onItemClick(playlist) }
            btnDelete.setOnClickListener { onDeleteClick(playlist) }

            itemView.setOnLongClickListener {
                btnDelete.visibility = if (btnDelete.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(playlists[position])
    }

    override fun getItemCount() = playlists.size

    fun updatePlaylists(newPlaylists: List<Playlist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }
}