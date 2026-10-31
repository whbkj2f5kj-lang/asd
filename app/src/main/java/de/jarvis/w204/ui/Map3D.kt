package de.jarvis.w204.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jarvis.w204.MainViewModel
import de.jarvis.w204.map.Building
import de.jarvis.w204.map.Way
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Farben der Nachtkarte (an BMW/MBUX angelehnt) */
private object MapC {
    val skyTop = Color(0xFF05080F)
    val skyBottom = Color(0xFF0E1A2E)
    val ground = Color(0xFF0A1322)
    val roadMinor = Color(0xFF26364F)
    val roadMajor = Color(0xFF34496B)
    val roadMotorway = Color(0xFF3F5884)
    val casing = Color(0xFF0D1626)
    val wallA = Color(0xFF1B2A44)
    val wallB = Color(0xFF233656)
    val roof = Color(0xFF2E4670)
    val roofEdge = Color(0x664E6FA8)
    val current = Color(0xFF3D8BFF)
    val car = Color(0xFFF4F7FB)
}

/** Kartenausschnitt + Blickrichtung */
data class MapPose(val lat: Double, val lon: Double, val heading: Float)

/**
 * 3D-Kartenansicht für die Mitte: Straßen und Gebäude aus OpenStreetMap, Blick schräg von oben hinter dem Auto,
 * Fahrtrichtung oben. Keine Navigation – nur Orientierung (CarPlay macht den Rest).
 */
