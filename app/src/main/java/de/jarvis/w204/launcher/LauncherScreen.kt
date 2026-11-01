package de.jarvis.w204.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.jarvis.w204.car.VehicleState
import de.jarvis.w204.ui.J
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    /** Name oben links (Profil) */
    val profile: String = "C 200 CDI",
    /** eigene Symbole (Dateiname ohne Endung -> Bild) */
    val customIcons: Map<String, ImageBitmap> = emptyMap(),
    /** Startseite der Symbolreihe */
    val page: Int = 0,
)

/** Was der Startbildschirm auslösen kann */
class LauncherActions(
    val openCockpit: (page: Int?) -> Unit = {},
    val openSettings: () -> Unit = {},
    val openDialog: (String) -> Unit = {},
    val launch: (AppEntry) -> Unit = {},
    val launchHome: (AppEntry) -> Unit = {},
    val assign: (Tile, AppEntry?) -> Unit = { _, _ -> },
    val resetTile: (Tile) -> Unit = {},
    val appInfo: (AppEntry) -> Unit = {},
    val mediaKey: (Int) -> Unit = {},
    val icon: (AppEntry) -> ImageBitmap? = { null },
    val toast: (String) -> Unit = {},
    val pageChanged: (Int) -> Unit = {},
)

/** Ein Eintrag der Symbolreihe */
private class Entry(
    /** Dateiname für eigene Symbole */
    val id: String,
    val title: String,
    val icon: MbIcon,
    /** dahinterliegende fremde App (lange drücken = andere wählen) */
    val tile: Tile?,
    val buttons: List<Pair<Glyph, () -> Unit>>,
    val open: () -> Unit,
)

/** Dateinamen für eigene Symbole (Einstellungen -> Startbildschirm) */
val ICON_IDS = listOf("navi", "medien", "telefon", "komfort", "fahrzeug", "video", "apps", "carplay", "info", "radio")

@Composable
private fun entries(s: LauncherState, a: LauncherActions, drawer: () -> Unit, assign: (Tile) -> Unit): List<Entry> {
    fun app(t: Tile, fallback: () -> Unit = { assign(t) }): () -> Unit = { s.tiles[t]?.let(a.launch) ?: fallback() }
    val prev = { a.mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
    val next = { a.mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT) }
    return listOf(
        Entry("navi", "Navigation", MbIcon.NAVI, Tile.NAVI, listOf(Glyph.MAP to { a.openCockpit(1) }, Glyph.HOME to app(Tile.NAVI)), app(Tile.NAVI)),
        Entry("medien", "Medien", MbIcon.MEDIA, Tile.MUSIC, listOf(Glyph.PREV to prev, Glyph.NEXT to next), app(Tile.MUSIC)),
        Entry("telefon", "Telefon", MbIcon.PHONE, Tile.PHONE, listOf(Glyph.CALLS to app(Tile.PHONE), Glyph.CONTACTS to app(Tile.PHONE)), app(Tile.PHONE)),
        Entry("komfort", "Komfort", MbIcon.COMFORT, Tile.SETTINGS, listOf(Glyph.GEAR to a.openSettings, Glyph.SOUND to app(Tile.SOUND, a.openSettings)), app(Tile.SETTINGS, a.openSettings)),
        Entry("fahrzeug", "Fahrzeug", MbIcon.CAR, null, listOf(Glyph.GAUGE to { a.openCockpit(0) }, Glyph.INFO to app(Tile.CAR) { a.openCockpit(2) }), { a.openCockpit(null) }),
        Entry("video", "Video", MbIcon.VIDEO, Tile.VIDEO, listOf(Glyph.PREV to prev, Glyph.NEXT to next), app(Tile.VIDEO)),
        Entry("apps", "Apps", MbIcon.APPS, null, listOf(Glyph.GRID to drawer, Glyph.GLOBE to drawer), drawer),
        Entry("carplay", "CarPlay", MbIcon.CARPLAY, Tile.CARPLAY, listOf(Glyph.LINK to app(Tile.CARPLAY), Glyph.PHONE to app(Tile.PHONE)), app(Tile.CARPLAY)),
        Entry("info", "Info", MbIcon.INFO, null, listOf(Glyph.TIRE to { a.openDialog("tires") }, Glyph.BARS to { a.openDialog("diag") }), { a.openCockpit(2) }),
        Entry("radio", "Radio", MbIcon.RADIO, Tile.RADIO, listOf(Glyph.INFO to app(Tile.RADIO), Glyph.PLAY to app(Tile.RADIO)), app(Tile.RADIO)),
    )
}

