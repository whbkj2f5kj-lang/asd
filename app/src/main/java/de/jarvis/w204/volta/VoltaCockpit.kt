package de.jarvis.w204.volta

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.jarvis.w204.car.VehicleState
import de.jarvis.w204.ui.J
import de.jarvis.w204.ui.SpeedLimitSign
import kotlinx.coroutines.delay
import java.util.Locale

/** Farben im Tesla/Volta-Stil: tiefes Schwarz, graue Karten, weiße Schrift */
private object V {
    val bg = Color(0xFF000000)
    val card = Color(0xFF16181C)
    val card2 = Color(0xFF202328)
    val line = Color(0xFF2A2D33)
    val text = Color(0xFFF2F3F5)
    val dim = Color(0xFF8D939C)
    val blue = Color(0xFF3E8BFF)
}

/** Daten, die das Volta-Cockpit braucht (ohne ViewModel – auch für Vorschaubilder) */
data class VoltaState(
    val v: VehicleState,
    val limit: Int?,
    val over: Boolean,
    val obdLive: Boolean,
    val clock: String,
    val driveMode: String?,
    val faults: Int,
    val track: Track?,
    val mediaAllowed: Boolean,
    val slots: Map<DockSlot, DockApp?>,
    val apps: List<DockApp>,
    val carVersion: Int,
    val hasCar: Boolean,
)

class VoltaActions(
    val onSettings: () -> Unit = {},
    val onDiagnose: () -> Unit = {},
    val onMic: () -> Unit = {},
    val onLimitTap: () -> Unit = {},
    val launch: (DockApp) -> Unit = {},
    val assign: (DockSlot, DockApp?) -> Unit = { _, _ -> },
    val resetSlot: (DockSlot) -> Unit = {},
    val icon: (DockApp) -> ImageBitmap? = { null },
    val playPause: () -> Unit = {},
    val next: () -> Unit = {},
    val previous: () -> Unit = {},
    val mediaAccess: () -> Unit = {},
    val carLoaded: (String?) -> Unit = {},
)

@Composable
fun VoltaCockpit(
    s: VoltaState,
    a: VoltaActions,
    extra: @Composable () -> Unit = {},
    right: @Composable BoxScope.() -> Unit,
) {
    var assignFor by remember { mutableStateOf<DockSlot?>(null) }
    var showApps by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(V.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                LeftColumn(s, a, Modifier.width(170.dp).fillMaxHeight())
                Box(Modifier.weight(1.15f).fillMaxHeight()) {
                    CarStage(s, a, Modifier.fillMaxSize())
                    MusicCard(s, a, Modifier.align(Alignment.BottomCenter).padding(start = 10.dp, end = 10.dp, bottom = 10.dp).fillMaxWidth())
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp, end = 8.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Color(0xFF0B0D10)),
                    content = right,
                )
            }
            Dock(s, a, extra, onLong = { assignFor = it }, onAll = { showApps = true })
        }
        if (showApps) AllApps(s, a, onClose = { showApps = false })
        assignFor?.let { slot -> AssignSheet(slot, s, a, onClose = { assignFor = null }) }
    }
}

// ------------------------------------------------------------------------------- links

