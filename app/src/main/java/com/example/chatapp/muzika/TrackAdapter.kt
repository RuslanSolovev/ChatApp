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
import com.example.chatapp.muzika.Track

class TrackAdapter(
    private var tracks: List<Track>,
    private val onItemClick: (Track) -> Unit,
    private val onAddToPlaylist: (Track) -> Unit,
    private val onDeleteClick: ((Track) -> Unit)? = null // Добавляем новый параметр
) : RecyclerView.Adapter<TrackAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.trackTitle)
        private val artistView: TextView = itemView.findViewById(R.id.trackArtist)
        private val coverArt: ImageView = itemView.findViewById(R.id.coverArt)
        private val addButton: ImageButton = itemView.findViewById(R.id.btnAddToPlaylist)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.btnDeleteTrack) // Добавляем кнопку удаления

        fun bind(track: Track) {
            titleView.text = track.title
            artistView.text = track.creator

            track.coverArtUrl?.let { url ->
                Glide.with(itemView.context)
                    .load(url)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .into(coverArt)
            } ?: run {
                coverArt.setImageResource(R.drawable.ic_music_note)
            }

            itemView.setOnClickListener { onItemClick(track) }
            addButton.setOnClickListener { onAddToPlaylist(track) }

            // Настройка видимости и обработчика для кнопки удаления
            deleteButton.visibility = if (onDeleteClick != null) View.VISIBLE else View.GONE
            deleteButton.setOnClickListener { onDeleteClick?.invoke(track) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(tracks[position])
    }

    override fun getItemCount() = tracks.size

    fun updateTracks(newTracks: List<Track>) {
        tracks = newTracks
        notifyDataSetChanged()
    }
}