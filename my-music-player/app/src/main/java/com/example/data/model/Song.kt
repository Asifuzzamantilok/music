package com.example.data.model

import java.io.Serializable

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,
    val albumArtUri: String? = null,
    val genre: String = "Unknown",
    val folder: String = "Unknown",
    val isSample: Boolean = false
) : Serializable {
    val durationFormatted: String
        get() {
            val totalSecs = duration / 1000
            val minutes = totalSecs / 60
            val seconds = totalSecs % 60
            return String.format("%d:%02d", minutes, seconds)
        }
}
