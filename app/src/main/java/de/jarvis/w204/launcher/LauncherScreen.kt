package de.jarvis.w204.launcher

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.jarvis.w204.car.VehicleState
import de.jarvis.w204.ui.J
import de.jarvis.w204.ui.LocalGaugePalette
import de.jarvis.w204.ui.SpeedLimitSign
import de.jarvis.w204.ui.glass
import de.jarvis.w204.ui.rememberNoiseBrush
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Alles, was der Startbildschirm anzeigt (ohne ViewModel – auch für Vorschaubilder) */
data class LauncherState(
    val vehicle: VehicleState,
    val limit: Int?,
    val over: Boolean,
    val obdText: String,
    val obdLive: Boolean,
    val clock: String,
    val tiles: Map<Tile, AppEntry?>,
    val apps: List<AppEntry>,
    val homes: List<AppEntry> = emptyList(),
)

/** Was der Startbildschirm auslösen kann */
class LauncherActions(
    val openCockpit: (page: Int?) -> Unit = {},
    val openSettings: () -> Unit = {},
    val launch: (AppEntry) -> Unit = {},
    val launchHome: (AppEntry) -> Unit = {},
    val assign: (Tile, AppEntry?) -> Unit = { _, _ -> },
    val resetTile: (Tile) -> Unit = {},
    val appInfo: (AppEntry) -> Unit = {},
    val mediaKey: (Int) -> Unit = {},
    val icon: (AppEntry) -> ImageBitmap? = { null },
    val toast: (String) -> Unit = {},
)

