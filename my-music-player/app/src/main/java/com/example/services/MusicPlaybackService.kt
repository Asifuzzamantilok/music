package com.example.services

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

enum class RepeatMode {
    OFF, ALL, ONE
}

class MusicPlaybackService : Service(), MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener {

    private val binder = LocalServiceBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Playback state flows
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

    // Queues
    private val originalQueue = mutableListOf<Song>()
    private val activeQueue = mutableListOf<Song>()
    private var currentIndex = -1

    // Coroutine scope for service
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionUpdateJob: Job? = null

    // Notification details
    private val NOTIFICATION_ID = 404
    private val CHANNEL_ID = "playback_channel"
    private var mediaSession: MediaSessionCompat? = null

    // Shared Preferences for persistent memory
    private lateinit var prefs: SharedPreferences

    inner class LocalServiceBinder : Binder() {
        fun getService(): MusicPlaybackService = this@MusicPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("MusicPlaybackService", "Service Created")
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prefs = getSharedPreferences("MusicPlayerPrefs", Context.MODE_PRIVATE)

        initializeMediaPlayer()
        setupMediaSession()
        createNotificationChannel()

        // Start tracking position
        startPositionTracker()
    }

    private fun initializeMediaPlayer() {
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setOnPreparedListener(this@MusicPlaybackService)
            setOnErrorListener(this@MusicPlaybackService)
            setOnCompletionListener(this@MusicPlaybackService)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "MyMusicPlayerSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { next() }
                override fun onSkipToPrevious() { previous() }
                override fun onSeekTo(pos: Long) { seekTo(pos) }
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            Log.d("MusicPlaybackService", "Action received: $action")
            when (action) {
                ACTION_PLAY -> play()
                ACTION_PAUSE -> pause()
                ACTION_TOGGLE_PLAY -> togglePlay()
                ACTION_NEXT -> next()
                ACTION_PREVIOUS -> previous()
                ACTION_STOP -> stopService()
            }
        }
        return START_NOT_STICKY
    }

    // --- Media Player Control API ---

    fun setQueue(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        originalQueue.clear()
        originalQueue.addAll(songs)

        activeQueue.clear()
        activeQueue.addAll(songs)

        currentIndex = if (startIndex in songs.indices) startIndex else 0

        if (_shuffleMode.value) {
            val currentSongItem = songs[currentIndex]
            val rest = songs.filter { it.id != currentSongItem.id }.shuffled()
            activeQueue.clear()
            activeQueue.add(currentSongItem)
            activeQueue.addAll(rest)
            currentIndex = 0
        }

        loadSong(activeQueue[currentIndex])
    }

    fun getQueue(): List<Song> = activeQueue

    fun getCurrentSongIndex(): Int = currentIndex

    private fun loadSong(song: Song) {
        serviceScope.launch {
            try {
                mediaPlayer?.reset()
                _currentSong.value = song
                _currentPosition.value = 0L

                // Update system MediaSession metadata
                updateMediaSessionMetadata(song)

                if (song.isSample) {
                    // Sample URL
                    mediaPlayer?.setDataSource(song.path)
                } else {
                    // Local file Uri
                    mediaPlayer?.setDataSource(this@MusicPlaybackService, android.net.Uri.parse(song.path))
                }

                mediaPlayer?.prepareAsync()
                saveLastPlayedSong(song.id)
            } catch (e: Exception) {
                Log.e("MusicPlaybackService", "Error loading song: ${e.message}")
                next() // Try playing next on failure
            }
        }
    }

    override fun onPrepared(mp: MediaPlayer?) {
        if (requestAudioFocus()) {
            mp?.start()
            _isPlaying.value = true
            updateNotification()
            updateMediaSessionState()
            notifyWidgets()
        }
    }

    fun play() {
        if (mediaPlayer == null) return
        if (requestAudioFocus()) {
            mediaPlayer?.start()
            _isPlaying.value = true
            updateNotification()
            updateMediaSessionState()
            notifyWidgets()
        }
    }

    fun pause() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            _isPlaying.value = false
            updateNotification()
            updateMediaSessionState()
            notifyWidgets()
            stopForeground(false) // Keep notification but non-foreground
        }
    }

    fun togglePlay() {
        if (_isPlaying.value) pause() else play()
    }

    fun next() {
        if (activeQueue.isEmpty()) return
        if (_repeatMode.value == RepeatMode.ONE) {
            seekTo(0)
            play()
            return
        }

        currentIndex++
        if (currentIndex >= activeQueue.size) {
            currentIndex = if (_repeatMode.value == RepeatMode.ALL) 0 else activeQueue.size - 1
        }

        if (currentIndex in activeQueue.indices) {
            loadSong(activeQueue[currentIndex])
        }
    }

    fun previous() {
        if (activeQueue.isEmpty()) return
        if (_currentPosition.value > 5000) {
            // Restart current song if past 5 seconds
            seekTo(0)
            return
        }

        currentIndex--
        if (currentIndex < 0) {
            currentIndex = if (_repeatMode.value == RepeatMode.ALL) activeQueue.size - 1 else 0
        }

        if (currentIndex in activeQueue.indices) {
            loadSong(activeQueue[currentIndex])
        }
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer?.seekTo(positionMs.toInt())
        _currentPosition.value = positionMs
        updateMediaSessionState()
    }

    fun toggleShuffle() {
        val nextShuffle = !_shuffleMode.value
        _shuffleMode.value = nextShuffle

        val currentItem = _currentSong.value
        if (nextShuffle && currentItem != null) {
            // Shuffle but keep current song at first position
            val rest = originalQueue.filter { it.id != currentItem.id }.shuffled()
            activeQueue.clear()
            activeQueue.add(currentItem)
            activeQueue.addAll(rest)
            currentIndex = 0
        } else {
            // Revert to original order
            activeQueue.clear()
            activeQueue.addAll(originalQueue)
            if (currentItem != null) {
                currentIndex = activeQueue.indexOfFirst { it.id == currentItem.id }
            }
        }
    }

    fun cycleRepeatMode() {
        val nextMode = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _repeatMode.value = nextMode
    }

    // --- Audio Focus ---

    private fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mediaPlayer?.setVolume(0.2f, 0.2f)
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            mediaPlayer?.setVolume(1.0f, 1.0f)
                            play()
                        }
                    }
                }
                .build()
            return audioManager?.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager?.requestAudioFocus(
                { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                        AudioManager.AUDIOFOCUS_GAIN -> play()
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus { }
        }
    }

    // --- Media Player Callbacks ---

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        Log.e("MusicPlaybackService", "MediaPlayer error: what=$what, extra=$extra")
        _isPlaying.value = false
        updateNotification()
        return true
    }

    override fun onCompletion(mp: MediaPlayer?) {
        if (_repeatMode.value == RepeatMode.ONE) {
            seekTo(0)
            play()
        } else {
            next()
        }
    }

    // --- Notification & Foreground Service Setup ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media controls and current track details"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun getNotification(): Notification {
        val song = _currentSong.value ?: Song("empty", "My Music Player", "Enjoy your offline vibes", "", 0, "")
        val playPauseAction = if (_isPlaying.value) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pause",
                getServicePendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Play",
                getServicePendingIntent(ACTION_PLAY)
            )
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Falls back to default icon
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSubText(song.album)
            .setContentIntent(contentPendingIntent)
            .setSilent(true)
            .setOngoing(_isPlaying.value)
            .setStyle(mediaStyle)
            .addAction(
                android.R.drawable.ic_media_previous, "Previous",
                getServicePendingIntent(ACTION_PREVIOUS)
            )
            .addAction(playPauseAction)
            .addAction(
                android.R.drawable.ic_media_next, "Next",
                getServicePendingIntent(ACTION_NEXT)
            )

        // Load bitmap cover dynamically if needed in background, or use default fallback
        val fallbackBitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_background)
        builder.setLargeIcon(fallbackBitmap)

        return builder.build()
    }

    private fun updateNotification() {
        val notification = getNotification()
        if (_isPlaying.value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun getServicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicPlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateMediaSessionState() {
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                if (_isPlaying.value) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                _currentPosition.value,
                1.0f
            )
        mediaSession?.setPlaybackState(stateBuilder.build())
    }

    private fun updateMediaSessionMetadata(song: Song) {
        val metadataBuilder = android.support.v4.media.MediaMetadataCompat.Builder()
            .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
        mediaSession?.setMetadata(metadataBuilder.build())
    }

    private fun startPositionTracker() {
        positionUpdateJob?.cancel()
        positionUpdateJob = serviceScope.launch {
            while (isActive) {
                if (_isPlaying.value && mediaPlayer?.isPlaying == true) {
                    _currentPosition.value = mediaPlayer?.currentPosition?.toLong() ?: 0L
                    updateMediaSessionState()
                }
                delay(1000)
            }
        }
    }

    // --- Widget & External Updates ---

    private fun notifyWidgets() {
        // Save current state for widgets to read instantly
        val song = _currentSong.value
        prefs.edit().apply {
            putBoolean("widget_is_playing", _isPlaying.value)
            putString("widget_song_title", song?.title ?: "My Music Player")
            putString("widget_song_artist", song?.artist ?: "No active track")
            apply()
        }

        // Send updates to AppWidgetProvider
        val updateIntent = Intent(ACTION_WIDGET_UPDATE).apply {
            `package` = packageName
        }
        sendBroadcast(updateIntent)
    }

    // --- SharedPreferences Cache ---

    private fun saveLastPlayedSong(songId: String) {
        prefs.edit().putString("last_played_song_id", songId).apply()
    }

    fun getLastPlayedSongId(): String? {
        return prefs.getString("last_played_song_id", null)
    }

    private fun stopService() {
        pause()
        abandonAudioFocus()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaPlayer?.release()
        mediaPlayer = null
        stopSelf()
    }

    override fun onDestroy() {
        Log.d("MusicPlaybackService", "Service Destroyed")
        serviceScope.cancel()
        stopService()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.example.musicplayer.PLAY"
        const val ACTION_PAUSE = "com.example.musicplayer.PAUSE"
        const val ACTION_TOGGLE_PLAY = "com.example.musicplayer.TOGGLE_PLAY"
        const val ACTION_NEXT = "com.example.musicplayer.NEXT"
        const val ACTION_PREVIOUS = "com.example.musicplayer.PREVIOUS"
        const val ACTION_STOP = "com.example.musicplayer.STOP"
        const val ACTION_WIDGET_UPDATE = "com.example.musicplayer.WIDGET_UPDATE"
    }
}
