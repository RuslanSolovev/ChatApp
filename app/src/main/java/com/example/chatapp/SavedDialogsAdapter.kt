package com.example.chatapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.databinding.ItemSavedDialogBinding
import java.text.SimpleDateFormat
import java.util.*

class SavedDialogsAdapter(
    private val onDialogSelected: (SavedDialog) -> Unit,
    private val onDialogDeleted: (SavedDialog) -> Unit
) : RecyclerView.Adapter<SavedDialogsAdapter.DialogViewHolder>() {




    private val dialogs = mutableListOf<SavedDialog>()
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    fun updateDialogs(newDialogs: List<SavedDialog>) {
        dialogs.clear()
        dialogs.addAll(newDialogs)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DialogViewHolder {
        val binding = ItemSavedDialogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DialogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DialogViewHolder, position: Int) {
        holder.bind(dialogs[position])
    }

    override fun getItemCount(): Int = dialogs.size

    inner class DialogViewHolder(private val binding: ItemSavedDialogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(dialog: SavedDialog) {
            binding.apply {
                textDialogTitle.text = dialog.title
                textDialogDate.text = dateFormat.format(Date(dialog.timestamp))
                root.setOnClickListener { onDialogSelected(dialog) }
                buttonDeleteDialog.setOnClickListener { onDialogDeleted(dialog) }
            }
        }
    }
}