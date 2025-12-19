package com.example.chatapp.zametki.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.databinding.ItemNoteBinding
import com.example.chatapp.zametki.data.Note

class NotesAdapter : RecyclerView.Adapter<NotesAdapter.NoteViewHolder>() {

    private var notes = mutableListOf<Note>()
    private var onItemClickListener: ((Note) -> Unit)? = null
    private var onDeleteClickListener: ((Note) -> Unit)? = null

    val currentList: List<Note>
        get() = notes

    fun setOnItemClickListener(listener: (Note) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnDeleteClickListener(listener: (Note) -> Unit) {
        onDeleteClickListener = listener
    }

    inner class NoteViewHolder(private val binding: ItemNoteBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(note: Note) {
            binding.apply {
                tvTitle.text = note.title
                tvDescription.text = note.description
                tvDate.text = note.date

                root.setOnClickListener {
                    onItemClickListener?.invoke(note)
                }

                root.setOnLongClickListener {
                    onDeleteClickListener?.invoke(note)
                    true
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val binding = ItemNoteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return NoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(notes[position])
    }

    override fun getItemCount(): Int = notes.size

    fun submitList(newNotes: List<Note>) {
        notes.clear()
        notes.addAll(newNotes)
        notifyDataSetChanged()
    }
}