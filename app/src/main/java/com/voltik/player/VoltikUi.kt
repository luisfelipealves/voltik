package com.voltik.player.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

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
    val setKeepAwake: (Boolean) -> Unit
)

private val Ink = Color(0xFF101115)
private val Panel = Color(0xFF24262B)
private val Accent = Color(0xFFC4F36A)
private val Secondary = Color(0xFFB1B3B5)
private val Border = Color(0xFF35373B)

@Composable
fun VoltikApp(
    albums: List<UiAlbum>, folders: List<UiFolder>, player: UiPlayer,
    keepAwake: Boolean, scanning: Boolean, message: String?, actions: UiActions
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

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel, onSurface = Color.White)) {
        Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding()) {
            when {
                showPlayer -> PlayerScreen(player, { showPlayer = false }, actions)
                settings -> SettingsScreen(folders, keepAwake, scanning, message, { settings = false }, actions)
                selectedAlbum != null -> AlbumScreen(selectedAlbum, { albumId = null }, actions)
                artist != null -> ArtistScreen(artist!!, filtered.filter { !it.isCompilation && it.artist == artist }, { artist = null }, { albumId = it })
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
                                Text("Adicione suas pastas de músicas nas configurações.", color = Secondary)
                                OutlinedButton(onClick = { settings = true }) { Text("Abrir configurações") }
                            }
                        } else if (query.isNotBlank()) {
                            SearchResults(filtered, query, { albumId = it }, actions)
                        } else when (tab) {
                            0 -> AlbumGrid(filtered, { albumId = it })
                            1 -> ArtistList(filtered.filterNot { it.isCompilation }.map { it.artist }.distinct().sortedBy { it.lowercase() }, { artist = it })
                            else -> AlbumGrid(filtered.filter { it.isCompilation }, { albumId = it })
                        }
                    }
                }
            }
            if (!message.isNullOrBlank()) Text(message, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 20.dp, vertical = 8.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (!showPlayer && player.title.isNotBlank()) MiniPlayer(player, { showPlayer = true }, actions.togglePlay)
        }
    }
}

@Composable private fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Voltar") }
        Text(title, modifier = Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable private fun Cover(title: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(Panel), contentAlignment = Alignment.Center) {
        Text(title.take(1).uppercase().ifBlank { "♫" }, fontSize = 38.sp, color = Accent)
    }
}

@Composable private fun AlbumGrid(albums: List<UiAlbum>, onOpen: (String) -> Unit) {
    if (albums.isEmpty()) { Text("Nenhum álbum encontrado.", Modifier.padding(24.dp), color = Secondary); return }
    LazyVerticalGrid(columns = GridCells.Adaptive(145.dp), contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items(albums, key = { it.id }) { album ->
            Column(Modifier.clickable { onOpen(album.id) }) {
                Cover(album.title, Modifier.fillMaxWidth().aspectRatio(1f))
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

@Composable private fun ColumnScope.ArtistScreen(artist: String, albums: List<UiAlbum>, back: () -> Unit, onOpen: (String) -> Unit) {
    Header("Artista", back)
    Text(artist, Modifier.padding(horizontal = 20.dp, vertical = 16.dp), color = Color.White, style = MaterialTheme.typography.headlineMedium)
    Text("Álbuns", Modifier.padding(horizontal = 20.dp), color = Secondary)
    Box(Modifier.weight(1f)) { AlbumGrid(albums, onOpen) }
}

@Composable private fun ColumnScope.AlbumScreen(album: UiAlbum, back: () -> Unit, actions: UiActions) {
    var menu by remember { mutableStateOf(false) }
    Header("Álbum", back)
    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(album.title, Modifier.size(112.dp))
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
    Text("Músicas", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Color.White, style = MaterialTheme.typography.titleMedium)
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp)) {
        items(album.songs.size) { index ->
            val song = album.songs[index]
            Row(Modifier.fillMaxWidth().clickable { actions.playSong(album, index) }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
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

@Composable private fun ColumnScope.PlayerScreen(player: UiPlayer, back: () -> Unit, actions: UiActions) {
    Header("Tocando agora", back)
    Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Orbit(player.audioLevel, player.playing) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
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
