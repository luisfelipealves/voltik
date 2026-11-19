package com.voltik.player.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class UiSong(val uri: String, val title: String, val artist: String, val durationMs: Long = 0)
data class UiAlbum(val id: String, val title: String, val artist: String, val songs: List<UiSong>, val isCompilation: Boolean)
data class UiFolder(val uri: String, val label: String)
data class UiPlayer(
    val title: String = "", val artist: String = "", val durationMs: Long = 0,
    val positionMs: Long = 0, val playing: Boolean = false, val shuffle: Boolean = false,
    val repeatMode: Int = 0, val audioLevel: Float = 0f
)
data class UiActions(
    val addFolder: () -> Unit,
    val removeFolder: (String) -> Unit,
    val refresh: () -> Unit,
    val playSong: (UiAlbum, Int) -> Unit,
    val setCompilation: (String, Boolean?) -> Unit,
    val togglePlay: () -> Unit,
    val previous: () -> Unit,
    val next: () -> Unit,
    val seekTo: (Long) -> Unit,
    val toggleShuffle: () -> Unit,
    val cycleRepeat: () -> Unit,
    val setKeepAwake: (Boolean) -> Unit,
    val loadArtwork: suspend (List<String>) -> ByteArray? = { null }
)

private val Ink = Color(0xFF101115)
private val Panel = Color(0xFF24262B)
private val Accent = Color(0xFFC4F36A)
private val Secondary = Color(0xFFB1B3B5)
private val Border = Color(0xFF35373B)
private val ArtworkCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}
private val NoArtworkCache = LruCache<String, Boolean>(256)
private val ArtworkReads = Semaphore(2)

@Composable
fun VoltikApp(
    albums: List<UiAlbum>, folders: List<UiFolder>, player: UiPlayer,
    keepAwake: Boolean, scanning: Boolean, message: String?, actions: UiActions,
    visualState: VisualUiState? = null,
    visualActions: VisualUiActions? = null,
    projectMContent: (@Composable () -> Unit)? = null
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var albumId by rememberSaveable { mutableStateOf<String?>(null) }
    var artist by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val selectedAlbum = albums.firstOrNull { it.id == albumId }
    BackHandler(enabled = showPlayer || settings || albumId != null || artist != null || searchVisible) {
        when {
            showPlayer -> showPlayer = false
            settings -> settings = false
            albumId != null -> albumId = null
            artist != null -> artist = null
            else -> { searchVisible = false; query = "" }
        }
    }
    val matches: (String) -> Boolean = { it.contains(query.trim(), ignoreCase = true) }
    val filtered = albums.filter { query.isBlank() || matches(it.title) || matches(it.artist) || it.songs.any { song -> matches(song.title) || matches(song.artist) } }
    val playAndOpen = actions.copy(playSong = { album, index ->
        actions.playSong(album, index)
        showPlayer = true
    })

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel, onSurface = Color.White)) {
        Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding()) {
            when {
                showPlayer -> PlayerScreen(player, { showPlayer = false }, actions, visualState, visualActions, projectMContent)
                settings -> SettingsScreen(folders, keepAwake, scanning, message, { settings = false }, actions)
                selectedAlbum != null -> AlbumScreen(selectedAlbum, { albumId = null }, playAndOpen)
                artist != null -> ArtistScreen(artist!!, filtered.filter { !it.isCompilation && it.artist == artist }, { artist = null }, { albumId = it }, actions.loadArtwork)
                else -> {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("VOLTIK", color = Accent, letterSpacing = 2.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) query = "" }) { Text(if (searchVisible) "Fechar busca" else "Buscar") }
                        TextButton(onClick = { settings = true }) { Text("Ajustes") }
                    }
                    Text("Biblioteca", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Color.White, style = MaterialTheme.typography.headlineMedium)
                    if (searchVisible) {
                        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), singleLine = true, label = { Text("Música, artista ou álbum") })
                    }
                    TabRow(selectedTabIndex = tab, containerColor = Ink, contentColor = Accent) {
                        listOf("Álbuns", "Artistas", "Coletâneas").forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, maxLines = 1) }) }
                    }
                    if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Box(Modifier.weight(1f)) {
                        if (folders.isEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Toque em Selecionar pasta, escolha onde estão suas músicas, toque em Usar esta pasta e permita o acesso. Você pode adicionar outras pastas depois nas configurações. O Android pode restringir a raiz do armazenamento e algumas pastas.", color = Secondary)
                                OutlinedButton(onClick = actions.addFolder) { Text("Selecionar pasta de músicas") }
                            }
                        } else if (query.isNotBlank()) {
                            SearchResults(filtered, query, { albumId = it }, playAndOpen)
                        } else when (tab) {
                            0 -> AlbumGrid(filtered, { albumId = it }, actions.loadArtwork)
                            1 -> ArtistList(filtered.filterNot { it.isCompilation }.map { it.artist }.distinct().sortedBy { it.lowercase() }, { artist = it })
                            else -> AlbumGrid(filtered.filter { it.isCompilation }, { albumId = it }, actions.loadArtwork)
                        }
                    }
                }
            }
            if (!message.isNullOrBlank()) Text(message, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 20.dp, vertical = 8.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (!showPlayer && player.title.isNotBlank()) MiniPlayer(player, { showPlayer = true }, actions.togglePlay)
        }
    }
}