@Composable
private fun LeftColumn(s: VoltaState, a: VoltaActions, modifier: Modifier) {
    val v = s.v
    Row(modifier.padding(start = 18.dp, top = 14.dp, bottom = 14.dp)) {
        // dünner Tempo-Balken wie im Vorbild
        Canvas(Modifier.width(3.dp).fillMaxHeight(0.62f)) {
            drawLine(V.line, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), size.width, StrokeCap.Round)
            val f = ((v.speed ?: 0) / 200f).coerceIn(0f, 1f)
            if (f > 0f) drawLine(Color.White, Offset(size.width / 2, size.height), Offset(size.width / 2, size.height * (1 - f)), size.width, StrokeCap.Round)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                s.driveMode?.uppercase(Locale.GERMANY) ?: "D", color = V.dim, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp,
            )
            Text(v.speed?.toString() ?: "–", color = V.text, fontSize = 64.sp, fontWeight = FontWeight.Light, lineHeight = 66.sp)
            Text("km/h", color = V.dim, fontSize = 15.sp)
            Spacer(Modifier.height(14.dp))
            SpeedLimitSign(s.limit, s.over, Modifier.size(52.dp).clickable(onClick = a.onLimitTap))
            Spacer(Modifier.height(14.dp))
            SmallValue(v.rpm?.let { String.format(Locale.GERMANY, "%,d", it) }, "U/min")
            SmallValue(v.coolant?.toString(), "°C Motor")
            v.boostBar?.let { SmallValue(String.format(Locale.GERMANY, "%.1f", it), "bar") }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (s.obdLive) Color(0xFF34D399) else V.dim))
                Spacer(Modifier.width(6.dp))
                Text(if (s.obdLive) "OBD" else "OBD aus", color = V.dim, fontSize = 12.sp)
                if (s.faults > 0) {
                    Spacer(Modifier.width(10.dp))
                    Text("⚠ ${s.faults}", color = J.warn, fontSize = 12.sp, modifier = Modifier.clickable(onClick = a.onDiagnose))
                }
            }
        }
    }
}

@Composable
private fun SmallValue(value: String?, unit: String) {
    if (value == null) return
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(value, color = V.text, fontSize = 17.sp)
        Spacer(Modifier.width(5.dp))
        Text(unit, color = V.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
    }
}

// ------------------------------------------------------------------------------- Mitte: 3D-Auto

