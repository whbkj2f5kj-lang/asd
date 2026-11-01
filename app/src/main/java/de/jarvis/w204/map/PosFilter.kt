package de.jarvis.w204.map

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Glättet die Position und überbrückt Aussetzer.
 *
 * - Zwischen den GPS-Punkten (und wenn das GPS ganz ausfällt) wird mit dem Tempo aus dem OBD
 *   (Tacho, genauer und öfter als GPS) weitergerechnet – entlang der Straße, wenn eine zugeordnet ist.
 * - Neue GPS-Punkte werden je nach Genauigkeit eingemischt (ungenaue Punkte ziehen nur wenig).
 * - Einzelne Ausreißer (Sprünge) werden verworfen; erst wenn mehrere Punkte übereinstimmend
 *   woanders liegen, springt die Position.
 */
class PosFilter {
    enum class Source { GPS, NETWORK, DEAD_RECKONING, SAVED }

    data class Pose(
        val lat: Double,
        val lon: Double,
        /** Fahrtrichtung in Grad (0 = Nord) oder null, solange unbekannt */
        val heading: Float?,
        val speedMs: Float,
        /** geschätzte Ungenauigkeit in m */
        val acc: Float,
        /** monotone Zeit (ms) */
        val at: Long,
        val source: Source,
        /** Zeit der letzten verwendeten GPS-Messung */
        val lastFixAt: Long,
    )

    /** Weiterrechnen entlang einer Straße: (lat, lon, Richtung, Meter) -> neue Position + Richtung, oder null */
    fun interface Follower {
        fun advance(lat: Double, lon: Double, heading: Float, meters: Double): Triple<Double, Double, Float>?
    }

    var pose: Pose? = null
        private set
    private var outliers = 0

    /** Startwert ohne Fix, z. B. letzte gespeicherte Position (die Karte ist dann sofort da) */
    fun seed(lat: Double, lon: Double, heading: Float?, now: Long) {
        if (pose != null) return
        pose = Pose(lat, lon, heading, 0f, SEED_ACC, now, Source.SAVED, 0L)
    }

    /**
     * Bis [now] weiterrechnen.
     * @param obdSpeedMs Tempo laut Tacho (m/s) oder null
     * @param maxDeadMs so lange ohne GPS weiterrechnen, danach stehen bleiben
     */
    fun predict(now: Long, obdSpeedMs: Float?, follower: Follower?, maxDeadMs: Long = MAX_DEAD_MS): Pose? {
        val p = pose ?: return null
        val dtMs = now - p.at
        if (dtMs <= 0) return p
        val dt = dtMs / 1000.0
        val sinceFix = now - p.lastFixAt
        // ohne Tacho: GPS-Tempo nur kurz weiterverwenden
        val v = when {
            obdSpeedMs != null -> obdSpeedMs
            sinceFix < 3000 -> p.speedMs
            else -> 0f
        }
        val canMove = p.source != Source.SAVED && p.heading != null && sinceFix < maxDeadMs
        if (!canMove || v < 0.3f) {
            val src = if (p.source != Source.SAVED && sinceFix > GPS_STALE_MS) Source.DEAD_RECKONING else p.source
            pose = p.copy(at = now, speedMs = v, source = src, acc = p.acc + (if (p.source == Source.SAVED) 0f else (0.2 * dt).toFloat()))
            return pose
        }
        val d = v * dt
        val h = p.heading!!
        val moved = follower?.advance(p.lat, p.lon, h, d)
        val (lat, lon, hd) = moved ?: run {
            val r = Math.toRadians(h.toDouble())
            Triple(p.lat + d * cos(r) / KY, p.lon + d * sin(r) / (KX * cos(Math.toRadians(p.lat))), h)
        }
        // Unsicherheit wächst mit der Strecke (Richtung nicht exakt bekannt), auf der Straße langsamer
        val grow = (if (moved != null) 0.03 else 0.10) * d + 0.2 * dt
        val dead = sinceFix > GPS_STALE_MS
        pose = Pose(lat, lon, hd, v, (p.acc + grow).toFloat(), now, if (dead) Source.DEAD_RECKONING else p.source, p.lastFixAt)
        return pose
    }

