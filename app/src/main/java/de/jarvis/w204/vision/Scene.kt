package de.jarvis.w204.vision

import kotlin.math.abs
import kotlin.math.tan

/** Einbau der Frontkamera. horizon = Bildhöhe des Horizonts (0 = oben, 1 = unten). */
data class Calibration(val heightM: Float = 1.25f, val horizon: Float = 0.5f, val fovDeg: Float = 90f)

enum class ObjKind(val widthM: Float, val heightM: Float) {
    CAR(1.8f, 1.45f),
    TRUCK(2.5f, 3.2f),
    MOTORBIKE(0.8f, 1.5f),
    BICYCLE(0.6f, 1.6f),
    PERSON(0.5f, 1.75f),
}

/** Erkanntes Objekt, Box normiert (0..1) aufs Kamerabild. */
data class Detection(val kind: ObjKind, val l: Float, val t: Float, val r: Float, val b: Float, val score: Float)

/** Spurlinie im Bild, normiert: x = m * y + c */
data class LaneLine(val m: Float, val c: Float) {
    fun x(y: Float) = m * y + c
}

data class Lanes(val left: LaneLine? = null, val right: LaneLine? = null)

data class VisionFrame(
    val width: Int,
    val height: Int,
    val signs: List<SignBox> = emptyList(),
    val objects: List<Detection> = emptyList(),
    val lanes: Lanes = Lanes(),
)

/** Objekt in Fahrzeugkoordinaten: x = seitlich (m, rechts positiv), z = Abstand nach vorn (m). */
data class SceneObject(val id: Int, val kind: ObjKind, val x: Float, val z: Float, val lead: Boolean)

data class Scene(
    val objects: List<SceneObject> = emptyList(),
    val laneLeft: Float = -1.75f,
    val laneRight: Float = 1.75f,
    val laneDetected: Boolean = false,
    val leadDistance: Float? = null,
)

/** Rechnet Bildpunkte auf die Straßenebene um (Lochkamera, ebene Straße). */
class Projector(private val cal: Calibration, private val w: Int, private val h: Int) {
    private val f = (w / 2f) / tan(Math.toRadians(cal.fovDeg / 2.0).toFloat())
    private val yh = cal.horizon * h

    /** Abstand eines Bodenpunkts in Bildzeile v (Pixel); null über dem Horizont. */
    fun groundZ(v: Float): Float? {
        val dv = v - yh
        return if (dv < 2f) null else f * cal.heightM / dv
    }

    fun depthFromWidth(px: Float, meters: Float): Float = f * meters / px

    fun lateral(u: Float, z: Float): Float = (u - w / 2f) * z / f

    fun laneX(line: LaneLine, z: Float): Float {
        val v = yh + f * cal.heightM / z
        val u = line.x(v / h) * w
        return lateral(u, z)
    }
}

/** Verfolgt Objekte über mehrere Bilder (ruhige Anzeige) und bestimmt das vorausfahrende Fahrzeug. */
class SceneBuilder {
    private class Track(val id: Int, var kind: ObjKind, var x: Float, var z: Float, var seen: Long, var hits: Int)

    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private var laneL = -1.75f
    private var laneR = 1.75f
    private var laneSeen = 0L
    private var lead: Float? = null

    fun update(frame: VisionFrame, cal: Calibration, now: Long): Scene {
        val p = Projector(cal, frame.width, frame.height)

        // --- Fahrspur ---
        val l = frame.lanes.left
        val r = frame.lanes.right
        val xl = l?.let { (p.laneX(it, 8f) + p.laneX(it, 14f)) / 2f }?.takeIf { it in -3.5f..-0.3f }
        val xr = r?.let { (p.laneX(it, 8f) + p.laneX(it, 14f)) / 2f }?.takeIf { it in 0.3f..3.5f }
        when {
            xl != null && xr != null && (xr - xl) in 2.4f..4.8f -> {
                laneL = ema(laneL, xl, 0.3f); laneR = ema(laneR, xr, 0.3f); laneSeen = now
            }
            xl != null -> {
                laneL = ema(laneL, xl, 0.3f); laneR = ema(laneR, xl + 3.5f, 0.3f); laneSeen = now
            }
            xr != null -> {
                laneR = ema(laneR, xr, 0.3f); laneL = ema(laneL, xr - 3.5f, 0.3f); laneSeen = now
            }
        }
        val laneDetected = now - laneSeen < 1500
        if (!laneDetected) {
            laneL = ema(laneL, -1.75f, 0.05f)
            laneR = ema(laneR, 1.75f, 0.05f)
        }

        // --- Objekte in Straßenkoordinaten ---
        val measured = frame.objects.mapNotNull { d ->
            if (d.r - d.l > 0.85f) return@mapNotNull null          // eigene Motorhaube o. Ä.
            val u = (d.l + d.r) / 2f * frame.width
            val wpx = (d.r - d.l) * frame.width
            val zw = if (wpx > 4f) p.depthFromWidth(wpx, d.kind.widthM) else null
            val zg = if (d.b < 0.98f) p.groundZ(d.b * frame.height) else null
            val z = when {
                zg != null && zw != null -> (zg + zw) / 2f
                zg != null -> zg
                zw != null -> zw
                else -> return@mapNotNull null
            }.coerceIn(2f, 150f)
            Triple(d.kind, p.lateral(u, z), z)
        }

        // --- Zuordnung zu bestehenden Tracks ---
        val used = BooleanArray(tracks.size)
        val fresh = ArrayList<Track>()
        for ((kind, x, z) in measured) {
            var bestI = -1
            var bestD = Float.MAX_VALUE
            for (i in tracks.indices) {
                if (used[i]) continue
                val t = tracks[i]
                val dist = abs(t.x - x) + abs(t.z - z) * 0.25f
                if (dist < bestD) { bestD = dist; bestI = i }
            }
            if (bestI >= 0 && bestD < 2.5f) {
                val t = tracks[bestI]
                used[bestI] = true
                t.x = ema(t.x, x, 0.45f)
                t.z = ema(t.z, z, 0.45f)
                t.kind = kind
                t.seen = now
                t.hits++
            } else {
                fresh += Track(nextId++, kind, x, z, now, 1)
            }
        }
        tracks += fresh
        tracks.removeAll { now - it.seen > 700 }

        val visible = tracks.filter { it.hits >= 2 }
        val leadTrack = visible
            .filter { it.kind != ObjKind.PERSON && it.x in (laneL + 0.2f)..(laneR - 0.2f) }
            .minByOrNull { it.z }
        val old = lead
        lead = leadTrack?.let { if (old == null) it.z else ema(old, it.z, 0.4f) }

        return Scene(
            objects = visible.map { SceneObject(it.id, it.kind, it.x, it.z, it.id == leadTrack?.id) },
            laneLeft = laneL,
            laneRight = laneR,
            laneDetected = laneDetected,
            leadDistance = lead,
        )
    }

    private fun ema(a: Float, b: Float, k: Float) = a + (b - a) * k
}