// ================================================================= Farben

private object Mb {
    val top = Color(0xFF020712)
    val deep = Color(0xFF041026)
    val horizon = Color(0xFF1C8FE8)
    val cyan = Color(0xFF39C6FF)
    val ring = Color(0xFFEAF4FF)
    val btn = Color(0xFF0E2A4E)
    val label = TextStyle(
        color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp,
        shadow = Shadow(Color(0xCC2AA8FF), Offset.Zero, 14f),
    )
}

// ================================================================= Startbildschirm

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherHome(
    s: LauncherState,
    a: LauncherActions,
    drawerOpen: Boolean,
    onDrawer: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    var assignFor by remember { mutableStateOf<Tile?>(null) }
    var search by remember { mutableStateOf(false) }
    BackHandler(enabled = drawerOpen || assignFor != null) {
        if (assignFor != null) assignFor = null else onDrawer(false)
    }
    val list = entries(s, a, drawer = { search = false; onDrawer(true) }, assign = { assignFor = it })
    val pages = list.chunked(5)
    val pager = rememberPagerState(initialPage = s.page.coerceIn(0, pages.lastIndex)) { pages.size }
    LaunchedEffect(pager.settledPage) { a.pageChanged(pager.settledPage) }

    // Einblenden beim Öffnen: Bühne leuchtet auf, Symbole steigen nacheinander auf
    val intro = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate) intro.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val pulse = if (animate) rememberInfiniteTransition(label = "stage").animateFloat(0.85f, 1f, infiniteRepeatable(tween(3800), RepeatMode.Reverse), label = "glow").value else 1f

    BoxWithConstraints(
        modifier.fillMaxSize().background(Mb.top)
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy < -80f) { search = false; onDrawer(true) } }) { _, d -> dy += d }
            }
    ) {
        val hgt = maxHeight
        val barH = 58.dp
        val horizonY = 0.56f
        Stage(Modifier.fillMaxSize(), horizonY, pulse * (0.4f + 0.6f * intro.value))

        Column(Modifier.fillMaxSize()) {
            TopBar(s, a, barH, onSearch = { search = true; onDrawer(true) })
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { pg ->
                Row(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
                    pages[pg].forEachIndexed { i, e ->
                        val delay = i * 0.08f
                        val t = ((intro.value - delay) / (1f - 0.32f)).coerceIn(0f, 1f)
                        EntryColumn(
                            e, s, a, Modifier.weight(1f).fillMaxHeight().graphicsLayer {
                                alpha = t
                                translationY = (1f - t) * 40f
                            },
                            iconBase = hgt * horizonY - barH,
                            onLong = { e.tile?.let { assignFor = it } },
                        )
                    }
                }
            }
            // Seitenpunkte + "Alle Apps" (wie "Suggestions" beim Original)
            Column(
                Modifier.fillMaxWidth().height(54.dp).clickable { search = false; onDrawer(true) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    repeat(pages.size) { i ->
                        val on = pager.currentPage == i
                        Box(Modifier.size(if (on) 8.dp else 6.dp).clip(CircleShape).background(if (on) Color.White else Color(0x88FFFFFF)))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("▾  ALLE APPS", color = Color(0xFFE6F0FA), fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            }
        }

        AnimatedVisibility(drawerOpen, enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut()) {
            AppDrawer(s, a, onClose = { onDrawer(false) }, onAssign = { assignFor = it }, search = search)
        }
        assignFor?.let { t -> AssignDialog(t, s, a, onClose = { assignFor = null }) }
    }
}

/** Hintergrund: Spotlight von oben, leuchtender Horizont, Punkteboden in Perspektive */
@Composable
private fun Stage(modifier: Modifier, horizonY: Float, glow: Float) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val hy = h * horizonY
        drawRect(Brush.verticalGradient(listOf(Mb.top, Mb.deep, Color(0xFF07264F)), 0f, hy))
        // Spotlight von oben in der Mitte
        drawRect(Brush.radialGradient(listOf(Color(0xFF1E9BFF).copy(alpha = 0.55f * glow), Color.Transparent), Offset(w * 0.5f, h * 0.12f), w * 0.36f))
        // Boden
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0B5DB4), Color(0xFF07336B), Color(0xFF020814)), hy, h), Offset(0f, hy), Size(w, h - hy))
        drawRect(Brush.radialGradient(listOf(Color(0xFF29A5FF).copy(alpha = 0.55f * glow), Color.Transparent), Offset(w * 0.5f, hy), w * 0.48f), Offset(0f, hy - h * 0.15f), Size(w, h * 0.5f))
        // Horizontlinie
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF8FD3FF).copy(alpha = 0.9f * glow), Color.Transparent)), Offset(0f, hy), Offset(w, hy), 2.2f)
        drawRect(Brush.verticalGradient(listOf(Color(0x5539C6FF), Color.Transparent), hy - h * 0.06f, hy), Offset(0f, hy - h * 0.06f), Size(w, h * 0.06f))
        // Punkteboden (weiter weg = kleiner, enger, blasser)
        val rows = 9
        for (r in 0 until rows) {
            val f = (r + 1f) / rows
            val y = hy + (h - hy) * (f * f) * 0.95f
            val gap = 10f + 34f * f
            val rad = 0.7f + 1.4f * f
            var x = (w / 2f) % gap
            while (x < w) {
                val side = 1f - (kotlin.math.abs(x - w / 2f) / (w / 2f))
                val a = (0.10f + 0.30f * f) * side
                if (a > 0.02f) drawCircle(Color(0xFFBFE6FF).copy(alpha = a), rad, Offset(x, y))
                x += gap
            }
        }
        // Ränder abdunkeln
        drawRect(Brush.horizontalGradient(listOf(Color(0xCC000000), Color.Transparent, Color.Transparent, Color(0xCC000000))))
    }
}