@Composable
private fun CarStage(s: VoltaState, a: VoltaActions, modifier: Modifier) {
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) a.carLoaded(CarModel.import(ctx, uri))
    }
    Box(modifier) {
        Car3D(s.carVersion, Modifier.fillMaxSize().padding(bottom = 96.dp))
        if (!s.hasCar) {
            Column(Modifier.align(Alignment.Center).padding(bottom = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CarSilhouette(Modifier.width(260.dp).height(90.dp))
                Spacer(Modifier.height(10.dp))
                Text("Eigenes 3D-Modell laden (.glb)", color = V.dim, fontSize = 14.sp)
                Text(
                    "Modell wählen", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).background(V.text)
                        .clickable { pick.launch(arrayOf("*/*")) }.padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** schlichte Limousinen-Silhouette als Platzhalter */
@Composable
private fun CarSilhouette(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val p = Path().apply {
            moveTo(w * 0.04f, h * 0.78f); lineTo(w * 0.05f, h * 0.55f)
            quadraticBezierTo(w * 0.12f, h * 0.46f, w * 0.27f, h * 0.42f)
            quadraticBezierTo(w * 0.36f, h * 0.12f, w * 0.52f, h * 0.10f)
            quadraticBezierTo(w * 0.68f, h * 0.10f, w * 0.80f, h * 0.36f)
            quadraticBezierTo(w * 0.95f, h * 0.42f, w * 0.97f, h * 0.60f)
            lineTo(w * 0.96f, h * 0.78f); close()
        }
        drawPath(p, V.dim.copy(alpha = 0.5f), style = Stroke(2.5f))
        drawCircle(V.dim.copy(alpha = 0.5f), h * 0.15f, Offset(w * 0.24f, h * 0.80f), style = Stroke(2.5f))
        drawCircle(V.dim.copy(alpha = 0.5f), h * 0.15f, Offset(w * 0.78f, h * 0.80f), style = Stroke(2.5f))
    }
}

// ------------------------------------------------------------------------------- Musik-Widget

@Composable
private fun MusicCard(s: VoltaState, a: VoltaActions, modifier: Modifier) {
    val t = s.track
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(V.card.copy(alpha = 0.94f))) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(V.card2), contentAlignment = Alignment.Center) {
                val art = t?.art
                if (art != null) Image(remember(art) { art.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Canvas(Modifier.size(22.dp)) { drawNote(V.dim) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                when {
                    !s.mediaAllowed -> {
                        Text("Musik-Anzeige", color = V.text, fontSize = 16.sp)
                        Text("Zugriff erlauben", color = V.blue, fontSize = 13.sp, modifier = Modifier.clickable(onClick = a.mediaAccess))
                    }
                    t == null -> {
                        Text("Keine Wiedergabe", color = V.text, fontSize = 16.sp)
                        Text("Musik in einer App starten", color = V.dim, fontSize = 13.sp)
                    }
                    else -> {
                        Text(t.title.ifBlank { "Unbekannter Titel" }, color = V.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(t.artist, t.app).filter { it.isNotBlank() }.joinToString(" · "), color = V.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Ctrl(Glyph.PREV, a.previous)
            Ctrl(if (t?.playing == true) Glyph.PAUSE else Glyph.PLAY, a.playPause, big = true)
            Ctrl(Glyph.NEXT, a.next)
        }
        // Fortschritt
        var now by remember { mutableLongStateOf(0L) }
        LaunchedEffect(t) { while (true) { now = t?.positionNow() ?: 0L; delay(500) } }
        val f = if (t != null && t.durationMs > 0) (now.toFloat() / t.durationMs).coerceIn(0f, 1f) else 0f
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            drawRect(V.line)
            drawRect(Color.White, size = Size(size.width * f, size.height))
        }
    }
}

private enum class Glyph { PREV, NEXT, PLAY, PAUSE }

@Composable
private fun Ctrl(g: Glyph, onClick: () -> Unit, big: Boolean = false) {
    Box(Modifier.size(if (big) 48.dp else 42.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(if (big) 22.dp else 18.dp)) {
            val w = size.width; val h = size.height; val c = V.text
            when (g) {
                Glyph.PLAY -> drawPath(Path().apply { moveTo(w * 0.18f, 0f); lineTo(w, h / 2); lineTo(w * 0.18f, h); close() }, c)
                Glyph.PAUSE -> { drawRect(c, Offset(w * 0.15f, 0f), Size(w * 0.24f, h)); drawRect(c, Offset(w * 0.61f, 0f), Size(w * 0.24f, h)) }
                Glyph.PREV -> { drawRect(c, Offset(0f, 0f), Size(w * 0.14f, h)); drawPath(Path().apply { moveTo(w, 0f); lineTo(w * 0.2f, h / 2); lineTo(w, h); close() }, c) }
                Glyph.NEXT -> { drawRect(c, Offset(w * 0.86f, 0f), Size(w * 0.14f, h)); drawPath(Path().apply { moveTo(0f, 0f); lineTo(w * 0.8f, h / 2); lineTo(0f, h); close() }, c) }
            }
        }
    }
}

private fun DrawScope.drawNote(c: Color) {
    val w = size.width; val h = size.height
    drawLine(c, Offset(w * 0.7f, h * 0.1f), Offset(w * 0.7f, h * 0.75f), w * 0.1f)
    drawLine(c, Offset(w * 0.7f, h * 0.1f), Offset(w * 0.95f, h * 0.25f), w * 0.1f)
    drawOval(c, Offset(w * 0.3f, h * 0.62f), Size(w * 0.45f, h * 0.32f))
}

// ------------------------------------------------------------------------------- App-Leiste

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Dock(s: VoltaState, a: VoltaActions, extra: @Composable () -> Unit, onLong: (DockSlot) -> Unit, onAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(V.bg).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Auto-Symbol = Einstellungen (wie im Vorbild)
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = a.onSettings), contentAlignment = Alignment.Center) {
            Canvas(Modifier.width(30.dp).height(20.dp)) { drawCarIcon(V.text) }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            DockSlot.entries.forEach { slot ->
                val app = s.slots[slot]
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = { app?.let(a.launch) ?: onLong(slot) }, onLongClick = { onLong(slot) }),
                    contentAlignment = Alignment.Center,
                ) {
                    val ic = app?.let { remember(it.key) { a.icon(it) } }
                    if (ic != null) Image(ic, slot.title, Modifier.size(38.dp))
                    else Text(slot.title.take(1), color = V.dim, fontSize = 18.sp,
                        modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(V.card2).padding(top = 7.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onAll), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(24.dp)) {
                    val c = size.width * 0.42f
                    for (i in 0..1) for (j in 0..1) drawRoundRect(V.text, Offset(i * size.width * 0.58f, j * size.height * 0.58f), Size(c, c), CornerRadius(c * 0.25f))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            extra()
            Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = a.onMic), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(20.dp)) {
                    val w = size.width; val h = size.height
                    drawRoundRect(V.text, Offset(w * 0.32f, 0f), Size(w * 0.36f, h * 0.62f), CornerRadius(w * 0.18f))
                    drawArc(V.text, 0f, 180f, false, Offset(w * 0.14f, h * 0.22f), Size(w * 0.72f, h * 0.55f), style = Stroke(w * 0.09f))
                    drawLine(V.text, Offset(w / 2, h * 0.78f), Offset(w / 2, h), w * 0.09f)
                }
            }
            Text(s.clock, color = V.text, fontSize = 17.sp)
        }
    }
}