private val TILE_ORDER = listOf(Tile.COCKPIT, Tile.CARPLAY, Tile.NAVI, Tile.MUSIC, Tile.PHONE, Tile.CAR, Tile.VIDEO, Tile.SETTINGS, Tile.APPS)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherHome(
    s: LauncherState,
    a: LauncherActions,
    drawerOpen: Boolean,
    onDrawer: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGaugePalette.current
    val noise = rememberNoiseBrush()
    var assignFor by remember { mutableStateOf<Tile?>(null) }

    BackHandler(enabled = drawerOpen || assignFor != null) {
        if (assignFor != null) assignFor = null else onDrawer(false)
    }

    BoxWithConstraints(
        modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF02050A), Color(0xFF061224), Color(0xFF02060C))))
            .drawBehind {
                // "Lichtbühne" hinter den Kacheln wie beim Original, in der Farbwelt des Cockpits
                drawRect(Brush.radialGradient(listOf(Color(0x332A6CFF), Color.Transparent), center = Offset(size.width * 0.5f, size.height * 0.62f), radius = size.width * 0.55f))
                drawRect(Brush.radialGradient(listOf(palette.glow.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width * 0.5f, size.height * 1.05f), radius = size.width * 0.45f))
                // Horizontlinie
                val y = size.height * 0.80f
                drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x553FA9FF), Color.Transparent)), Offset(0f, y), Offset(size.width, y), 1.5f)
                drawRect(noise)
            }
            // Wischen nach oben = alle Apps
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy < -80f) onDrawer(true) }) { _, d -> dy += d }
            }
    ) {
        val fullW = maxWidth
        // wie beim Original: fünf Kacheln nebeneinander, der Rest per Wischen
        val gap = 8.dp
        val tileW = ((fullW - 48.dp - gap * 4) / 5).coerceIn(140.dp, 320.dp)
        Column(Modifier.fillMaxSize()) {
            HomeTopBar(s, a)
            val list = rememberLazyListState()
            val snap = rememberSnapFlingBehavior(list)
            LazyRow(
                state = list,
                flingBehavior = snap,
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                itemsIndexed(TILE_ORDER) { i, t ->
                    val focus = 1f
                    LauncherTile(
                        tile = t, app = s.tiles[t], s = s, a = a, focus = focus,
                        modifier = Modifier.width(tileW).fillMaxHeight(),
                        onOpen = {
                            when (t) {
                                Tile.COCKPIT -> a.openCockpit(null)
                                Tile.APPS -> onDrawer(true)
                                else -> {
                                    val app = s.tiles[t]
                                    if (app != null) a.launch(app) else assignFor = t
                                }
                            }
                        },
                        onLong = { if (t.assignable) assignFor = t },
                    )
                }
            }
            // Griff: alle Apps
            Column(
                Modifier.fillMaxWidth().height(40.dp).clickable { onDrawer(true) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.width(44.dp).height(4.dp).clip(RoundedCornerShape(50)).background(Color(0x55FFFFFF)))
                Text("Alle Apps", color = J.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }

        AnimatedVisibility(drawerOpen, enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut()) {
            AppDrawer(s, a, onClose = { onDrawer(false) }, onAssign = { assignFor = it })
        }
        assignFor?.let { t ->
            AssignDialog(t, s, a, onClose = { assignFor = null })
        }
    }
}

// ------------------------------------------------------------------------------- Kopfzeile

@Composable
private fun HomeTopBar(s: LauncherState, a: LauncherActions) {
    val date = remember { SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY) }
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.clock, color = J.text, fontSize = 30.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.width(14.dp))
        Text(date.format(Date()), color = J.dim, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        s.vehicle.ambient?.let { Chip("${if (it > 0) "+" else ""}$it °C") }
        s.vehicle.voltage?.let { Chip(String.format(Locale.GERMANY, "%.1f V", it)) }
        Row(
            Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).glass(RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (s.obdLive) J.ok else J.dim))
            Spacer(Modifier.width(7.dp))
            Text(s.obdText, color = J.dim, fontSize = 13.sp, maxLines = 1)
        }
        Box(
            Modifier.padding(start = 10.dp).size(40.dp).clip(CircleShape).clickable(onClick = a.openSettings),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Settings, contentDescription = "Einstellungen", tint = J.dim) }
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text, color = J.text, fontSize = 13.sp,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).glass(RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ------------------------------------------------------------------------------- Kachel

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherTile(
    tile: Tile, app: AppEntry?, s: LauncherState, a: LauncherActions, focus: Float,
    modifier: Modifier, onOpen: () -> Unit, onLong: () -> Unit,
) {
    val palette = LocalGaugePalette.current
    val accent = if (tile == Tile.COCKPIT) palette.glow else J.accent
    Column(
        modifier.graphicsLayer {
            val sc = 0.86f + 0.14f * focus
            scaleX = sc; scaleY = sc
            alpha = 0.55f + 0.45f * focus
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.12f))
        // Bild + Titel (antippen = öffnen, lange drücken = andere App wählen)
        Column(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .combinedClickable(onClick = onOpen, onLongClick = onLong)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Lichtkegel unter dem Symbol
                Canvas(Modifier.fillMaxSize()) {
                    drawOval(
                        Brush.radialGradient(listOf(accent.copy(alpha = 0.20f), Color.Transparent), center = Offset(size.width / 2, size.height * 0.86f), radius = size.width * 0.4f),
                        topLeft = Offset(size.width * 0.1f, size.height * 0.72f), size = Size(size.width * 0.8f, size.height * 0.28f),
                    )
                }
                if (tile == Tile.COCKPIT) CockpitMini(s, Modifier.fillMaxSize())
                else TileGlyph(tile, accent, Modifier.fillMaxSize(0.62f))
            }
            Text(tile.title, color = J.text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                when {
                    tile == Tile.COCKPIT -> "Tacho · Karte · Werte"
                    tile == Tile.APPS -> "${s.apps.size} Apps"
                    app != null -> app.label
                    else -> "lange drücken: App wählen"
                },
                color = J.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // kleine Schnellknöpfe darunter (wie beim Original)
        Row(
            Modifier.height(64.dp).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (tile) {
                Tile.COCKPIT -> {
                    RoundButton("Karte", { drawPin(it) }) { a.openCockpit(1) }
                    RoundButton("Werte", { drawBars(it) }) { a.openCockpit(2) }
                }
                Tile.MUSIC -> {
                    RoundButton("Zurück", { drawSkip(it, back = true) }) { a.mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
                    RoundButton("Pause", { drawPlayPause(it) }) { a.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
                    RoundButton("Weiter", { drawSkip(it, back = false) }) { a.mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
                }
                Tile.SETTINGS -> {
                    RoundButton("Cockpit", { drawGauge(it) }) { a.openSettings() }
                }
                else -> Spacer(Modifier.size(1.dp))
            }
        }
        Spacer(Modifier.weight(0.08f))
    }
}

@Composable
private fun RoundButton(label: String, glyph: DrawScope.(Color) -> Unit, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0x22FFFFFF), Color(0x0AFFFFFF))))
            .border(1.5.dp, Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x33FFFFFF))), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) { glyph(Color(0xFFEAF2FA)) }
    }
}

