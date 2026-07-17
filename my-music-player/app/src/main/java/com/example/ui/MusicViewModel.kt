package com.example.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.database.entities.PlaylistEntity
import com.example.data.model.Song
import com.example.data.repository.MusicRepository
import com.example.services.MusicPlaybackService
import com.example.services.RepeatMode
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: MusicRepository
    private var playbackService: MusicPlaybackService? = null
    private var isServiceBound = false

    // --- Media Library States ---
    private val _allSongs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs: StateFlow<List<Song>> = _allSongs.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    val favorites: StateFlow<List<String>>
    val recentlyPlayed: StateFlow<List<Song>>
    val playlists: StateFlow<List<PlaylistEntity>>

    // --- Live Playback States (Delegated from Service) ---
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _shuffleMode = MutableStateFlow(false)
    val shuffleMode: StateFlow<Boolean> = _shuffleMode.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    // --- Active Playlist Song Details ---
    private val _activePlaylistSongs = MutableStateFlow<List<Song>>(emptyList())
    val activePlaylistSongs: StateFlow<List<Song>> = _activePlaylistSongs.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicPlaybackService.LocalServiceBinder
            playbackService = binder.getService()
            isServiceBound = true
            observeServiceState()
            Log.d("MusicViewModel", "Playback Service Bound Successfully")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            playbackService = null
            Log.d("MusicViewModel", "Playback Service Disconnected")
        }
    }

    init {
        // Init Database & Repo
        val database = AppDatabase.getDatabase(application)
        repository = MusicRepository(database.musicDao())

        // Setup reactive database flows
        favorites = repository.favoritesFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        recentlyPlayed = repository.recentlyPlayedFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        playlists = repository.playlistsFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Scan local audio files on startup
        scanMusic(application, force = false)

        // Bind the background playback service
        val intent = Intent(application, MusicPlaybackService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeServiceState() {
        val service = playbackService ?: return
        viewModelScope.launch {
            service.currentSong.collectLatest { song ->
                _currentSong.value = song
                // Add to recently played history whenever a song changes/starts
                song?.let { repository.addRecentlyPlayed(it.id) }
            }
        }
        viewModelScope.launch {
            service.isPlaying.collectLatest { playing ->
                _isPlaying.value = playing
            }
        }
        viewModelScope.launch {
            service.currentPosition.collectLatest { pos ->
                _currentPosition.value = pos
            }
        }
        viewModelScope.launch {
            service.shuffleMode.collectLatest { shuffle ->
                _shuffleMode.value = shuffle
            }
        }
        viewModelScope.launch {
            service.repeatMode.collectLatest { rMode ->
                _repeatMode.value = rMode
            }
        }
    }

    // --- Public Audio Library Actions ---

    fun scanMusic(context: Context, force: Boolean) {
        viewModelScope.launch {
            _isScanning.value = true
            val songsList = repository.scanLocalMusic(context, forceRefresh = force)
            _allSongs.value = songsList
            _isScanning.value = false
            Log.d("MusicViewModel", "Scanned ${songsList.size} songs")

            // Restore queue if service is active but queue is empty
            val service = playbackService
            if (service != null && service.getQueue().isEmpty() && songsList.isNotEmpty()) {
                val lastPlayedId = service.getLastPlayedSongId()
                val lastPlayedSong = songsList.find { it.id == lastPlayedId }
                val startIdx = if (lastPlayedSong != null) songsList.indexOf(lastPlayedSong) else 0
                service.setQueue(songsList, startIdx)
            }
        }
    }

    // --- Public Media Player Delegates ---

    fun playSong(queue: List<Song>, index: Int) {
        val service = playbackService ?: return
        serviceScopeLaunch {
            service.setQueue(queue, index)
            service.play()
        }
    }

    fun togglePlay() {
        playbackService?.togglePlay()
    }

    fun next() {
        playbackService?.next()
    }

    fun previous() {
        playbackService?.previous()
    }

    fun seekTo(positionMs: Long) {
        playbackService?.seekTo(positionMs)
    }

    fun toggleShuffle() {
        playbackService?.toggleShuffle()
    }

    fun cycleRepeatMode() {
        playbackService?.cycleRepeatMode()
    }

    fun getActiveQueue(): List<Song> {
        return playbackService?.getQueue() ?: emptyList()
    }

    // --- Favorites Actions ---

    fun toggleFavorite(songId: String) {
        viewModelScope.launch {
            if (favorites.value.contains(songId)) {
                repository.removeFavorite(songId)
            } else {
                repository.addFavorite(songId)
            }
        }
    }

    // --- History Actions ---

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    // --- Playlists Actions ---

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            repository.createPlaylist(name)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        viewModelScope.launch {
            repository.renamePlaylist(playlistId, newName)
        }
    }

    fun addSongToPlaylist(playlistId: Long, songId: String) {
        viewModelScope.launch {
            repository.addSongToPlaylist(playlistId, songId)
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: String) {
        viewModelScope.launch {
            repository.removeSongFromPlaylist(playlistId, songId)
            // Refresh currently viewed playlist songs
            loadSongsForPlaylist(playlistId)
        }
    }

    fun loadSongsForPlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.getSongsForPlaylist(playlistId).collectLatest { songs ->
                _activePlaylistSongs.value = songs
            }
        }
    }

    private fun serviceScopeLaunch(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (isServiceBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
