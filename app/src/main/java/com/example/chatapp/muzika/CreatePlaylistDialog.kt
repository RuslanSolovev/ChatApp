package com.example.chatapp.muzika

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.example.chatapp.databinding.DialogCreatePlaylistBinding

class CreatePlaylistDialog : DialogFragment() {
    private lateinit var binding: DialogCreatePlaylistBinding
    var onCreate: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DialogCreatePlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.createButton.setOnClickListener {
            val name = binding.playlistName.text.toString().trim()
            if (name.isNotEmpty()) {
                onCreate?.invoke(name)
                dismiss()
            } else {
                binding.playlistName.error = "Enter playlist name"
            }
        }

        binding.cancelButton.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}