/** Live-Vorschau des Cockpits in der Kachel */
@Composable
private fun CockpitMini(s: LauncherState, modifier: Modifier) {
    val palette = LocalGaugePalette.current
    val v = s.vehicle
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize(0.92f)) {
            // Drehzahl-Bogen
            val r = size.minDimension / 2f * 0.92f
            val c = Offset(size.width / 2, size.height / 2)
            val tl = Offset(c.x - r, c.y - r)
            val sz = Size(r * 2, r * 2)
            drawArc(Color(0x22FFFFFF), 135f, 270f, false, tl, sz, style = Stroke(r * 0.07f, cap = StrokeCap.Round))
            val frac = ((v.rpm ?: 0) / 5000f).coerceIn(0f, 1f)
            if (frac > 0f) drawArc(
                Brush.sweepGradient(listOf(palette.deep, palette.mid, palette.light), c),
                135f, 270f * frac, false, tl, sz, style = Stroke(r * 0.07f, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(v.speed?.toString() ?: "–", color = J.text, fontSize = 46.sp, fontWeight = FontWeight.Light, lineHeight = 46.sp)
            Text("km/h", color = J.dim, fontSize = 12.sp)
        }
        if (s.limit != null) SpeedLimitSign(s.limit, s.over, Modifier.align(Alignment.TopEnd).size(40.dp))
    }
}

// ------------------------------------------------------------------------------- Symbole

@Composable
private fun TileGlyph(tile: Tile, accent: Color, modifier: Modifier) {
    val icon: ImageVector? = when (tile) {
        Tile.PHONE -> Icons.Filled.Phone
        Tile.NAVI -> null
        Tile.VIDEO -> Icons.Filled.PlayArrow
        Tile.SETTINGS -> Icons.Filled.Settings
        else -> null
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (icon != null && tile != Tile.VIDEO) {
            Icon(icon, contentDescription = null, tint = Color(0xFFE8F1FA), modifier = Modifier.fillMaxSize(0.72f))
        } else {
            Canvas(Modifier.fillMaxSize()) {
                when (tile) {
                    Tile.NAVI -> drawNavArrow(accent)
                    Tile.MUSIC -> drawNote(accent)
                    Tile.CARPLAY -> drawCarPlay(accent)
                    Tile.CAR -> drawCar()
                    Tile.VIDEO -> drawVideo(accent)
                    Tile.APPS -> drawGrid(accent)
                    else -> {}
                }
            }
        }
    }
}

private val GLYPH = Color(0xFFE8F1FA)

private fun DrawScope.drawNavArrow(accent: Color) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.5f, h * 0.08f); lineTo(w * 0.82f, h * 0.9f); lineTo(w * 0.5f, h * 0.7f); lineTo(w * 0.18f, h * 0.9f); close()
    }
    drawPath(p, Brush.verticalGradient(listOf(Color.White, accent, Color(0xFF1C4FD8))))
    val shade = Path().apply { moveTo(w * 0.5f, h * 0.08f); lineTo(w * 0.82f, h * 0.9f); lineTo(w * 0.5f, h * 0.7f); close() }
    drawPath(shade, Color(0x33000000))
}

private fun DrawScope.drawNote(accent: Color) {
    val w = size.width; val h = size.height
    val st = Stroke(w * 0.06f, cap = StrokeCap.Round)
    val br = Brush.verticalGradient(listOf(Color.White, accent))
    drawLine(br, Offset(w * 0.40f, h * 0.72f), Offset(w * 0.40f, h * 0.16f), st.width, StrokeCap.Round)
    drawLine(br, Offset(w * 0.78f, h * 0.64f), Offset(w * 0.78f, h * 0.08f), st.width, StrokeCap.Round)
    drawLine(br, Offset(w * 0.40f, h * 0.16f), Offset(w * 0.78f, h * 0.08f), w * 0.09f, StrokeCap.Round)
    drawOval(br, Offset(w * 0.16f, h * 0.64f), Size(w * 0.27f, h * 0.2f))
    drawOval(br, Offset(w * 0.54f, h * 0.56f), Size(w * 0.27f, h * 0.2f))
}

private fun DrawScope.drawCarPlay(accent: Color) {
    val w = size.width; val h = size.height
    drawRoundRect(
        Brush.linearGradient(listOf(Color.White, accent), Offset.Zero, Offset(w, h)),
        Offset(w * 0.1f, h * 0.1f), Size(w * 0.8f, h * 0.8f), androidx.compose.ui.geometry.CornerRadius(w * 0.2f),
        style = Stroke(w * 0.06f),
    )
    val p = Path().apply { moveTo(w * 0.42f, h * 0.33f); lineTo(w * 0.68f, h * 0.5f); lineTo(w * 0.42f, h * 0.67f); close() }
    drawPath(p, GLYPH)
}

