package com.example.chatapp.muzika.ui

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.example.chatapp.R
import com.example.chatapp.databinding.ActivityMusicMainBinding
import com.example.chatapp.muzika.CreatePlaylistDialog
import com.example.chatapp.muzika.MusicViewModel
import com.example.chatapp.muzika.Track
import com.google.android.material.snackbar.Snackbar

class MusicMainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMusicMainBinding
    private val viewModel: MusicViewModel by viewModels()
    private var mediaPlayer: MediaPlayer? = null
    private var currentTrack: Track? = null
    private var isPlaying: Boolean = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var updateSeekBar: Runnable

    private var isMediaPlayerPrepared: Boolean = false
        set(value) {
            field = value
            updatePlayButtonState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMusicMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        setupPlayerControls()
        setupObservers()

        if (savedInstanceState == null) {
            showMainTabsFragment()
        }
    }

    fun showMainTabsFragment() {
        supportFragmentManager.commit {
            replace(R.id.container, MainTabsFragment.newInstance())
            setReorderingAllowed(true)
        }
    }

    fun showPlaylistTracksFragment() {
        supportFragmentManager.commit {
            replace(R.id.container, PlaylistTracksFragment.newInstance())
            addToBackStack("playlist_tracks")
            setReorderingAllowed(true)
        }
    }

    fun showCreatePlaylistDialog() {
        val dialog = CreatePlaylistDialog().apply {
            onCreate = { name ->
                viewModel.createNewPlaylist(name)
                Snackbar.make(binding.root, "Плейлист '$name' создан", Snackbar.LENGTH_SHORT).show()
            }
        }
        dialog.show(supportFragmentManager, "CreatePlaylistDialog")
    }

    private fun setupPlayerControls() {
        updateSeekBar = object : Runnable {
            override fun run() {
                mediaPlayer?.let { mp ->
                    if (mp.duration > 0) {
                        val progress = (mp.currentPosition.toFloat() / mp.duration * 100).toInt()
                        binding.seekBar.progress = progress
                    }
                    handler.postDelayed(this, 1000)
                }
            }
        }

        binding.seekBar.max = 100
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.let {
                        val newPosition = (progress * it.duration) / 100
                        it.seekTo(newPosition)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnPlayPause.setOnClickListener {
            if (mediaPlayer != null && isMediaPlayerPrepared) {
                if (isPlaying) {
                    pauseTrack()
                } else {
                    resumeTrack()
                }
            } else if (currentTrack != null && !isMediaPlayerPrepared) {
                Snackbar.make(binding.root, "Трек загружается...", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, "Трек не выбран", Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.btnStop.setOnClickListener {
            stopTrack()
        }
    }

    private fun setupObservers() {
        viewModel.currentPlaylist.observe(this) { playlist ->
            supportActionBar?.title = playlist?.name ?: getString(R.string.app_name)
        }
    }

    fun playTrack(track: Track) {
        if (track.streamUrl.isBlank()) {
            Snackbar.make(binding.root, "Невозможно воспроизвести трек", Snackbar.LENGTH_SHORT).show()
            return
        }
        stopTrack()

        try {
            isMediaPlayerPrepared = false
            updatePlayButtonState()

            currentTrack = track
            updatePlayerUI()
            showPlayerControls()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(track.streamUrl)
                setOnPreparedListener {
                    isMediaPlayerPrepared = true
                    handler.post(updateSeekBar)
                    start()
                    this@MusicMainActivity.isPlaying = true
                    updatePlayerUI()
                }
                setOnErrorListener { mp, what, extra ->
                    if (this@MusicMainActivity.mediaPlayer == mp) {
                        isMediaPlayerPrepared = false
                        updatePlayButtonState()
                        handler.removeCallbacks(updateSeekBar)
                    }
                    Snackbar.make(
                        binding.root,
                        "Ошибка воспроизведения",
                        Snackbar.LENGTH_SHORT
                    ).show()
                    stopTrack()
                    true
                }
                setOnCompletionListener {
                    stopTrack()
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            isMediaPlayerPrepared = false
            updatePlayButtonState()
            Snackbar.make(binding.root, "Ошибка загрузки трека", Snackbar.LENGTH_SHORT).show()
            stopTrack()
        }
    }

    private fun showPlayerControls() {
        binding.playerControls.visibility = View.VISIBLE
    }

    private fun hidePlayerControls() {
        binding.playerControls.visibility = View.GONE
    }

    private fun resumeTrack() {
        mediaPlayer?.let { mp ->
            if (!mp.isPlaying) {
                mp.start()
                isPlaying = true
                updatePlayerUI()
                handler.post(updateSeekBar)
            }
        } ?: run {
            currentTrack?.let {
                playTrack(it)
            } ?: Snackbar.make(binding.root, "Нет трека для воспроизведения", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun pauseTrack() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.pause()
                isPlaying = false
                updatePlayerUI()
                handler.removeCallbacks(updateSeekBar)
            }
        }
    }

    private fun stopTrack() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        isMediaPlayerPrepared = false
        currentTrack = null
        updatePlayerUI()
        binding.seekBar.progress = 0
        handler.removeCallbacks(updateSeekBar)
        hidePlayerControls()
    }

    private fun updatePlayButtonState() {
        val isEnabled = mediaPlayer != null && isMediaPlayerPrepared
        binding.btnPlayPause.isEnabled = isEnabled

        val iconRes = if (isPlaying) {
            R.drawable.ic_pause
        } else {
            R.drawable.ic_play
        }
        binding.btnPlayPause.setImageResource(iconRes)
    }

    private fun updatePlayerUI() {
        binding.currentTrackInfo.text = currentTrack?.let { "${it.title} - ${it.creator}" }
            ?: getString(R.string.not_playing)
        updatePlayButtonState()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateSeekBar)
        mediaPlayer?.release()
        mediaPlayer = null
        isMediaPlayerPrepared = false
    }
}