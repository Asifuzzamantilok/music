package com.example.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import com.example.R
import com.example.services.MusicPlaybackService

abstract class BaseWidgetProvider(private val layoutId: Int) : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("MusicPlayerPrefs", Context.MODE_PRIVATE)
        val isPlaying = prefs.getBoolean("widget_is_playing", false)
        val songTitle = prefs.getString("widget_song_title", "My Music Player") ?: "My Music Player"
        val songArtist = prefs.getString("widget_song_artist", "No active track") ?: "No active track"

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, layoutId)
            setupClickIntents(context, views)
            updateWidgetContent(views, isPlaying, songTitle, songArtist)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == MusicPlaybackService.ACTION_WIDGET_UPDATE) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, this.javaClass)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    private fun setupClickIntents(context: Context, views: RemoteViews) {
        // Play / Pause
        val toggleIntent = Intent(context, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_TOGGLE_PLAY
        }
        val togglePending = PendingIntent.getService(
            context, 10, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_play_pause, togglePending)

        // Previous
        val prevIntent = Intent(context, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_PREVIOUS
        }
        val prevPending = PendingIntent.getService(
            context, 11, prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOptionalOnClickPendingIntent(R.id.btn_prev, prevPending)

        // Next
        val nextIntent = Intent(context, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_NEXT
        }
        val nextPending = PendingIntent.getService(
            context, 12, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOptionalOnClickPendingIntent(R.id.btn_next, nextPending)
    }

    protected open fun updateWidgetContent(views: RemoteViews, isPlaying: Boolean, title: String, artist: String) {
        val playIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        views.setImageViewResource(R.id.btn_play_pause, playIcon)
    }

    // Helper extension to safely bind views that may not exist in all layouts
    private fun RemoteViews.setOptionalOnClickPendingIntent(viewId: Int, pendingIntent: PendingIntent) {
        try {
            setOnClickPendingIntent(viewId, pendingIntent)
        } catch (e: Exception) {
            // View doesn't exist in current layout, ignore safely
        }
    }
}

class SmallWidgetProvider : BaseWidgetProvider(R.layout.widget_small)

class MediumWidgetProvider : BaseWidgetProvider(R.layout.widget_medium)

class LargeWidgetProvider : BaseWidgetProvider(R.layout.widget_large) {
    override fun updateWidgetContent(views: RemoteViews, isPlaying: Boolean, title: String, artist: String) {
        super.updateWidgetContent(views, isPlaying, title, artist)
        views.setTextViewText(R.id.widget_song_title, title)
        views.setTextViewText(R.id.widget_song_artist, artist)
        // Set dynamic visual representation of playing state
        val artResource = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        views.setImageViewResource(R.id.widget_album_art, artResource)
    }
}