    /**
     * Neue Messung einmischen.
     * @return true, wenn verwendet (false = als Ausreißer verworfen)
     */
    fun update(
        lat: Double, lon: Double, acc: Float, bearing: Float?, speedMs: Float?,
        gps: Boolean, at: Long, obdSpeedMs: Float?, follower: Follower?,
    ): Boolean {
        val fa = max(acc, 3f)
        val p0 = pose
        if (p0 == null || p0.source == Source.SAVED || at - p0.lastFixAt > RESET_MS && p0.source != Source.GPS) {
            outliers = 0
            pose = Pose(lat, lon, bearing?.takeIf { (speedMs ?: 0f) > HEADING_MIN_MS }, speedMs ?: 0f, fa, at,
                if (gps) Source.GPS else Source.NETWORK, at)
            return true
        }
        // ungenaue Netz-Position stört eine gute (geschätzte) Position nicht
        if (!gps && fa > p0.acc * 1.5f && at - p0.lastFixAt < RESET_MS) return false

        val p = predict(at, obdSpeedMs, follower) ?: p0
        val kx = KX * cos(Math.toRadians(p.lat))
        val dx = (lon - p.lon) * kx
        val dy = (lat - p.lat) * KY
        val dist = hypot(dx, dy)
        val gate = 3.0 * sqrt((p.acc * p.acc + fa * fa).toDouble()) + 15.0
        if (dist > gate) {
            outliers++
            // mehrfach woanders (oder Messung deutlich genauer als die Schätzung) -> übernehmen
            if (outliers < 3 && fa >= p.acc * 0.5f) return false
            outliers = 0
            pose = Pose(lat, lon, bearing?.takeIf { (speedMs ?: 0f) > HEADING_MIN_MS } ?: p.heading, speedMs ?: p.speedMs, fa, at,
                if (gps) Source.GPS else Source.NETWORK, at)
            return true
        }
        outliers = 0
        val pa2 = p.acc * p.acc
        val fa2 = fa * fa
        val k = pa2 / (pa2 + fa2)
        val nLat = p.lat + (lat - p.lat) * k
        val nLon = p.lon + (lon - p.lon) * k
        val nAcc = sqrt(pa2 * fa2 / (pa2 + fa2))

        // Richtung: GPS-Kurs, sobald man fährt; sonst aus der Bewegung zwischen den Messungen
        var hd = p.heading
        val sp = speedMs ?: obdSpeedMs ?: p.speedMs
        if (bearing != null && sp > HEADING_MIN_MS) {
            hd = if (hd == null) bearing else blendAngle(hd, bearing, 0.6f)
        } else if (dist > 12 && sp > HEADING_MIN_MS) {
            val mv = ((Math.toDegrees(atan2(dx, dy)) + 360) % 360).toFloat()
            hd = if (hd == null) mv else blendAngle(hd, mv, 0.4f)
        }
        pose = Pose(nLat, nLon, hd, speedMs ?: p.speedMs, nAcc, at, if (gps) Source.GPS else Source.NETWORK, if (gps) at else p.lastFixAt)
        return true
    }

    companion object {
        const val KX = 111_320.0
        const val KY = 110_540.0
        const val SEED_ACC = 300f
        /** ab hier gilt das GPS als ausgefallen (Koppelnavigation) */
        const val GPS_STALE_MS = 4_000L
        /** so lange wird ohne GPS mit dem Tacho weitergerechnet */
        const val MAX_DEAD_MS = 120_000L
        const val RESET_MS = 300_000L
        const val HEADING_MIN_MS = 2.0f

        fun blendAngle(a: Float, b: Float, k: Float): Float {
            var d = (b - a) % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return (a + d * k + 360f) % 360f
        }

        fun angleDiff(a: Float, b: Float): Float = abs(((a - b + 540f) % 360f) - 180f)

        @Suppress("unused")
        private fun clamp(v: Float, lo: Float, hi: Float) = min(hi, max(lo, v))
    }
}