/** Kopfzeile mit schrägen Feldern links/rechts und Suchfeld in der Mitte */
@Composable
private fun TopBar(s: LauncherState, a: LauncherActions, h: Dp, onSearch: () -> Unit) {
    val date = remember { SimpleDateFormat("EE, d. MMM", Locale.GERMANY) }
    Box(Modifier.fillMaxWidth().height(h)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val hh = size.height
            val bg = Brush.verticalGradient(listOf(Color(0xFF0D2440), Color(0xFF071628)))
            val edge = Color(0xFF3FB6FF)
            val lw = w * 0.30f; val rw = w * 0.36f; val slant = hh * 0.9f
            val left = Path().apply { moveTo(0f, 0f); lineTo(lw + slant, 0f); lineTo(lw, hh); lineTo(0f, hh); close() }
            val right = Path().apply { moveTo(w, 0f); lineTo(w - rw - slant, 0f); lineTo(w - rw, hh); lineTo(w, hh); close() }
            drawPath(left, bg); drawPath(right, bg)
            // leuchtende Unterkante
            for ((x0, x1, x2) in listOf(Triple(0f, lw, lw + slant), Triple(w, w - rw, w - rw - slant))) {
                drawLine(edge, Offset(x0, hh - 1.5f), Offset(x1, hh - 1.5f), 3f)
                drawLine(edge, Offset(x1, hh - 1.5f), Offset(x2, 0f), 3f)
                drawLine(edge.copy(alpha = 0.25f), Offset(x0, hh + 2f), Offset(x1, hh + 2f), 8f)
            }
            // Trenner im linken Feld
            drawLine(edge.copy(alpha = 0.7f), Offset(w * 0.075f + slant * 0.6f, 0f), Offset(w * 0.075f, hh), 2f)
        }
        // links: Home + Profil
        Row(Modifier.align(Alignment.CenterStart).padding(start = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(28.dp).clickable { a.openCockpit(null) }) { drawGlyph(Glyph.HOME) }
            Spacer(Modifier.width(54.dp))
            Canvas(Modifier.size(22.dp)) { drawGlyph(Glyph.CONTACTS) }
            Spacer(Modifier.width(10.dp))
            Text(s.profile, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        // Mitte: Suchen
        Row(
            Modifier.align(Alignment.Center).width(240.dp).height(40.dp).clip(RoundedCornerShape(4.dp))
                .border(1.5.dp, Color(0x997FB8E6), RoundedCornerShape(4.dp)).background(Color(0x33041020))
                .clickable(onClick = onSearch),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(22.dp)) {
                drawCircle(Color.White, size.width * 0.3f, Offset(size.width * 0.58f, size.height * 0.4f), style = Stroke(size.width * 0.1f))
                drawLine(Color.White, Offset(size.width * 0.38f, size.height * 0.62f), Offset(size.width * 0.1f, size.height * 0.92f), size.width * 0.14f)
            }
            Spacer(Modifier.width(8.dp))
            Text("Suchen", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        // rechts: Uhr, Status, Schnellzugriffe
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.clock, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text(date.format(Date()), color = Color(0xFFB8C8D8), fontSize = 13.sp)
            Spacer(Modifier.width(14.dp))
            // OBD wie Empfangsbalken
            Canvas(Modifier.size(width = 20.dp, height = 16.dp)) {
                for (i in 0 until 4) {
                    val bh = size.height * (0.35f + i * 0.21f)
                    drawRect(if (s.obdLive) Color.White else Color(0x55FFFFFF), Offset(i * size.width / 4f, size.height - bh), Size(size.width / 6f, bh))
                }
            }
            s.vehicle.ambient?.let {
                Spacer(Modifier.width(12.dp))
                Text("${if (it > 0) "+" else ""}$it°", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            BarButton(Glyph.GAUGE) { a.openCockpit(null) }
            Spacer(Modifier.width(10.dp))
            BarButton(Glyph.GEAR) { a.openSettings() }
        }
    }
}

@Composable
private fun BarButton(g: Glyph, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).border(1.5.dp, Color(0xCCFFFFFF), RoundedCornerShape(6.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(18.dp)) { drawGlyph(g) } }
}

/** Eine Spalte: großes Symbol auf dem Horizont, Beschriftung, zwei runde Knöpfe */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryColumn(e: Entry, s: LauncherState, a: LauncherActions, modifier: Modifier, iconBase: Dp, onLong: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, tween(140), label = "press")
    BoxWithConstraints(modifier) {
        val iconSize = minOf(maxWidth * 0.66f, iconBase * 0.80f)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Symbol steht auf dem Horizont
            Box(
                Modifier.fillMaxWidth().height(iconBase)
                    .combinedClickable(interactionSource = src, indication = null, onClick = e.open, onLongClick = onLong),
                contentAlignment = Alignment.BottomCenter,
            ) {
                // Lichtschein am Fuß (stärker beim Drücken)
                Canvas(Modifier.width(iconSize * 1.3f).height(iconSize * 0.5f).offset(y = iconSize * 0.2f)) {
                    drawOval(Brush.radialGradient(listOf(Color(0xFF9FD8FF).copy(alpha = 0.35f + 0.4f * press), Color.Transparent), center, size.width / 2f))
                }
                val custom = s.customIcons[e.id]
                val m = Modifier.padding(bottom = iconSize * 0.04f).size(iconSize).graphicsLayer {
                    val sc = 1f - 0.06f * press
                    scaleX = sc; scaleY = sc
                }
                if (custom != null) Image(custom, contentDescription = e.title, modifier = m)
                else Canvas(m) { drawMbIcon(e.icon) }
            }
            Spacer(Modifier.height(14.dp))
            Text(e.title, style = Mb.label, maxLines = 1)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                e.buttons.forEach { (g, act) -> RoundKey(g, act) }
            }
        }
    }
}

