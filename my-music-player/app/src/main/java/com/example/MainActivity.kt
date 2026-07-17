package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.entities.PlaylistEntity
import com.example.data.model.Song
import com.example.services.FloatingControllerService
import com.example.services.RepeatMode
import com.example.ui.MusicViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                var hasStoragePermission by remember { mutableStateOf(checkStoragePermission(context)) }
                var hasNotificationPermission by remember { mutableStateOf(checkNotificationPermission(context)) }

                // Multi-permission request launcher
                val permissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    hasStoragePermission = permissions[Manifest.permission.READ_MEDIA_AUDIO] == true ||
                            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
                    hasNotificationPermission = permissions[Manifest.permission.POST_NOTIFICATIONS] == true

                    if (!hasStoragePermission) {
                        Toast.makeText(context, "Storage permission is highly recommended for scanning local music.", Toast.LENGTH_LONG).show()
                    }
                }

                LaunchedEffect(Unit) {
                    val reqs = mutableListOf<String>()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (!checkStoragePermission(context)) {
                            reqs.add(Manifest.permission.READ_MEDIA_AUDIO)
                        }
                        if (!checkNotificationPermission(context)) {
                            reqs.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else {
                        if (!checkStoragePermission(context)) {
                            reqs.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }
                    if (reqs.isNotEmpty()) {
                        permissionsLauncher.launch(reqs.toTypedArray())
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    private fun checkStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MusicViewModel = viewModel()) {
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val allSongs by viewModel.allSongs.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val context = LocalContext.current

    var activeTab by remember { mutableIntStateOf(0) }
    var isPlayerExpanded by remember { mutableStateOf(false) }

    // Dialog state for creating/renaming playlists
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var playlistDialogMode by remember { mutableStateOf("create") } // "create" or "rename"
    var activePlaylistIdForRename by remember { mutableStateOf<Long?>(null) }
    var playlistInputName by remember { mutableStateOf("") }

    // Dialog for adding song to playlist
    var showAddToPlaylistDialog by remember { mutableStateOf<Song?>(null) }

    Scaffold(
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding()) {
                // Mini Player Bar
                if (currentSong != null) {
                    BottomMiniPlayer(
                        song = currentSong!!,
                        isPlaying = isPlaying,
                        isFavorite = favorites.contains(currentSong!!.id),
                        onTogglePlay = { viewModel.togglePlay() },
                        onNext = { viewModel.next() },
                        onToggleFavorite = { viewModel.toggleFavorite(currentSong!!.id) },
                        onClick = { isPlayerExpanded = true }
                    )
                }

                // Standard Material 3 Navigation Bar
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    modifier = Modifier.testTag("main_navigation_bar")
                ) {
                    val items = listOf(
                        Triple("Home", Icons.Default.Home, Icons.Outlined.Home),
                        Triple("Library", Icons.AutoMirrored.Filled.PlaylistPlay, Icons.AutoMirrored.Filled.PlaylistPlay),
                        Triple("Search", Icons.Default.Search, Icons.Default.Search),
                        Triple("Settings", Icons.Default.Settings, Icons.Default.Settings)
                    )

                    items.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = activeTab == index,
                            onClick = { activeTab = index },
                            icon = {
                                Icon(
                                    imageVector = if (activeTab == index) item.second else item.third,
                                    contentDescription = item.first,
                                    tint = if (activeTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            label = { Text(item.first, fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeTab) {
                0 -> HomeScreenContent(
                    viewModel = viewModel,
                    onCreatePlaylist = {
                        playlistDialogMode = "create"
                        playlistInputName = ""
                        showPlaylistDialog = true
                    },
                    onRenamePlaylist = { id, name ->
                        playlistDialogMode = "rename"
                        activePlaylistIdForRename = id
                        playlistInputName = name
                        showPlaylistDialog = true
                    },
                    onAddSongToPlaylist = { showAddToPlaylistDialog = it }
                )
                1 -> LibraryScreenContent(
                    viewModel = viewModel,
                    onAddSongToPlaylist = { showAddToPlaylistDialog = it }
                )
                2 -> SearchScreenContent(
                    viewModel = viewModel,
                    onAddSongToPlaylist = { showAddToPlaylistDialog = it }
                )
                3 -> SettingsScreenContent(viewModel = viewModel)
            }
        }
    }

    // Full Player Sheet Animated Overlay
    AnimatedVisibility(
        visible = isPlayerExpanded,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        if (currentSong != null) {
            FullPlayerSheet(
                viewModel = viewModel,
                song = currentSong!!,
                isPlaying = isPlaying,
                onCollapse = { isPlayerExpanded = false }
            )
        }
    }

    // Playlist Dialog (Create or Rename)
    if (showPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showPlaylistDialog = false },
            title = {
                Text(
                    text = if (playlistDialogMode == "create") "New Playlist" else "Rename Playlist",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                OutlinedTextField(
                    value = playlistInputName,
                    onValueChange = { playlistInputName = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("playlist_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (playlistInputName.isNotBlank()) {
                            if (playlistDialogMode == "create") {
                                viewModel.createPlaylist(playlistInputName)
                            } else {
                                activePlaylistIdForRename?.let {
                                    viewModel.renamePlaylist(it, playlistInputName)
                                }
                            }
                        }
                        showPlaylistDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("playlist_dialog_confirm")
                ) {
                    Text("Save", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPlaylistDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }

    // Add Song to Playlist Chooser Dialog
    if (showAddToPlaylistDialog != null) {
        val playlists by viewModel.playlists.collectAsState()
        val activeSong = showAddToPlaylistDialog!!

        AlertDialog(
            onDismissRequest = { showAddToPlaylistDialog = null },
            title = { Text("Add to Playlist", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                if (playlists.isEmpty()) {
                    Text("You don't have any playlists yet. Create one in the Home tab!", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp)) {
                        items(playlists) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.addSongToPlaylist(playlist.id, activeSong.id)
                                        Toast.makeText(context, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
                                        showAddToPlaylistDialog = null
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QueueMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(playlist.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                            HorizontalDivider(color = Color(0x11FFFFFF))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddToPlaylistDialog = null }) {
                    Text("Dismiss", color = Color.White)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

// --- Dynamic Bouncing Audio Waves Visualizer ---
@Composable
fun AudioVisualizer(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val barCount = 32
    val animationStates = remember { List(barCount) { Animatable(0.1f) } }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isPlaying) {
                animationStates.forEachIndexed { index, animatable ->
                    scope.launch {
                        animatable.animateTo(
                            targetValue = (0.2f + 0.8f * sin(System.currentTimeMillis() / 150.0 + index).toFloat().coerceIn(0f, 1f) * (0.5f + 0.5f * Math.random().toFloat())),
                            animationSpec = tween(durationMillis = 180, easing = LinearEasing)
                        )
                    }
                }
                delay(120)
            }
        } else {
            animationStates.forEach { animatable ->
                scope.launch { animatable.animateTo(0.08f, tween(300)) }
            }
        }
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barWidth = width / (barCount * 1.5f)
        val spacing = barWidth * 0.5f

        var xOffset = spacing / 2
        animationStates.forEach { anim ->
            val barHeight = height * anim.value
            val path = Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = xOffset,
                        top = height / 2 - barHeight / 2,
                        right = xOffset + barWidth,
                        bottom = height / 2 + barHeight / 2,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2)
                    )
                )
            }
            drawPath(
                path = path,
                brush = Brush.verticalGradient(
                    colors = listOf(CyberTurquoise, NeonPink)
                )
            )
            xOffset += barWidth + spacing
        }
    }
}

// --- Home Tab Content ---
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreenContent(
    viewModel: MusicViewModel,
    onCreatePlaylist: () -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onAddSongToPlaylist: (Song) -> Unit
) {
    val playlists by viewModel.playlists.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsState()
    val allSongs by viewModel.allSongs.collectAsState()

    var activePlaylistDetails by remember { mutableStateOf<PlaylistEntity?>(null) }
    val activePlaylistSongs by viewModel.activePlaylistSongs.collectAsState()

    LaunchedEffect(activePlaylistDetails) {
        activePlaylistDetails?.let {
            viewModel.loadSongsForPlaylist(it.id)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Hero Section
        item {
            Column(modifier = Modifier.padding(top = 28.dp)) {
                Text(
                    text = "My Music Player",
                    fontSize = 28.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                Text(
                    text = "High Fidelity Offline Audio",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        if (activePlaylistDetails != null) {
            // Viewing specific playlist back option
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { activePlaylistDetails = null }
                        .padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Back to Dashboard", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(activePlaylistDetails!!.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("${activePlaylistSongs.size} Songs", fontSize = 12.sp, color = TextSecondary)
                    }
                    IconButton(
                        onClick = {
                            viewModel.deletePlaylist(activePlaylistDetails!!.id)
                            activePlaylistDetails = null
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete Playlist",
                            tint = NeonPink,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            if (activePlaylistSongs.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlaylistAddCheck,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("This playlist is empty.", color = TextSecondary)
                        Text("Add songs from the Library or Search tabs", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            } else {
                items(activePlaylistSongs) { song ->
                    SongRowItem(
                        song = song,
                        isFavorite = favorites.contains(song.id),
                        onFavoriteToggle = { viewModel.toggleFavorite(song.id) },
                        onAddPlaylist = { onAddSongToPlaylist(song) },
                        onRemoveFromPlaylist = { viewModel.removeSongFromPlaylist(activePlaylistDetails!!.id, song.id) },
                        isInPlaylistView = true,
                        onClick = {
                            viewModel.playSong(activePlaylistSongs, activePlaylistSongs.indexOf(song))
                        }
                    )
                }
            }

        } else {
            // General Dashboard View
            // Playlists Header & Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Your Playlists", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    IconButton(onClick = onCreatePlaylist) {
                        Icon(
                            imageVector = Icons.Default.AddCircleOutline,
                            contentDescription = "Create Playlist",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                if (playlists.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { onCreatePlaylist() },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.QueueMusic,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                            Text("Create your first playlist", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(playlists) { playlist ->
                            Card(
                                modifier = Modifier
                                    .width(130.dp)
                                    .combinedClickable(
                                        onClick = { activePlaylistDetails = playlist },
                                        onLongClick = { onRenamePlaylist(playlist.id, playlist.name) }
                                    ),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(90.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                Brush.radialGradient(
                                                    colors = listOf(NeonPink, NeonPurple)
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = playlist.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Hold to rename",
                                        fontSize = 10.sp,
                                        color = TextMuted,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Recently Played Header & Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recently Played", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    if (recentlyPlayed.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearHistory() }) {
                            Text("Clear", color = NeonPink, fontSize = 12.sp)
                        }
                    }
                }

                if (recentlyPlayed.isEmpty()) {
                    Text("No historic playback detected yet.", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(recentlyPlayed) { song ->
                            Column(
                                modifier = Modifier
                                    .width(110.dp)
                                    .clickable {
                                        viewModel.playSong(recentlyPlayed, recentlyPlayed.indexOf(song))
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(RoundedCornerShape(50.dp)) // Circle representation for dynamic vibe
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = CyberTurquoise,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = song.title,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = song.artist,
                                    fontSize = 10.sp,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            // Favorites Header & List
            item {
                Text("Your Favorites", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 8.dp))
            }

            val favoriteSongs = allSongs.filter { favorites.contains(it.id) }

            if (favoriteSongs.isEmpty()) {
                item {
                    Text("Tap the heart icon on any song to add to your favorites.", color = TextMuted, fontSize = 13.sp)
                }
            } else {
                items(favoriteSongs) { song ->
                    SongRowItem(
                        song = song,
                        isFavorite = true,
                        onFavoriteToggle = { viewModel.toggleFavorite(song.id) },
                        onAddPlaylist = { onAddSongToPlaylist(song) },
                        onClick = {
                            viewModel.playSong(favoriteSongs, favoriteSongs.indexOf(song))
                        }
                    )
                }
            }

            // Space filler
            item {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

// --- Library Tab Content ---
@Composable
fun LibraryScreenContent(
    viewModel: MusicViewModel,
    onAddSongToPlaylist: (Song) -> Unit
) {
    val allSongs by viewModel.allSongs.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    val categories = listOf("All Songs", "Albums", "Artists", "Folders", "Genres")
    var selectedCategory by remember { mutableStateOf("All Songs") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        // Screen Header
        Text(
            text = "Your Music Library",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(top = 28.dp, bottom = 12.dp)
        )

        // Horizontal Category Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            items(categories) { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                    label = { Text(category) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.Black,
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = TextSecondary
                    ),
                    border = null,
                    modifier = Modifier.testTag("filter_chip_$category")
                )
            }
        }

        if (isScanning) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (allSongs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.AudioFile,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No local music found.", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    Text("Enable storage permission or scan again in settings.", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        } else {
            // Display categorized structures
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedCategory) {
                    "All Songs" -> {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(allSongs) { song ->
                                SongRowItem(
                                    song = song,
                                    isFavorite = favorites.contains(song.id),
                                    onFavoriteToggle = { viewModel.toggleFavorite(song.id) },
                                    onAddPlaylist = { onAddSongToPlaylist(song) },
                                    onClick = {
                                        viewModel.playSong(allSongs, allSongs.indexOf(song))
                                    }
                                )
                            }
                            item { Spacer(modifier = Modifier.height(60.dp)) }
                        }
                    }
                    "Albums" -> {
                        val albumGroups = allSongs.groupBy { it.album }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(albumGroups.keys.toList()) { album ->
                                val albumSongs = albumGroups[album] ?: emptyList()
                                Card(
                                    modifier = Modifier.clickable {
                                        viewModel.playSong(albumSongs, 0)
                                    },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(100.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Brush.radialGradient(colors = listOf(CyberTurquoise, NeonPurple))),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Album,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(album, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${albumSongs.size} Tracks", fontSize = 11.sp, color = TextSecondary)
                                    }
                                }
                            }
                        }
                    }
                    "Artists" -> {
                        val artistGroups = allSongs.groupBy { it.artist }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(artistGroups.keys.toList()) { artist ->
                                val artistSongs = artistGroups[artist] ?: emptyList()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.playSong(artistSongs, 0) }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(50.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            tint = NeonPink,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(artist, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                                        Text("${artistSongs.size} Tracks", fontSize = 12.sp, color = TextSecondary)
                                    }
                                }
                                HorizontalDivider(color = Color(0x0FFFFFFF))
                            }
                            item { Spacer(modifier = Modifier.height(60.dp)) }
                        }
                    }
                    "Folders" -> {
                        val folderGroups = allSongs.groupBy { it.folder }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(folderGroups.keys.toList()) { folder ->
                                val folderSongs = folderGroups[folder] ?: emptyList()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.playSong(folderSongs, 0) }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(folder, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                                        Text("${folderSongs.size} Tracks", fontSize = 12.sp, color = TextSecondary)
                                    }
                                }
                                HorizontalDivider(color = Color(0x0FFFFFFF))
                            }
                            item { Spacer(modifier = Modifier.height(60.dp)) }
                        }
                    }
                    "Genres" -> {
                        val genreGroups = allSongs.groupBy { it.genre }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(genreGroups.keys.toList()) { genre ->
                                val genreSongs = genreGroups[genre] ?: emptyList()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.playSong(genreSongs, 0) }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LibraryMusic,
                                        contentDescription = null,
                                        tint = NeonPurple,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(genre, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                                        Text("${genreSongs.size} Tracks", fontSize = 12.sp, color = TextSecondary)
                                    }
                                }
                                HorizontalDivider(color = Color(0x0FFFFFFF))
                            }
                            item { Spacer(modifier = Modifier.height(60.dp)) }
                        }
                    }
                }
            }
        }
    }
}

// --- Search Tab Content ---
@Composable
fun SearchScreenContent(
    viewModel: MusicViewModel,
    onAddSongToPlaylist: (Song) -> Unit
) {
    val allSongs by viewModel.allSongs.collectAsState()
    val favorites by viewModel.favorites.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val filteredSongs = remember(searchQuery, allSongs) {
        if (searchQuery.isBlank()) emptyList()
        else {
            allSongs.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true) ||
                it.album.contains(searchQuery, ignoreCase = true) ||
                it.genre.contains(searchQuery, ignoreCase = true) ||
                it.folder.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Instant Search",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(top = 28.dp, bottom = 12.dp)
        )

        // Search Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search songs, albums, artists, genres...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondary)
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focusManager.clearFocus()
                keyboardController?.hide()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .testTag("search_text_input")
        )

        if (searchQuery.isBlank()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Explore Audio Instantly", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    Text("Type a query above to start indexing", color = TextMuted, fontSize = 12.sp)
                }
            }
        } else if (filteredSongs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No results matching \"$searchQuery\"", color = TextSecondary)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredSongs) { song ->
                    SongRowItem(
                        song = song,
                        isFavorite = favorites.contains(song.id),
                        onFavoriteToggle = { viewModel.toggleFavorite(song.id) },
                        onAddPlaylist = { onAddSongToPlaylist(song) },
                        onClick = {
                            viewModel.playSong(filteredSongs, filteredSongs.indexOf(song))
                        }
                    )
                }
                item { Spacer(modifier = Modifier.height(60.dp)) }
            }
        }
    }
}

// --- Settings Tab Content ---
@Composable
fun SettingsScreenContent(viewModel: MusicViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("MusicPlayerPrefs", Context.MODE_PRIVATE) }

    var floatingControllerEnabled by remember {
        mutableStateOf(prefs.getBoolean("floating_controller_enabled", false))
    }

    // Permission request launcher for Overlay
    val overlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = Settings.canDrawOverlays(context)
        prefs.edit().putBoolean("floating_controller_enabled", granted).apply()
        floatingControllerEnabled = granted

        if (granted) {
            context.startService(Intent(context, FloatingControllerService::class.java))
            Toast.makeText(context, "Floating Controller Enabled!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Overlay Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Settings & Config",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 28.dp, bottom = 8.dp)
            )
        }

        // Feature Toggle: Signature Floating Controller
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Floating Controller", fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                "Draggable, edge-docking media player over other apps",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Switch(
                            checked = floatingControllerEnabled,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    if (!Settings.canDrawOverlays(context)) {
                                        // Request overlay permission
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        overlayLauncher.launch(intent)
                                    } else {
                                        prefs.edit().putBoolean("floating_controller_enabled", true).apply()
                                        floatingControllerEnabled = true
                                        context.startService(Intent(context, FloatingControllerService::class.java))
                                    }
                                } else {
                                    prefs.edit().putBoolean("floating_controller_enabled", false).apply()
                                    floatingControllerEnabled = false
                                    context.stopService(Intent(context, FloatingControllerService::class.java))
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }
        }

        // Widget Configuration Instructions
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Widget Guide", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "1. Go to your Android Home Screen\n" +
                        "2. Long press empty space & select Widgets\n" +
                        "3. Find 'Music Player'\n" +
                        "4. Add Small, Medium, or Large sizes dynamically!",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // Battery Optimization Guide
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Battery Optimization Guide", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "To prevent Android OS from sleeping background playback, please exclude My Music Player from battery optimization under System App Settings -> Battery -> Unrestricted.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // Re-scan Button
        item {
            Button(
                onClick = {
                    viewModel.scanMusic(context, force = true)
                    Toast.makeText(context, "Rescanned Device Media Library!", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rescan_music_button")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Rescan Device Audio Files", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Device Active Library Folders Info
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("About Player", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Version: 1.0.0\n" +
                        "Architecture: MVVM Modular Native\n" +
                        "Format Support: MP3, FLAC, AAC, WAV, OGG, M4A\n" +
                        "Features: Local Database, Background Playback Services, Overlay Permissions.",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(40.dp)) }
    }
}

// --- Common UI Component: Song Row Item ---
@Composable
fun SongRowItem(
    song: Song,
    isFavorite: Boolean,
    onFavoriteToggle: () -> Unit,
    onAddPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    isInPlaylistView: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail representing art
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.verticalGradient(
                        colors = if (song.isSample) listOf(CyberTurquoise, NeonPurple) else listOf(CharcoalCard, SurfaceGrey)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Metadata Title/Artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Heart Icon
        IconButton(onClick = onFavoriteToggle) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Favorite",
                tint = if (isFavorite) NeonPink else TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }

        // Add to Playlist / Options
        if (isInPlaylistView && onRemoveFromPlaylist != null) {
            IconButton(onClick = onRemoveFromPlaylist) {
                Icon(
                    imageVector = Icons.Default.RemoveCircleOutline,
                    contentDescription = "Remove",
                    tint = NeonPink,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            IconButton(onClick = onAddPlaylist) {
                Icon(
                    imageVector = Icons.Default.PlaylistAdd,
                    contentDescription = "Add to playlist",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// --- Bottom Mini Player Bar ---
@Composable
fun BottomMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clickable { onClick() }
            .testTag("mini_player_bar"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CharcoalCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Brush.horizontalGradient(colors = listOf(CyberTurquoise, NeonPink))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = if (isFavorite) NeonPink else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(onClick = onTogglePlay) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = CyberTurquoise,
                    modifier = Modifier.size(24.dp)
                )
            }

            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// --- Immersive Full Screen Player Sheet ---
@Composable
fun FullPlayerSheet(
    viewModel: MusicViewModel,
    song: Song,
    isPlaying: Boolean,
    onCollapse: () -> Unit
) {
    val favorites by viewModel.favorites.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val shuffleMode by viewModel.shuffleMode.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()

    val isFavorite = favorites.contains(song.id)

    // Infinite vinyl rotation animation
    val infiniteTransition = rememberInfiniteTransition()
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 15000, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF040508))
            .statusBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Upper Controls: Back & Queue Info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCollapse) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Collapse",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(
                "NOW PLAYING",
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = {}) {
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = "Queue",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Vinyl Record / Cover Art representation with luxury rotation glow
        Box(
            modifier = Modifier
                .padding(vertical = 20.dp)
                .size(280.dp)
                .clip(CircleShape)
                .background(Color(0xFF101217))
                .border(2.dp, CyberTurquoise, CircleShape)
                .rotate(if (isPlaying) rotationAngle else 0f),
            contentAlignment = Alignment.Center
        ) {
            // Vinyl grooves
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = size / 2f
                val radiusMax = size.minDimension / 2f
                for (i in 1..8) {
                    drawCircle(
                        color = Color(0x18FFFFFF),
                        radius = radiusMax * (i / 10f),
                        style = Stroke(width = 1f)
                    )
                }
            }

            // Central Cover thumbnail representation
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(CyberTurquoise, NeonPink)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Track Title, Artist, & Favorite Heart
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    fontSize = 14.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            IconButton(onClick = { viewModel.toggleFavorite(song.id) }) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) NeonPink else Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Beautiful active frequency wave visualizer
        AudioVisualizer(
            isPlaying = isPlaying,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(vertical = 4.dp)
        )

        // Custom Slider position controller
        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = currentPosition.toFloat(),
                onValueChange = { viewModel.seekTo(it.toLong()) },
                valueRange = 0f..song.duration.toFloat(),
                colors = SliderDefaults.colors(
                    activeTrackColor = CyberTurquoise,
                    inactiveTrackColor = Color(0x22FFFFFF),
                    thumbColor = CyberTurquoise
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatDuration(currentPosition), color = TextSecondary, fontSize = 11.sp)
                Text(formatDuration(song.duration), color = TextSecondary, fontSize = 11.sp)
            }
        }

        // Playback Action bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shuffle
            IconButton(onClick = { viewModel.toggleShuffle() }) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (shuffleMode) CyberTurquoise else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Previous
            IconButton(onClick = { viewModel.previous() }) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Big Play/Pause Trigger
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(CyberTurquoise)
                    .clickable { viewModel.togglePlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "PlayPause",
                    tint = Color.Black,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Next
            IconButton(onClick = { viewModel.next() }) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Repeat Cycle
            IconButton(onClick = { viewModel.cycleRepeatMode() }) {
                Icon(
                    imageVector = when (repeatMode) {
                        RepeatMode.OFF -> Icons.Default.Repeat
                        RepeatMode.ALL -> Icons.Default.Repeat
                        RepeatMode.ONE -> Icons.Default.RepeatOne
                    },
                    contentDescription = "Repeat",
                    tint = when (repeatMode) {
                        RepeatMode.OFF -> Color.White
                        else -> CyberTurquoise
                    },
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
    }
}

// --- Utilities & Extensions ---

private fun formatDuration(durationMs: Long): String {
    val totalSecs = durationMs / 1000
    val minutes = totalSecs / 60
    val seconds = totalSecs % 60
    return String.format("%d:%02d", minutes, seconds)
}

// Global semantic colors referencing palette definitions
val TextPrimary = Color(0xFFF2F4F7)
val TextSecondary = Color(0xFF9EA3B0)
val TextMuted = Color(0xFF636975)
val CyberTurquoise = Color(0xFF00E5FF)
val NeonPink = Color(0xFFFF007F)
val NeonPurple = Color(0xFFBD00FF)
val MidnightBlack = Color(0xFF07080B)
val CharcoalCard = Color(0xFF11131A)
val SurfaceGrey = Color(0xFF161822)