@Composable private fun Header(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Voltar") }
        Text(title, modifier = Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable private fun Cover(title: String, modifier: Modifier = Modifier, album: UiAlbum? = null, loadArtwork: suspend (List<String>) -> ByteArray? = { null }) {
    val uris = remember(album) { album?.songs?.map { it.uri }.orEmpty() }
    val key = remember(album?.id, uris) { (album?.id ?: "") + "\u0000" + uris.joinToString("\u0000") }
    val image by androidx.compose.runtime.key(key) { produceState<Bitmap?>(initialValue = ArtworkCache.get(key), key1 = key) {
        value = ArtworkCache.get(key)
        if (album != null && value == null && NoArtworkCache.get(key) == null) value = withContext(Dispatchers.IO) {
            try {
                ArtworkReads.withPermit {
                    val existing = ArtworkCache.get(key)
                    if (existing != null) existing
                    else if (NoArtworkCache.get(key) != null) null
                    else {
                        val bytes = loadArtwork(uris)
                        val bitmap = if (bytes == null) null else {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            var sample = 1
                            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                        if (bitmap != null) ArtworkCache.put(key, bitmap) else NoArtworkCache.put(key, true)
                        bitmap
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { null }
        }
    } }
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(Panel), contentAlignment = Alignment.Center) {
        if (image == null) Text(title.take(1).uppercase().ifBlank { "♫" }, fontSize = 38.sp, color = Accent)
        else Image(bitmap = image!!.asImageBitmap(), contentDescription = "Capa do álbum $title", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable private fun AlbumGrid(albums: List<UiAlbum>, onOpen: (String) -> Unit, loadArtwork: suspend (List<String>) -> ByteArray?) {
    if (albums.isEmpty()) { Text("Nenhum álbum encontrado.", Modifier.padding(24.dp), color = Secondary); return }
    LazyVerticalGrid(columns = GridCells.Adaptive(145.dp), contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items(albums, key = { it.id }) { album ->
            Column(Modifier.testTag("album-${album.id}").clickable { onOpen(album.id) }) {
                Cover(album.title, Modifier.fillMaxWidth().aspectRatio(1f), album, loadArtwork)
                Spacer(Modifier.height(7.dp))
                Text(album.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(album.artist, color = Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun ArtistList(artists: List<String>, onOpen: (String) -> Unit) {
    if (artists.isEmpty()) { Text("Nenhum artista encontrado.", Modifier.padding(24.dp), color = Secondary); return }
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        items(artists) { artist -> Row(Modifier.fillMaxWidth().clickable { onOpen(artist) }.padding(vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) { Text(artist, Modifier.weight(1f), color = Color.White); Text("›", color = Accent) }; HorizontalDivider(color = Border) }
    }
}

@Composable private fun ColumnScope.ArtistScreen(artist: String, albums: List<UiAlbum>, back: () -> Unit, onOpen: (String) -> Unit, loadArtwork: suspend (List<String>) -> ByteArray?) {
    Header("Artista", back)
    Text(artist, Modifier.padding(horizontal = 20.dp, vertical = 16.dp), color = Color.White, style = MaterialTheme.typography.headlineMedium)
    Text("Álbuns", Modifier.padding(horizontal = 20.dp), color = Secondary)
    Box(Modifier.weight(1f)) { AlbumGrid(albums, onOpen, loadArtwork) }
}

@Composable private fun ColumnScope.AlbumScreen(album: UiAlbum, back: () -> Unit, actions: UiActions) {
    var menu by remember { mutableStateOf(false) }
    Header("Álbum", back)
    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(album.title, Modifier.size(112.dp), album, actions.loadArtwork)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(album.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(album.artist, color = Secondary, style = MaterialTheme.typography.bodyMedium)
            Text("${album.songs.size} músicas", color = Secondary, style = MaterialTheme.typography.bodySmall)
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 22.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (album.isCompilation) "Desmarcar coletânea" else "Marcar como coletânea") }, onClick = { actions.setCompilation(album.id, !album.isCompilation); menu = false })
                DropdownMenuItem(text = { Text("Usar classificação automática") }, onClick = { actions.setCompilation(album.id, null); menu = false })
            }
        }
    }
    Button(onClick = { actions.playSong(album, 0) }, enabled = album.songs.isNotEmpty(), modifier = Modifier.testTag("album-play").padding(horizontal = 20.dp)) { Text("▶  Reproduzir álbum") }
    Text("Músicas", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Color.White, style = MaterialTheme.typography.titleMedium)
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp)) {
        items(album.songs.size) { index ->
            val song = album.songs[index]
            Row(Modifier.fillMaxWidth().testTag("track-$index").clickable { actions.playSong(album, index) }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}".padStart(2, '0'), Modifier.width(36.dp), color = Secondary, style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f)) { Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(song.artist, color = Secondary, style = MaterialTheme.typography.bodySmall) }
                if (song.durationMs > 0) Text(formatTime(song.durationMs), color = Secondary, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = Border)
        }
    }
}

@Composable private fun SearchResults(albums: List<UiAlbum>, query: String, onOpen: (String) -> Unit, actions: UiActions) {
    val tracks = albums.flatMap { album -> album.songs.mapIndexedNotNull { i, s -> if (s.title.contains(query.trim(), true) || s.artist.contains(query.trim(), true)) Triple(album, i, s) else null } }
    LazyColumn(contentPadding = PaddingValues(20.dp)) {
        if (albums.isEmpty() && tracks.isEmpty()) item { Text("Nenhum resultado encontrado.", color = Secondary) }
        if (tracks.isNotEmpty()) item { Text("Músicas", color = Accent, style = MaterialTheme.typography.titleSmall) }
        items(tracks, key = { it.third.uri }) { (album, index, song) ->
            Column(Modifier.fillMaxWidth().clickable { actions.playSong(album, index) }.padding(vertical = 12.dp)) { Text(song.title, color = Color.White); Text("${song.artist} · ${album.title}", color = Secondary, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider(color = Border)
        }
        if (albums.isNotEmpty()) item { Spacer(Modifier.height(16.dp)); Text("Álbuns e artistas", color = Accent, style = MaterialTheme.typography.titleSmall) }
        items(albums, key = { it.id }) { album ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(album.id) }.padding(vertical = 12.dp)) { Text(album.title, color = Color.White); Text(album.artist, color = Secondary, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider(color = Border)
        }
    }
}

@Composable private fun ColumnScope.SettingsScreen(folders: List<UiFolder>, keepAwake: Boolean, scanning: Boolean, message: String?, back: () -> Unit, actions: UiActions) {
    Header("Configurações", back)
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Pastas de música", color = Color.White, style = MaterialTheme.typography.titleLarge) }
        item { Text("Escolha uma ou mais pastas para montar sua biblioteca.", color = Secondary) }
        items(folders, key = { it.uri }) { folder ->
            Row(verticalAlignment = Alignment.CenterVertically) { Text(folder.label, Modifier.weight(1f), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis); TextButton(onClick = { actions.removeFolder(folder.uri) }) { Text("Remover") } }
            HorizontalDivider(color = Border)
        }
        item { OutlinedButton(onClick = actions.addFolder) { Text("Adicionar pasta") } }
        item { OutlinedButton(onClick = actions.refresh, enabled = !scanning && folders.isNotEmpty()) { Text(if (scanning) "Atualizando…" else "Atualizar biblioteca") } }
        item { HorizontalDivider(color = Border); Spacer(Modifier.height(12.dp)) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Manter tela acesa", color = Color.White); Text("Enquanto o app estiver aberto", color = Secondary, style = MaterialTheme.typography.bodySmall) }; Switch(checked = keepAwake, onCheckedChange = actions.setKeepAwake) } }
    }
}

@Composable private fun MiniPlayer(player: UiPlayer, open: () -> Unit, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).clickable(onClick = open).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(player.title, Modifier.size(40.dp)); Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(player.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(player.artist, color = Secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        TextButton(onClick = toggle) { Text(if (player.playing) "Pausar" else "Tocar") }
    }
}

@Composable private fun ColumnScope.PlayerScreen(player: UiPlayer, back: () -> Unit, actions: UiActions, visualState: VisualUiState?, visualActions: VisualUiActions?, projectMContent: (@Composable () -> Unit)?) {
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionEpoch by remember { mutableIntStateOf(0) }
    var fingerDown by remember { mutableStateOf(false) }
    var visualDialogOpen by remember { mutableStateOf(false) }
    val touchExploration = LocalAccessibilityManager.current?.isTouchExplorationEnabled == true
    LaunchedEffect(player.playing, interactionEpoch, fingerDown, visualDialogOpen, visualState?.busy, touchExploration) {
        if (!player.playing || touchExploration) controlsVisible = true
        else if (!fingerDown && !visualDialogOpen && visualState?.busy != true) {
            kotlinx.coroutines.delay(5000)
            controlsVisible = false
        }
    }
    Box(
        Modifier.weight(1f).fillMaxWidth().testTag("visual-surface")
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.any { it.pressed }
                        fingerDown = pressed
                        if (pressed) {
                            interactionEpoch++
                            controlsVisible = true
                        }
                    }
                }
            }
            .semantics {
                if (!controlsVisible) onClick(label = "Mostrar controles") {
                    controlsVisible = true
                    interactionEpoch++
                    true
                }
            }
    ) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (visualState?.activePresetId != null && projectMContent != null) projectMContent()
            else VoltikVisualizer(player.audioLevel, player.playing)
        }
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = EnterTransition.None,
            exit = slideOutVertically(animationSpec = tween(350), targetOffsetY = { it }) + fadeOut(animationSpec = tween(350))
        ) {
            Column(Modifier.fillMaxWidth().background(Ink.copy(alpha = 0.94f))) {
                Header("Tocando agora", back, Modifier.testTag("player-screen"))
                if (visualState != null && visualActions != null) VisualPicker(visualState, visualActions) { visualDialogOpen = it }
                if (!visualState?.message.isNullOrBlank()) Text(visualState!!.message!!, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 20.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                if (visualState?.activePresetId != null && projectMContent == null) Text("Visual importado indisponível; exibindo Órbita Voltik.", Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 20.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = EnterTransition.None,
            exit = slideOutVertically(animationSpec = tween(350), targetOffsetY = { it }) + fadeOut(animationSpec = tween(350))
        ) {
    Column(Modifier.fillMaxWidth().background(Ink.copy(alpha = 0.94f)).padding(horizontal = 24.dp)) {
        Text(player.title.ifBlank { "Nenhuma música" }, color = Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(player.artist, color = Secondary)
        val duration = player.durationMs.coerceAtLeast(0)
        Slider(value = if (duration > 0) (player.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f, onValueChange = { actions.seekTo((it * duration).toLong()) }, enabled = duration > 0)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(player.positionMs), color = Secondary, style = MaterialTheme.typography.bodySmall); Text(formatTime(duration), color = Secondary, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.toggleShuffle, modifier = Modifier.semantics { contentDescription = if (player.shuffle) "Desativar aleatório" else "Ativar aleatório" }) { Text("⤨", color = if (player.shuffle) Accent else Secondary, fontSize = 22.sp) }
            IconButton(onClick = actions.previous, modifier = Modifier.semantics { contentDescription = "Faixa anterior" }) { Text("⏮", color = Color.White, fontSize = 20.sp) }
            IconButton(onClick = actions.togglePlay, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(50)).background(Accent).semantics { contentDescription = if (player.playing) "Pausar" else "Tocar" }) { Text(if (player.playing) "Ⅱ" else "▶", color = Ink, fontSize = 22.sp) }
            IconButton(onClick = actions.next, modifier = Modifier.semantics { contentDescription = "Próxima faixa" }) { Text("⏭", color = Color.White, fontSize = 20.sp) }
            IconButton(onClick = actions.cycleRepeat, modifier = Modifier.semantics { contentDescription = when (player.repeatMode) { 1 -> "Repetindo faixa"; 2 -> "Repetindo álbum"; else -> "Repetição desligada" } }) { Text(if (player.repeatMode == 1) "↻¹" else "↻", color = if (player.repeatMode != 0) Accent else Secondary, fontSize = 22.sp) }
        }
    }
        }
    }
}

@Composable private fun Orbit(level: Float, playing: Boolean) {
    val transition = rememberInfiniteTransition(label = "orbita")
    val phase by transition.animateFloat(initialValue = 0f, targetValue = 360f, animationSpec = infiniteRepeatable(tween(18000), RepeatMode.Restart), label = "rotacao")
    val intensity = if (playing) level.coerceIn(0f, 1f) else 0f
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { rotationZ = if (playing) phase else 0f }) {
        val radius = min(size.width, size.height) * (0.27f + intensity * 0.09f)
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(Accent, radius, center.copy(x = center.x - radius * .16f), style = Stroke(width = 2.dp.toPx()))
        drawCircle(Color(0xFFD8D8DA), radius * .86f, center.copy(x = center.x + radius * .26f, y = center.y + radius * .14f), style = Stroke(width = 2.dp.toPx()))
        drawCircle(Color(0xFF83BFC3), radius * 1.12f, center.copy(y = center.y - radius * .13f), style = Stroke(width = 1.dp.toPx()))
    }
}

private fun formatTime(ms: Long): String { val s = ms.coerceAtLeast(0) / 1000; return "%d:%02d".format(s / 60, s % 60) }