private fun DrawScope.drawCar() {
    // Front-Ansicht, Linien wie beim Original (eigene Zeichnung)
    val w = size.width; val h = size.height
    val st = Stroke(w * 0.035f, cap = StrokeCap.Round)
    val body = Path().apply {
        moveTo(w * 0.06f, h * 0.70f); lineTo(w * 0.06f, h * 0.52f)
        quadraticBezierTo(w * 0.08f, h * 0.44f, w * 0.18f, h * 0.42f)
        lineTo(w * 0.28f, h * 0.24f); quadraticBezierTo(w * 0.5f, h * 0.18f, w * 0.72f, h * 0.24f)
        lineTo(w * 0.82f, h * 0.42f); quadraticBezierTo(w * 0.92f, h * 0.44f, w * 0.94f, h * 0.52f)
        lineTo(w * 0.94f, h * 0.70f); close()
    }
    drawPath(body, Brush.verticalGradient(listOf(Color(0x44FFFFFF), Color(0x11FFFFFF))))
    drawPath(body, GLYPH, style = st)
    drawLine(GLYPH, Offset(w * 0.25f, h * 0.42f), Offset(w * 0.75f, h * 0.42f), st.width)
    drawRoundRect(GLYPH, Offset(w * 0.12f, h * 0.50f), Size(w * 0.16f, h * 0.07f), androidx.compose.ui.geometry.CornerRadius(w * 0.03f))
    drawRoundRect(GLYPH, Offset(w * 0.72f, h * 0.50f), Size(w * 0.16f, h * 0.07f), androidx.compose.ui.geometry.CornerRadius(w * 0.03f))
    drawRoundRect(GLYPH, Offset(w * 0.36f, h * 0.52f), Size(w * 0.28f, h * 0.1f), androidx.compose.ui.geometry.CornerRadius(w * 0.02f), style = Stroke(w * 0.025f))
    drawRoundRect(GLYPH, Offset(w * 0.10f, h * 0.70f), Size(w * 0.12f, h * 0.10f), androidx.compose.ui.geometry.CornerRadius(w * 0.02f))
    drawRoundRect(GLYPH, Offset(w * 0.78f, h * 0.70f), Size(w * 0.12f, h * 0.10f), androidx.compose.ui.geometry.CornerRadius(w * 0.02f))
}

