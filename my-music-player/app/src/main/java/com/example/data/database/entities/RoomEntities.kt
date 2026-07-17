package com.example.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val songId: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "recently_played")
data class RecentlyPlayedEntity(
    @PrimaryKey val songId: String,
    val playedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_song_cross_ref",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["playlistId"]), Index(value = ["songId"])]
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: String,
    val addedAt: Long = System.currentTimeMillis()
)
