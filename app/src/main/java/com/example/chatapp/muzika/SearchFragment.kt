package com.example.chatapp.muzika.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatapp.databinding.FragmentSearchBinding
import com.example.chatapp.muzika.MusicViewModel
import com.example.chatapp.muzika.Track
import com.example.chatapp.muzika.adapters.TrackAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SearchFragment : Fragment() {
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MusicViewModel by viewModels(ownerProducer = { requireActivity() })
    private lateinit var trackAdapter: TrackAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Очищаем треки при создании
        viewModel.clearTracks()

        setupRecyclerView()
        setupListeners()
        observeData()
    }

    private fun setupRecyclerView() {
        trackAdapter = TrackAdapter(
            emptyList(),
            onItemClick = { track ->
                (activity as? MusicMainActivity)?.playTrack(track)
            },
            onAddToPlaylist = { track ->
                showAddToPlaylistDialog(track)
            },
            onDeleteClick = null // В поиске не показываем кнопку удаления
        )

        binding.tracksRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = trackAdapter
        }
    }

    private fun setupListeners() {
        binding.searchButton.setOnClickListener {
            val query = binding.searchEditText.text.toString().trim()
            if (query.isNotEmpty()) {
                viewModel.searchMusic(query)
            } else {
                Toast.makeText(context, "Please enter search query", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeData() {
        viewModel.tracks.observe(viewLifecycleOwner) { tracks ->
            trackAdapter.updateTracks(tracks)
            binding.searchInputLayout.visibility = View.VISIBLE
            binding.searchButton.visibility = View.VISIBLE
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.searchButton.isEnabled = !isLoading
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAddToPlaylistDialog(track: Track) {
        val playlists = viewModel.playlists.value ?: emptyList()
        val playlistNames = playlists.map { it.name }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add to Playlist")
            .setItems(playlistNames) { _, which ->
                val playlistId = playlists[which].id
                viewModel.addToPlaylist(playlistId, track)
                Toast.makeText(
                    context,
                    "Added to ${playlists[which].name}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}