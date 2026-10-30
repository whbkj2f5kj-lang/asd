package de.jarvis.w204.vision

import java.util.Random
import kotlin.math.abs

/**
 * Sucht helle, schmale Markierungen (weiß/gelb) unterhalb des Horizonts und legt per RANSAC
 * je eine Gerade für die linke und rechte Spurlinie hinein.
 */
class LaneDetector {
    private val rnd = Random(7)
    private var left: LaneLine? = null
    private var right: LaneLine? = null
    private var leftSeen = 0L
    private var rightSeen = 0L

    fun detect(px: IntArray, w: Int, h: Int, horizon: Float, now: Long): Lanes {
        val y0 = ((horizon + 0.05f) * h).toInt().coerceIn(1, h - 2)
        val y1 = (h * 0.92f).toInt().coerceIn(y0 + 1, h - 1)
        val ptsL = ArrayList<FloatArray>()
        val ptsR = ArrayList<FloatArray>()
        val g = IntArray(w)

        var y = y0
        while (y < y1) {
            val row = y * w
            for (x in 0 until w) {
                val c = px[row + x]
                g[x] = (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
            }
            val t = (y - y0).toFloat() / (y1 - y0)
            val d = (2 + t * w * 0.025f).toInt()      // Linien werden nach unten breiter
            var runStart = -1
            for (x in d until w - d) {
                val v = g[x]
                val isMark = v > 110 && v - g[x - d] > 30 && v - g[x + d] > 30
                if (isMark && runStart < 0) runStart = x
                if ((!isMark || x == w - d - 1) && runStart >= 0) {
                    if (x - runStart <= d * 2 + 2) {
                        val cx = (runStart + x - 1) / 2f
                        val p = floatArrayOf(cx / w, y.toFloat() / h)
                        if (cx < w / 2f) ptsL += p else ptsR += p
                    }
                    runStart = -1
                }
            }
            y += 3
        }

        val l = fit(ptsL)?.takeIf { it.m < -0.15f }
        val r = fit(ptsR)?.takeIf { it.m > 0.15f }
        if (l != null) { left = blend(left, l, now - leftSeen < 800); leftSeen = now }
        if (r != null) { right = blend(right, r, now - rightSeen < 800); rightSeen = now }

        return Lanes(
            left = left?.takeIf { now - leftSeen < 1000 },
            right = right?.takeIf { now - rightSeen < 1000 },
        )
    }

    private fun blend(old: LaneLine?, new: LaneLine, recent: Boolean): LaneLine =
        if (old == null || !recent) new
        else LaneLine(old.m + (new.m - old.m) * 0.35f, old.c + (new.c - old.c) * 0.35f)

    private fun fit(pts: List<FloatArray>): LaneLine? {
        if (pts.size < 12) return null
        var bestM = 0f
        var bestC = 0f
        var bestN = 0
        repeat(80) {
            val a = pts[rnd.nextInt(pts.size)]
            val b = pts[rnd.nextInt(pts.size)]
            val dy = b[1] - a[1]
            if (abs(dy) < 0.05f) return@repeat
            val m = (b[0] - a[0]) / dy
            val c = a[0] - m * a[1]
            var n = 0
            for (p in pts) if (abs(m * p[1] + c - p[0]) < 0.012f) n++
            if (n > bestN) { bestN = n; bestM = m; bestC = c }
        }
        if (bestN < 10) return null

        // Feinanpassung (kleinste Quadrate) über alle passenden Punkte
        var sy = 0.0; var sx = 0.0; var syy = 0.0; var sxy = 0.0; var n = 0
        for (p in pts) {
            if (abs(bestM * p[1] + bestC - p[0]) < 0.012f) {
                sy += p[1]; sx += p[0]; syy += p[1] * p[1]; sxy += p[0] * p[1]; n++
            }
        }
        val den = n * syy - sy * sy
        if (abs(den) < 1e-9) return LaneLine(bestM, bestC)
        val m = ((n * sxy - sx * sy) / den).toFloat()
        val c = ((sx - m * sy) / n).toFloat()
        return LaneLine(m, c)
    }
}
