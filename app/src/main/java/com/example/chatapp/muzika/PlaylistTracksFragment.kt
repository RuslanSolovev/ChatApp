package com.example.chatapp.muzika.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatapp.R
import com.example.chatapp.databinding.FragmentPlaylistTracksBinding
import com.example.chatapp.muzika.MusicViewModel
import com.example.chatapp.muzika.Track
import com.example.chatapp.muzika.adapters.TrackAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class PlaylistTracksFragment : Fragment() {
    private var _binding: FragmentPlaylistTracksBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MusicViewModel by viewModels(ownerProducer = { requireActivity() })
    private lateinit var trackAdapter: TrackAdapter

    companion object {
        fun newInstance() = PlaylistTracksFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistTracksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        setupRecyclerView()
        setupObservers()
    }

    private fun setupRecyclerView() {
        trackAdapter = TrackAdapter(
            emptyList(),
            onItemClick = { track ->
                (activity as? MusicMainActivity)?.playTrack(track)
            },
            onAddToPlaylist = { },
            onDeleteClick = { track ->
                viewModel.currentPlaylist.value?.id?.let { playlistId ->
                    showDeleteTrackDialog(playlistId, track.id)
                }
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = trackAdapter
        }
    }

    private fun showDeleteTrackDialog(playlistId: String, trackId: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_track_title))
            .setMessage(getString(R.string.delete_track_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                viewModel.removeFromPlaylist(playlistId, trackId)
                Snackbar.make(binding.root, getString(R.string.track_deleted), Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun setupObservers() {
        viewModel.currentPlaylist.observe(viewLifecycleOwner) { playlist ->
            playlist?.let {
                binding.toolbar.title = it.name
                trackAdapter.updateTracks(it.tracks)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}