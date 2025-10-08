package com.example.chatapp.muzika.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatapp.R
import com.example.chatapp.databinding.FragmentPlaylistBinding
import com.example.chatapp.muzika.MusicViewModel
import com.example.chatapp.muzika.Playlist
import com.example.chatapp.muzika.adapters.PlaylistAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class PlaylistFragment : Fragment() {
    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MusicViewModel by viewModels(ownerProducer = { requireActivity() })
    private lateinit var playlistAdapter: PlaylistAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupObservers()
        setupListeners()
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            emptyList(),
            onItemClick = { playlist ->
                viewModel.setCurrentPlaylist(playlist)
                (activity as? MusicMainActivity)?.showPlaylistTracksFragment()
            },
            onDeleteClick = { playlist ->
                showDeletePlaylistDialog(playlist)
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = playlistAdapter
            setHasFixedSize(true)
        }
    }

    private fun showDeletePlaylistDialog(playlist: Playlist) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_playlist_title))
            .setMessage(getString(R.string.delete_playlist_message, playlist.name))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                viewModel.deletePlaylist(playlist.id)
                Snackbar.make(binding.root, getString(R.string.playlist_deleted), Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun setupObservers() {
        viewModel.playlists.observe(viewLifecycleOwner) { playlists ->
            if (playlists != null) {
                playlistAdapter.updatePlaylists(playlists)
                binding.emptyState.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupListeners() {
        binding.fab.setOnClickListener {
            (activity as? MusicMainActivity)?.showCreatePlaylistDialog()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}