@Composable
fun MapView3D(vm: MainViewModel, active: Boolean, modifier: Modifier = Modifier) {
    val ms by vm.map.state.collectAsStateWithLifecycle()
    val limit by vm.speedLimit.collectAsStateWithLifecycle()
    var pose by remember { mutableStateOf<MapPose?>(null) }
    var ways by remember { mutableStateOf<List<Way>>(emptyList()) }
    var buildings by remember { mutableStateOf<List<Building>>(emptyList()) }

    DisposableEffect(active) {
        vm.map.wantBuildings = active
        onDispose { vm.map.wantBuildings = false }
    }

    // Position ~20× pro Sekunde weich nachführen (GPS kommt nur 1× pro Sekunde)
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var lastLoad = 0L
        while (true) {
            val s = vm.map.state.value
            val lat = s.lat
            val lon = s.lon
            if (lat != null && lon != null) {
                val now = SystemClock.elapsedRealtime()
                val dt = (now - s.fixAt).coerceIn(0L, 2000L) / 1000.0
                val v = (s.speedMs ?: 0f).toDouble()
                val hd = s.bearing ?: pose?.heading ?: 0f
                val r = Math.toRadians(hd.toDouble())
                val tLat = lat + v * dt * cos(r) / 110_540.0
                val tLon = lon + v * dt * sin(r) / (111_320.0 * cos(Math.toRadians(lat)))
                val p = pose
                val next = if (p == null || abs(p.lat - tLat) > 0.003 || abs(p.lon - tLon) > 0.004) MapPose(tLat, tLon, hd)
                else MapPose(p.lat + (tLat - p.lat) * 0.25, p.lon + (tLon - p.lon) * 0.25, lerpAngle(p.heading, hd, 0.15f))
                if (p == null || abs(next.lat - p.lat) > 1e-7 || abs(next.lon - p.lon) > 1e-7 || abs(next.heading - p.heading) > 0.05f) pose = next
                if (now - lastLoad > 2000) {
                    ways = vm.map.waysAround(next.lat, next.lon)
                    buildings = vm.map.buildingsAround(next.lat, next.lon)
                    lastLoad = now
                }
            }
            delay(50)
        }
    }

    Box(modifier.clip(RoundedCornerShape(24.dp))) {
        val p = pose
        if (p != null) {
            Map3DCanvas(ways, buildings, p, ms.roadKey, Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MapC.skyTop, MapC.skyBottom))))
        }

        // Straßenname + Limit
        Column(Modifier.align(Alignment.TopStart).padding(14.dp)) {
            SpeedLimitSign(limit, false, Modifier.size(54.dp))
        }
        ms.road?.let { road ->
            Text(
                road, color = J.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0xCC0B1424)).padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        // Nordpfeil
        if (p != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(14.dp).size(34.dp).clip(CircleShape).background(Color(0xCC0B1424)),
                contentAlignment = Alignment.Center,
            ) {
                Text("N", color = J.danger, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.rotate(-p.heading))
            }
        }
        val hint = when {
            p == null && !ms.running -> "Karte: GPS aus (Einstellungen)"
            p == null -> "Warte auf GPS …"
            ways.isEmpty() -> "Kartendaten werden geladen …"
            else -> null
        }
        hint?.let {
            Text(it, color = J.dim, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}

private fun lerpAngle(a: Float, b: Float, k: Float): Float {
    var d = (b - a) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return (a + d * k + 360f) % 360f
}

// ------------------------------------------------------------------------------- Zeichnen

/** Straßen/Gebäude in Meter um einen Bezugspunkt umgerechnet (einmal pro Datenstand) */
private class Prepared(
    val refLat: Double, val refLon: Double,
    val roads: List<PRoad>, val houses: List<PHouse>,
)
private class PRoad(val x: FloatArray, val y: FloatArray, val width: Float, val cls: Int, val key: String)
private class PHouse(val x: FloatArray, val y: FloatArray, val h: Float, val cx: Float, val cy: Float)

private fun roadStyle(hw: String): Pair<Float, Int>? = when (hw) {
    "motorway", "trunk" -> 15f to 2
    "motorway_link", "trunk_link", "primary" -> 11f to 1
    "primary_link", "secondary", "secondary_link" -> 10f to 1
    "tertiary", "tertiary_link" -> 8.5f to 0
    "unclassified", "residential", "road", "living_street" -> 6.5f to 0
    else -> null
}

private fun prepare(ways: List<Way>, buildings: List<Building>, refLat: Double, refLon: Double): Prepared {
    val kx = 111_320.0 * cos(Math.toRadians(refLat))
    val ky = 110_540.0
    val roads = ways.mapNotNull { w ->
        val (width, cls) = roadStyle(w.highway) ?: return@mapNotNull null
        PRoad(
            FloatArray(w.lon.size) { ((w.lon[it] - refLon) * kx).toFloat() },
            FloatArray(w.lat.size) { ((w.lat[it] - refLat) * ky).toFloat() },
            width, cls, w.roadKey,
        )
    }
    val houses = buildings.map { b ->
        val xs = FloatArray(b.lon.size) { ((b.lon[it] - refLon) * kx).toFloat() }
        val ys = FloatArray(b.lat.size) { ((b.lat[it] - refLat) * ky).toFloat() }
        PHouse(xs, ys, b.height, xs.average().toFloat(), ys.average().toFloat())
    }
    return Prepared(refLat, refLon, roads, houses)
}

/** Reine Zeichenfunktion (auch für Vorschaubilder ohne Gerät) */
@Composable
fun Map3DCanvas(ways: List<Way>, buildings: List<Building>, pose: MapPose, currentRoad: String?, modifier: Modifier = Modifier) {
    // Bezugspunkt nur bei neuen Daten oder großer Bewegung neu wählen
    val ref = remember(ways, buildings) { prepare(ways, buildings, pose.lat, pose.lon) }
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val kx = 111_320.0 * cos(Math.toRadians(ref.refLat))
        val ox = ((pose.lon - ref.refLon) * kx).toFloat()
        val oy = ((pose.lat - ref.refLat) * 110_540.0).toFloat()
        val h = Math.toRadians(pose.heading.toDouble())
        val sh = sin(h).toFloat()
        val ch = cos(h).toFloat()
        drawMap(ref, ox, oy, sh, ch, currentRoad)
    }
}

private const val CAM_H = 75f       // Kamerahöhe über dem Auto (m)
private const val CAM_D = 60f       // Abstand hinter dem Auto (m)
private const val PITCH = 0.84f     // Blickwinkel nach unten (rad, ~48°)
private const val F_MIN = -50f      // sichtbarer Bereich hinter dem Auto
private const val F_MAX = 750f      // … und davor

