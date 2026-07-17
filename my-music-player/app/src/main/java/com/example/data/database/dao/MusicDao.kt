package com.example.data.database.dao

import androidx.room.*
import com.example.data.database.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {

    // --- Favorites ---
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun getFavorites(): Flow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun deleteFavorite(songId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId LIMIT 1)")
    fun isFavorite(songId: String): Flow<Boolean>

    // --- Recently Played ---
    @Query("SELECT * FROM recently_played ORDER BY playedAt DESC LIMIT 50")
    fun getRecentlyPlayed(): Flow<List<RecentlyPlayedEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentlyPlayed(recentlyPlayed: RecentlyPlayedEntity)

    @Query("DELETE FROM recently_played WHERE songId = :songId")
    suspend fun deleteRecentlyPlayed(songId: String)

    @Query("DELETE FROM recently_played")
    suspend fun clearHistory()

    // --- Playlists ---
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    // --- Playlist Songs ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSong(ref: PlaylistSongCrossRef)

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun deletePlaylistSong(playlistId: Long, songId: String)

    @Query("SELECT * FROM playlist_song_cross_ref WHERE playlistId = :playlistId ORDER BY addedAt ASC")
    fun getSongsForPlaylist(playlistId: Long): Flow<List<PlaylistSongCrossRef>>
}