private fun DrawScope.drawVideo(accent: Color) {
    val w = size.width; val h = size.height
    drawRoundRect(Brush.linearGradient(listOf(Color.White, accent), Offset.Zero, Offset(w, h)), Offset(w * 0.06f, h * 0.22f), Size(w * 0.88f, h * 0.56f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = Stroke(w * 0.05f))
    val p = Path().apply { moveTo(w * 0.42f, h * 0.36f); lineTo(w * 0.64f, h * 0.5f); lineTo(w * 0.42f, h * 0.64f); close() }
    drawPath(p, GLYPH)
}

private fun DrawScope.drawGrid(accent: Color) {
    val w = size.width
    val cell = w * 0.24f
    val gap = (w - cell * 3) / 2f
    for (i in 0 until 3) for (j in 0 until 3) {
        drawRoundRect(
            if ((i + j) % 2 == 0) Brush.linearGradient(listOf(Color.White, accent)) else Brush.linearGradient(listOf(accent, Color(0xFF1C4FD8))),
            Offset(i * (cell + gap), j * (cell + gap)), Size(cell, cell), androidx.compose.ui.geometry.CornerRadius(cell * 0.28f),
        )
    }
}

private fun DrawScope.drawSkip(c: Color, back: Boolean) {
    val w = size.width; val h = size.height
    val p = Path()
    if (back) { p.moveTo(w * 0.85f, h * 0.15f); p.lineTo(w * 0.3f, h * 0.5f); p.lineTo(w * 0.85f, h * 0.85f); p.close() }
    else { p.moveTo(w * 0.15f, h * 0.15f); p.lineTo(w * 0.7f, h * 0.5f); p.lineTo(w * 0.15f, h * 0.85f); p.close() }
    drawPath(p, c)
    val x = if (back) w * 0.2f else w * 0.8f
    drawLine(c, Offset(x, h * 0.15f), Offset(x, h * 0.85f), w * 0.12f)
}

private fun DrawScope.drawPlayPause(c: Color) {
    val w = size.width; val h = size.height
    val p = Path().apply { moveTo(w * 0.05f, h * 0.15f); lineTo(w * 0.5f, h * 0.5f); lineTo(w * 0.05f, h * 0.85f); close() }
    drawPath(p, c)
    drawLine(c, Offset(w * 0.68f, h * 0.18f), Offset(w * 0.68f, h * 0.82f), w * 0.12f)
    drawLine(c, Offset(w * 0.9f, h * 0.18f), Offset(w * 0.9f, h * 0.82f), w * 0.12f)
}

private fun DrawScope.drawPin(c: Color) {
    val w = size.width; val h = size.height
    drawCircle(c, w * 0.3f, Offset(w * 0.5f, h * 0.38f), style = Stroke(w * 0.12f))
    val p = Path().apply { moveTo(w * 0.24f, h * 0.55f); lineTo(w * 0.5f, h * 0.95f); lineTo(w * 0.76f, h * 0.55f); close() }
    drawPath(p, c)
}

private fun DrawScope.drawBars(c: Color) {
    val w = size.width; val h = size.height
    listOf(0.5f, 0.8f, 0.35f, 0.65f).forEachIndexed { i, f ->
        drawLine(c, Offset(w * (0.14f + i * 0.24f), h * 0.92f), Offset(w * (0.14f + i * 0.24f), h * (0.92f - f * 0.84f)), w * 0.13f, StrokeCap.Round)
    }
}

private fun DrawScope.drawGauge(c: Color) {
    val w = size.width
    drawArc(c, 150f, 240f, false, style = Stroke(w * 0.12f, cap = StrokeCap.Round))
    drawLine(c, Offset(w * 0.5f, w * 0.5f), Offset(w * 0.78f, w * 0.26f), w * 0.12f, StrokeCap.Round)
}

// ------------------------------------------------------------------------------- Alle Apps

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppDrawer(s: LauncherState, a: LauncherActions, onClose: () -> Unit, onAssign: (Tile) -> Unit) {
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFC05090F))
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy > 120f) onClose() }) { _, d -> dy += d }
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Alle Apps", color = J.text, fontSize = 22.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
            s.homes.forEach { h ->
                TextButton(onClick = { a.launchHome(h) }) { Text("Original-Startbildschirm", color = J.accent, fontSize = 14.sp) }
            }
            TextButton(onClick = onClose) { Text("SCHLIESSEN", color = J.dim, fontSize = 14.sp) }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(s.apps, key = { it.key }) { app ->
                Column(
                    Modifier.padding(4.dp).clip(RoundedCornerShape(16.dp))
                        .combinedClickable(onClick = { a.launch(app) }, onLongClick = { menuFor = app })
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppIcon(app, a, 58.dp)
                    Text(
                        app.label, color = J.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
                    )
                }
            }
        }
    }
    menuFor?.let { app ->
        BackHandler { menuFor = null }
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { menuFor = null }, contentAlignment = Alignment.Center) {
            Column(Modifier.width(360.dp).clip(RoundedCornerShape(20.dp)).background(J.panel2).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app, a, 40.dp)
                    Text(app.label, color = J.text, fontSize = 17.sp, modifier = Modifier.padding(start = 12.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text("Auf Kachel legen:", color = J.dim, fontSize = 13.sp)
                Tile.entries.filter { it.assignable }.chunked(3).forEach { row ->
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { t ->
                            Text(
                                t.title, color = if (s.tiles[t]?.key == app.key) Color.Black else J.text, fontSize = 13.sp,
                                modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(if (s.tiles[t]?.key == app.key) J.accent else J.track)
                                    .clickable { a.assign(t, app); a.toast("${app.label} liegt jetzt auf „${t.title}“"); menuFor = null }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    TextButton(onClick = { a.appInfo(app); menuFor = null }) { Text("App-Info", color = J.accent) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { menuFor = null }) { Text("Abbrechen", color = J.dim) }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(app: AppEntry, a: LauncherActions, size: Dp) {
    val bmp = remember(app.key) { a.icon(app) }
    if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(size))
    else Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(J.track), contentAlignment = Alignment.Center) {
        Text(app.label.take(1).uppercase(), color = J.text, fontSize = 22.sp)
    }
}

/** Kachel einer anderen App zuordnen */
@Composable
private fun AssignDialog(t: Tile, s: LauncherState, a: LauncherActions, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(Modifier.width(440.dp).fillMaxHeight(0.86f).clip(RoundedCornerShape(20.dp)).background(J.panel2).clickable(enabled = false) {}.padding(16.dp)) {
            Text("App für „${t.title}“", color = J.text, fontSize = 19.sp)
            Row {
                TextButton(onClick = { a.resetTile(t); onClose() }) { Text("Automatisch", color = J.accent) }
                TextButton(onClick = { a.assign(t, null); onClose() }) { Text("Keine", color = J.dim) }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(s.apps, key = { it.key }) { app ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (s.tiles[t]?.key == app.key) Color(0x2239C6FF) else Color.Transparent)
                            .clickable { a.assign(t, app); onClose() }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app, a, 36.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(app.label, color = J.text, fontSize = 15.sp)
                            Text(app.pkg, color = J.dim, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