private fun DrawScope.drawCarIcon(c: Color) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.05f, h * 0.85f); lineTo(w * 0.05f, h * 0.5f); lineTo(w * 0.2f, h * 0.45f)
        lineTo(w * 0.32f, h * 0.1f); lineTo(w * 0.68f, h * 0.1f); lineTo(w * 0.8f, h * 0.45f)
        lineTo(w * 0.95f, h * 0.5f); lineTo(w * 0.95f, h * 0.85f); close()
    }
    drawPath(p, c, style = Stroke(w * 0.06f))
    drawCircle(c, h * 0.12f, Offset(w * 0.27f, h * 0.68f))
    drawCircle(c, h * 0.12f, Offset(w * 0.73f, h * 0.68f))
}

@Composable
private fun AssignSheet(slot: DockSlot, s: VoltaState, a: VoltaActions, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(Modifier.width(440.dp).fillMaxHeight(0.86f).clip(RoundedCornerShape(16.dp)).background(V.card).clickable(enabled = false) {}.padding(16.dp)) {
            Text("App für „${slot.title}“", color = V.text, fontSize = 19.sp)
            Row {
                TextButton(onClick = { a.resetSlot(slot); onClose() }) { Text("Automatisch", color = V.blue) }
                TextButton(onClick = { a.assign(slot, null); onClose() }) { Text("Leer", color = V.dim) }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(s.apps, key = { it.key }) { app ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (s.slots[slot]?.key == app.key) V.card2 else Color.Transparent)
                            .clickable { a.assign(slot, app); onClose() }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val ic = remember(app.key) { a.icon(app) }
                        if (ic != null) Image(ic, null, Modifier.size(34.dp)) else Spacer(Modifier.size(34.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(app.label, color = V.text, fontSize = 15.sp)
                            Text(app.pkg, color = V.dim, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllApps(s: VoltaState, a: VoltaActions, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(Color(0xF2000000)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Apps", color = V.text, fontSize = 22.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("SCHLIESSEN", color = V.dim) }
        }
        LazyVerticalGrid(GridCells.Adaptive(104.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(s.apps, key = { it.key }) { app ->
                Column(
                    Modifier.padding(4.dp).clip(RoundedCornerShape(12.dp)).clickable { a.launch(app); onClose() }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val ic = remember(app.key) { a.icon(app) }
                    if (ic != null) Image(ic, null, Modifier.size(52.dp)) else Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(V.card2))
                    Text(app.label, color = V.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp))
                }
            }
        }
    }
}
