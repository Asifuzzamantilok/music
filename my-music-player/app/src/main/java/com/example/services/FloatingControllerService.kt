package com.example.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FloatingControllerService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    // Service binding to MusicPlaybackService
    private var musicService: MusicPlaybackService? = null
    private var isBound = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var flowJob: Job? = null

    // UI elements
    private lateinit var collapsedView: FrameLayout
    private lateinit var expandedView: LinearLayout
    private lateinit var playPauseButton: ImageView
    private lateinit var prevButton: ImageView
    private lateinit var nextButton: ImageView
    private lateinit var songTitleText: TextView
    private lateinit var floatingDisc: ImageView

    // Layout configuration
    private var isExpanded = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private lateinit var prefs: SharedPreferences
    private val PREF_POSITION_X = "floating_x"
    private val PREF_POSITION_Y = "floating_y"

    private val NOTIFICATION_ID = 505
    private val CHANNEL_ID = "floating_channel"

    private var autoCollapseHandler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable {
        if (isExpanded) {
            collapse()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MusicPlaybackService.LocalServiceBinder
            musicService = binder.getService()
            isBound = true
            observePlaybackState()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            isBound = false
            musicService = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("MusicPlayerPrefs", Context.MODE_PRIVATE)

        // Bind to the music playback service
        val intent = Intent(this, MusicPlaybackService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, getServiceNotification())

        if (Settings.canDrawOverlays(this)) {
            showFloatingController()
        } else {
            Log.e("FloatingService", "Overlay permission not granted!")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Controller Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the system overlay active"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun getServiceNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating Controller Active")
            .setContentText("Tap to open full player")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .build()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showFloatingController() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Create overlay parent layout programmatically
        val frameLayout = FrameLayout(this)
        overlayView = frameLayout

        // Visual Layout built dynamically with pristine Material Design 3 style
        val cardView = CardView(this).apply {
            radius = 60f // Beautiful rounded shape
            cardElevation = 16f
            setCardBackgroundColor(Color.parseColor("#121318")) // Clean luxury dark background
            useCompatPadding = false
            preventCornerOverlap = true
        }

        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
        }

        // 1. Collapsed View: Just showing a music icon
        collapsedView = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(90, 90)
        }

        floatingDisc = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play) // Use built-in system icon as fallback
            setColorFilter(Color.parseColor("#00E5FF")) // Cyber Turquoise accent
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        collapsedView.addView(floatingDisc)

        // 2. Expanded View: Music controls with Title
        expandedView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(16, 0, 0, 0)
        }

        songTitleText = TextView(this).apply {
            text = "Not Playing"
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(220, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 16
            }
        }

        prevButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setColorFilter(Color.WHITE)
            setPadding(8, 8, 8, 8)
            setOnClickListener { musicService?.previous() }
        }

        playPauseButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.parseColor("#00E5FF"))
            setPadding(8, 8, 8, 8)
            setOnClickListener { musicService?.togglePlay() }
        }

        nextButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_next)
            setColorFilter(Color.WHITE)
            setPadding(8, 8, 8, 8)
            setOnClickListener { musicService?.next() }
        }

        expandedView.addView(songTitleText)
        expandedView.addView(prevButton)
        expandedView.addView(playPauseButton)
        expandedView.addView(nextButton)

        mainContainer.addView(collapsedView)
        mainContainer.addView(expandedView)
        cardView.addView(mainContainer)
        frameLayout.addView(cardView)

        // Window Manager Params
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val savedX = prefs.getInt(PREF_POSITION_X, 100)
        val savedY = prefs.getInt(PREF_POSITION_Y, 400)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        // Draggable gesture
        cardView.setOnTouchListener(object : View.OnTouchListener {
            private var lastAction = 0
            private var touchTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params!!.x
                        initialY = params!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        lastAction = MotionEvent.ACTION_DOWN
                        touchTime = System.currentTimeMillis()
                        resetAutoCollapseTimer()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val diffX = event.rawX - initialTouchX
                        val diffY = event.rawY - initialTouchY

                        params!!.x = initialX + diffX.toInt()
                        params!!.y = initialY + diffY.toInt()

                        windowManager?.updateViewLayout(overlayView, params)
                        lastAction = MotionEvent.ACTION_MOVE
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - touchTime
                        val diffX = event.rawX - initialTouchX
                        val diffY = event.rawY - initialTouchY

                        if (Math.abs(diffX) < 10 && Math.abs(diffY) < 10 && duration < 300) {
                            // User clicked!
                            onFloatingControllerClicked()
                        } else {
                            // Snap to edge
                            snapToScreenEdge()
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager?.addView(overlayView, params)
    }

    private fun onFloatingControllerClicked() {
        if (isExpanded) {
            collapse()
        } else {
            expand()
        }
    }

    private fun expand() {
        isExpanded = true
        expandedView.visibility = View.VISIBLE
        resetAutoCollapseTimer()
        updateLayoutSize()
    }

    private fun collapse() {
        isExpanded = false
        expandedView.visibility = View.GONE
        autoCollapseHandler.removeCallbacks(autoCollapseRunnable)
        updateLayoutSize()
    }

    private fun updateLayoutSize() {
        windowManager?.updateViewLayout(overlayView, params)
    }

    private fun resetAutoCollapseTimer() {
        autoCollapseHandler.removeCallbacks(autoCollapseRunnable)
        if (isExpanded) {
            autoCollapseHandler.postDelayed(autoCollapseRunnable, 5000) // Collapse after 5s inactivity
        }
    }

    private fun snapToScreenEdge() {
        val displayMetrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels

        val middle = screenWidth / 2
        val targetX = if (params!!.x + (overlayView?.width ?: 0) / 2 < middle) {
            16 // Near left
        } else {
            screenWidth - (overlayView?.width ?: 0) - 16 // Near right
        }

        // Quick animated movement simulation via loop
        serviceScope.launch {
            val startX = params!!.x
            val diff = targetX - startX
            for (i in 1..10) {
                params!!.x = startX + (diff * i / 10)
                windowManager?.updateViewLayout(overlayView, params)
                delay(10)
            }
            // Save last location
            prefs.edit().putInt(PREF_POSITION_X, params!!.x).putInt(PREF_POSITION_Y, params!!.y).apply()
        }
    }

    private fun observePlaybackState() {
        flowJob?.cancel()
        flowJob = serviceScope.launch {
            launch {
                musicService?.currentSong?.collectLatest { song ->
                    songTitleText.text = song?.title ?: "Not Playing"
                }
            }
            launch {
                musicService?.isPlaying?.collectLatest { isPlaying ->
                    if (isPlaying) {
                        playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
                        // Rotate disc animation
                        startDiscRotation()
                    } else {
                        playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                        stopDiscRotation()
                    }
                }
            }
        }
    }

    private var rotationJob: Job? = null
    private fun startDiscRotation() {
        rotationJob?.cancel()
        rotationJob = serviceScope.launch {
            var rotation = 0f
            while (true) {
                floatingDisc.rotation = rotation
                rotation = (rotation + 3f) % 360f
                delay(16) // ~60fps
            }
        }
    }

    private fun stopDiscRotation() {
        rotationJob?.cancel()
    }

    override fun onDestroy() {
        flowJob?.cancel()
        rotationJob?.cancel()
        serviceScope.cancel()
        autoCollapseHandler.removeCallbacks(autoCollapseRunnable)

        if (isBound) {
            unbindService(connection)
            isBound = false
        }

        overlayView?.let { windowManager?.removeView(it) }
        super.onDestroy()
    }
}