@Composable
private fun RoundKey(g: Glyph, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier.size(50.dp).clip(CircleShape)
            .background(Brush.radialGradient(listOf(if (pressed) Color(0xFF3A86D8) else Color(0xFF1D4D86), Mb.btn)))
            .border(2.5.dp, Mb.ring, CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) { drawGlyph(g) }
    }
}

// ------------------------------------------------------------------------------- Alle Apps

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppDrawer(s: LauncherState, a: LauncherActions, onClose: () -> Unit, onAssign: (Tile) -> Unit, search: Boolean = false) {
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var query by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(search) { if (search) runCatching { focus.requestFocus() } }
    val shown = remember(s.apps, query) { if (query.isBlank()) s.apps else s.apps.filter { it.label.contains(query.trim(), ignoreCase = true) } }
    Column(
        Modifier.fillMaxSize().background(Color(0xFC05090F))
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy > 120f) onClose() }) { _, d -> dy += d }
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Alle Apps", color = J.text, fontSize = 22.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.width(20.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = J.text, fontSize = 16.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(J.accent),
                modifier = Modifier.weight(1f).focusRequester(focus).clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0x6639C6FF), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                decorationBox = { inner -> Box { if (query.isEmpty()) Text("Suchen …", color = J.dim, fontSize = 16.sp); inner() } },
            )
            Spacer(Modifier.width(12.dp))
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
            items(shown, key = { it.key }) { app ->
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
