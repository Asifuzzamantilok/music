package com.example.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.data.database.dao.MusicDao
import com.example.data.database.entities.*
import com.example.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class MusicRepository(private val musicDao: MusicDao) {

    // --- Curated Sample Tracks for Instant Playback ---
    val sampleSongs = listOf(
        Song(
            id = "sample_1",
            title = "Acoustic Horizon",
            artist = "SoundHelix",
            album = "Helix Acoustics",
            duration = 372000, // 6:12
            path = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
            albumArtUri = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=500&q=80",
            genre = "Acoustic",
            folder = "Sample Cloud",
            isSample = true
        ),
        Song(
            id = "sample_2",
            title = "Chillwave Breeze",
            artist = "SoundHelix",
            album = "Helix Dream",
            duration = 423000, // 7:03
            path = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
            albumArtUri = "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=500&q=80",
            genre = "Chillwave",
            folder = "Sample Cloud",
            isSample = true
        ),
        Song(
            id = "sample_3",
            title = "Midnight Drive",
            artist = "SoundHelix",
            album = "Night Riders",
            duration = 344000, // 5:44
            path = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3",
            albumArtUri = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=500&q=80",
            genre = "Synthwave",
            folder = "Sample Cloud",
            isSample = true
        ),
        Song(
            id = "sample_4",
            title = "Cybernetic Pulse",
            artist = "SoundHelix",
            album = "Grid Runner",
            duration = 302000, // 5:02
            path = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3",
            albumArtUri = "https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=500&q=80",
            genre = "Electronic",
            folder = "Sample Cloud",
            isSample = true
        )
    )

    // Cached copy of scanned/loaded songs
    private var cachedSongs: List<Song> = emptyList()

    /**
     * Scans local MediaStore and merges with pre-configured sample songs.
     */
    suspend fun scanLocalMusic(context: Context, forceRefresh: Boolean = false): List<Song> = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedSongs.isNotEmpty()) {
            return@withContext cachedSongs
        }

        val localSongs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )

        // Only query MediaStore if appropriate permissions are granted
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn).toString()
                    val title = cursor.getString(titleColumn) ?: "Unknown Title"
                    val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                    val album = cursor.getString(albumColumn) ?: "Unknown Album"
                    val duration = cursor.getLong(durationColumn)
                    val path = cursor.getString(dataColumn) ?: ""
                    val albumId = cursor.getLong(albumIdColumn)

                    // Skip empty paths or durations < 1s
                    if (path.isEmpty() || duration < 1000) continue

                    // Check supported formats
                    val file = File(path)
                    val ext = file.extension.lowercase()
                    val supportedFormats = listOf("mp3", "flac", "aac", "wav", "ogg", "m4a")
                    if (ext.isNotEmpty() && !supportedFormats.contains(ext)) continue

                    val albumArtUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    ).toString()

                    val parentFolder = file.parentFile?.name ?: "Unknown Folder"

                    localSongs.add(
                        Song(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            duration = duration,
                            path = path,
                            albumArtUri = albumArtUri,
                            genre = "Local Audio", // Default genre (or query if available)
                            folder = parentFolder,
                            isSample = false
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error scanning local MediaStore: ${e.message}")
        }

        // Merge local scanned files with beautiful sample tracks
        val finalSongsList = localSongs + sampleSongs
        cachedSongs = finalSongsList
        return@withContext finalSongsList
    }

    fun getCachedSongs(): List<Song> {
        return cachedSongs.ifEmpty { sampleSongs }
    }

    fun findSongById(id: String): Song? {
        return getCachedSongs().find { it.id == id }
    }

    // --- Favorites Logic ---
    val favoritesFlow: Flow<List<String>> = musicDao.getFavorites()
        .map { list -> list.map { it.songId } }
        .flowOn(Dispatchers.IO)

    suspend fun addFavorite(songId: String) = withContext(Dispatchers.IO) {
        musicDao.insertFavorite(FavoriteEntity(songId))
    }

    suspend fun removeFavorite(songId: String) = withContext(Dispatchers.IO) {
        musicDao.deleteFavorite(songId)
    }

    fun isFavorite(songId: String): Flow<Boolean> {
        return musicDao.isFavorite(songId).flowOn(Dispatchers.IO)
    }

    // --- Recently Played Logic ---
    val recentlyPlayedFlow: Flow<List<Song>> = musicDao.getRecentlyPlayed()
        .map { list ->
            list.mapNotNull { entity -> findSongById(entity.songId) }
        }
        .flowOn(Dispatchers.IO)

    suspend fun addRecentlyPlayed(songId: String) = withContext(Dispatchers.IO) {
        musicDao.insertRecentlyPlayed(RecentlyPlayedEntity(songId))
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        musicDao.clearHistory()
    }

    // --- Playlists Logic ---
    val playlistsFlow: Flow<List<PlaylistEntity>> = musicDao.getAllPlaylists()
        .flowOn(Dispatchers.IO)

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        musicDao.insertPlaylist(PlaylistEntity(name = name))
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String) = withContext(Dispatchers.IO) {
        val original = musicDao.getAllPlaylists().map { list -> list.find { it.id == playlistId } }.flowOn(Dispatchers.IO)
        // Simple update query
        musicDao.updatePlaylist(PlaylistEntity(id = playlistId, name = newName))
    }

    suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        musicDao.deletePlaylist(playlistId)
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: String) = withContext(Dispatchers.IO) {
        musicDao.insertPlaylistSong(PlaylistSongCrossRef(playlistId, songId))
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String) = withContext(Dispatchers.IO) {
        musicDao.deletePlaylistSong(playlistId, songId)
    }

    fun getSongsForPlaylist(playlistId: Long): Flow<List<Song>> {
        return musicDao.getSongsForPlaylist(playlistId)
            .map { list ->
                list.mapNotNull { ref -> findSongById(ref.songId) }
            }
            .flowOn(Dispatchers.IO)
    }
}