private fun DrawScope.drawMap(p: Prepared, ox: Float, oy: Float, sh: Float, ch: Float, currentRoad: String?) {
    val w = size.width
    val hgt = size.height
    val sp = sin(PITCH)
    val cp = cos(PITCH)
    val focal = max(w, hgt) * 0.75f
    val cx = w / 2f
    val cy = hgt * 0.78f

    // Welt (Meter, Ost/Nord) -> Auto (l = rechts, f = vorn)
    fun lf(x: Float, y: Float): Pair<Float, Float> {
        val px = x - ox
        val py = y - oy
        return (px * ch - py * sh) to (px * sh + py * ch)
    }
    fun proj(l: Float, f: Float, up: Float): Offset {
        val rz = f + CAM_D
        val ry = up - CAM_H
        val depth = max(1f, -ry * sp + rz * cp)
        val upc = ry * cp + rz * sp
        return Offset(cx + focal * l / depth, cy - focal * upc / depth)
    }

    // Himmel/Boden
    drawRect(Brush.verticalGradient(listOf(MapC.skyTop, MapC.skyBottom, MapC.ground), startY = 0f, endY = hgt))

    // ---------- Straßen: pro Klasse und Entfernung ein gemeinsamer Pfad (wenige Zeichenaufrufe)
    val bands = 3
    val casing = Array(bands) { Path() }
    val fills = Array(3) { Array(bands) { Path() } }
    val cur = Path()
    fun band(f: Float) = when { f < 180f -> 0; f < 420f -> 1; else -> 2 }

    for (r in p.roads) {
        var pl = 0f; var pf = 0f; var havePrev = false
        for (i in r.x.indices) {
            val (l, f) = lf(r.x[i], r.y[i])
            if (havePrev) {
                // Abschnitt auf den sichtbaren Bereich zuschneiden
                var l0 = pl; var f0 = pf; var l1 = l; var f1 = f
                val inRange = !((f0 < F_MIN && f1 < F_MIN) || (f0 > F_MAX && f1 > F_MAX) || (abs(l0) > 700f && abs(l1) > 700f))
                if (inRange) {
                    if (f0 < F_MIN) { val t = (F_MIN - f0) / (f1 - f0); l0 += (l1 - l0) * t; f0 = F_MIN }
                    if (f1 < F_MIN) { val t = (F_MIN - f1) / (f0 - f1); l1 += (l0 - l1) * t; f1 = F_MIN }
                    val dl = l1 - l0; val df = f1 - f0
                    val len = sqrt(dl * dl + df * df)
                    if (len > 0.3f) {
                        val nl = -df / len; val nf = dl / len
                        fun quad(path: Path, half: Float) {
                            val a = proj(l0 + nl * half, f0 + nf * half, 0f)
                            val b = proj(l1 + nl * half, f1 + nf * half, 0f)
                            val c = proj(l1 - nl * half, f1 - nf * half, 0f)
                            val d = proj(l0 - nl * half, f0 - nf * half, 0f)
                            path.moveTo(a.x, a.y); path.lineTo(b.x, b.y); path.lineTo(c.x, c.y); path.lineTo(d.x, d.y); path.close()
                        }
                        val bd = band((f0 + f1) / 2f)
                        quad(casing[bd], r.width / 2f + 1.2f)
                        quad(fills[r.cls][bd], r.width / 2f)
                        if (currentRoad != null && r.key == currentRoad) quad(cur, r.width / 2f * 0.55f)
                    }
                }
            }
            pl = l; pf = f; havePrev = true
        }
    }
    val fade = floatArrayOf(1f, 0.75f, 0.45f)
    for (b in 0 until bands) drawPath(casing[b], MapC.casing.copy(alpha = fade[b]))
    val roadCol = arrayOf(MapC.roadMinor, MapC.roadMajor, MapC.roadMotorway)
    for (c in 0..2) for (b in 0 until bands) drawPath(fills[c][b], roadCol[c].copy(alpha = fade[b]))
    // eigene Straße leuchtet (wie die Routenlinie bei BMW)
    drawPath(cur, MapC.current.copy(alpha = 0.35f), style = Stroke(width = 10f))
    drawPath(cur, MapC.current.copy(alpha = 0.9f))

    // ---------- Gebäude: von hinten nach vorn, nur Wände, die zur Kamera zeigen
    class Vis(val house: PHouse, val dist: Float)
    val vis = ArrayList<Vis>()
    for (b in p.houses) {
        val (l, f) = lf(b.cx, b.cy)
        if (f < -30f || f > 520f || abs(l) > 380f) continue
        vis += Vis(b, f * f + l * l)
    }
    vis.sortByDescending { it.dist }
    val maxHouses = 320
    val list = if (vis.size > maxHouses) vis.subList(vis.size - maxHouses, vis.size) else vis
    val walls = Path()
    val roof = Path()
    for (v in list) {
        val b = v.house
        val n = b.x.size
        val gl = FloatArray(n); val gf = FloatArray(n)
        for (i in 0 until n) { val (l, f) = lf(b.x[i], b.y[i]); gl[i] = l; gf[i] = f }
        val base = Array(n) { proj(gl[it], gf[it], 0f) }
        val top = Array(n) { proj(gl[it], gf[it], b.h) }
        val fogA = (1f - (sqrt(v.dist) / 560f)).coerceIn(0.25f, 1f)
        walls.reset()
        // Umlaufsinn des Grundrisses -> Außenseite jeder Wand
        var area = 0f
        for (i in 0 until n - 1) area += gl[i] * gf[i + 1] - gl[i + 1] * gf[i]
        val sgn = if (area >= 0f) 1f else -1f
        for (i in 0 until n - 1) {
            val dl = gl[i + 1] - gl[i]; val df = gf[i + 1] - gf[i]
            val ml = (gl[i] + gl[i + 1]) / 2f; val mf = (gf[i] + gf[i + 1]) / 2f
            // Wand nur zeichnen, wenn ihre Außenseite zur Kamera zeigt
            if ((df * sgn) * (0f - ml) + (-dl * sgn) * (-CAM_D - mf) <= 0f) continue
            val a = base[i]; val c = base[i + 1]; val d = top[i + 1]; val e = top[i]
            walls.moveTo(a.x, a.y); walls.lineTo(c.x, c.y); walls.lineTo(d.x, d.y); walls.lineTo(e.x, e.y); walls.close()
        }
        val wallCol = if ((b.cx.toInt() xor b.cy.toInt()) and 1 == 0) MapC.wallA else MapC.wallB
        drawPath(walls, wallCol.copy(alpha = fogA))
        roof.reset()
        for (i in 0 until n) if (i == 0) roof.moveTo(top[i].x, top[i].y) else roof.lineTo(top[i].x, top[i].y)
        roof.close()
        drawPath(roof, MapC.roof.copy(alpha = fogA))
        drawPath(roof, MapC.roofEdge.copy(alpha = 0.4f * fogA), style = Stroke(width = 1.2f))
    }

    // ---------- eigenes Auto: flacher Pfeil auf der Straße mit Lichtschein
    val c0 = proj(0f, 0f, 0f)
    drawCircle(
        Brush.radialGradient(listOf(MapC.current.copy(alpha = 0.45f), Color.Transparent), center = c0, radius = w * 0.06f),
        radius = w * 0.06f, center = c0,
    )
    val s = 2.6f
    val tip = proj(0f, 4.2f * s, 0.2f)
    val left = proj(-2.4f * s, -2.6f * s, 0.2f)
    val notch = proj(0f, -1.2f * s, 0.2f)
    val right = proj(2.4f * s, -2.6f * s, 0.2f)
    val arrow = Path().apply { moveTo(tip.x, tip.y); lineTo(right.x, right.y); lineTo(notch.x, notch.y); lineTo(left.x, left.y); close() }
    drawPath(arrow, MapC.car)
    drawPath(arrow, MapC.current, style = Stroke(width = 2f))

    // Ränder weich ausblenden (Karte schwebt im Cockpit)
    drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = 0f, endY = hgt * 0.22f), blendMode = BlendMode.DstOut)
    drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = 0f, endX = w * 0.08f), blendMode = BlendMode.DstOut)
    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = w * 0.92f, endX = w), blendMode = BlendMode.DstOut)
}
