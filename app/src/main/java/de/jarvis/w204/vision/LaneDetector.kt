package de.jarvis.w204.vision

import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Spurerkennung, Stufe 1 (klassisch, ohne KI):
 *  1. Helle, schmale Markierungen zeilenweise suchen – Schwellen passen sich der Straßenhelligkeit an (Nacht)
 *  2. Gelbe Markierungen erkennen: sind genug da (Baustelle), gelten nur noch die gelben
 *  3. Punkte auf die Straße umrechnen (Meter) und links/rechts zuordnen
 *  4. Je Seite eine Linie per RANSAC, dann gemeinsames Modell beider Linien (parallel, gemeinsame Krümmung):
 *     x(z) = a + b·z + c·z²   (x seitlich, z nach vorn, Meter)
 *  5. Plausibilität: Spurbreite 2,3–4,8 m, Krümmung/Richtung begrenzt
 *  6. Glättung über die Zeit; fehlt eine Linie, wird sie über die gemerkte Spurbreite ergänzt
 */
class LaneDetector {
    private val rnd = Random(7)

    private class State(
        var aL: Float, var aR: Float, var b: Float, var c: Float, var width: Float,
        var seenL: Long, var seenR: Long, var seenAny: Long,
    ) {
        fun xL(z: Float) = aL + b * z + c * z * z
        fun xR(z: Float) = aR + b * z + c * z * z
    }

    private var st: State? = null
    private var yellowScore = 0f

    private class Pt(val x: Float, val z: Float, val yellow: Boolean)

    fun detect(px: IntArray, w: Int, h: Int, cal: Calibration, now: Long): Lanes {
        val f = (w / 2f) / tan(Math.toRadians(cal.fovDeg / 2.0).toFloat())
        val yh = cal.horizon * h
        val hm = cal.heightM
        val y0 = max((yh + f * hm / Z_FAR).toInt(), (yh + 2).toInt()).coerceIn(1, h - 2)
        val y1 = min((yh + f * hm / Z_NEAR).toInt(), (h * 0.95f).toInt()).coerceIn(y0 + 1, h - 1)

        // ---- 1+2) Markierungspunkte
        val pts = ArrayList<Pt>()
        val g = IntArray(w)
        var y = y0
        while (y < y1) {
            val row = y * w
            var sum = 0
            var cnt = 0
            for (x in 0 until w) {
                val c = px[row + x]
                g[x] = (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
                if (x in w / 6 until w * 5 / 6) { sum += g[x]; cnt++ }
            }
            val road = if (cnt > 0) sum / cnt else 80
            val tAbs = max(25, (road * 0.35f).toInt())
            val tEdge = max(18, (road * 0.25f).toInt())
            val z = f * hm / (y - yh)
            val markPx = 0.15f * f / z                        // erwartete Linienbreite in Pixeln
            val d = (2 + markPx * 1.5f).toInt().coerceIn(2, w / 8)
            val maxRun = (markPx * 3f + 3).toInt()
            var runStart = -1
            for (x in d until w - d) {
                val v = g[x]
                val isMark = v > road + tAbs && v - g[x - d] > tEdge && v - g[x + d] > tEdge
                if (isMark && runStart < 0) runStart = x
                if ((!isMark || x == w - d - 1) && runStart >= 0) {
                    if (x - runStart <= maxRun) {
                        val cx = (runStart + x - 1) / 2f
                        val c = px[row + cx.toInt()]
                        val r = (c shr 16) and 0xFF; val gg = (c shr 8) and 0xFF; val b = c and 0xFF
                        val yellow = min(r, gg) - b > 40 && r > 80 && gg > 70 && abs(r - gg) < 90
                        pts += Pt((cx - w / 2f) * z / f, z, yellow)
                    }
                    runStart = -1
                }
            }
            y += 2
        }

        // Baustellenmodus mit Verzögerung (nicht bei jedem gelben Fleck umschalten)
        val nYellow = pts.count { it.yellow }
        val yellowNow = nYellow >= 16 && nYellow >= pts.size * 0.3f
        yellowScore += ((if (yellowNow) 1f else 0f) - yellowScore) * 0.2f
        val yellowMode = yellowScore > 0.5f
        val use = if (yellowMode) pts.filter { it.yellow } else pts

        // ---- 3) links/rechts zuordnen
        val prev = st?.takeIf { now - it.seenAny < 1000 }
        val left = ArrayList<Pt>()
        val right = ArrayList<Pt>()
        for (p in use) {
            if (prev != null) {
                val dl = abs(p.x - prev.xL(p.z))
                val dr = abs(p.x - prev.xR(p.z))
                val tol = 0.6f + 0.02f * p.z
                if (dl < dr && dl < tol) left += p else if (dr <= dl && dr < tol) right += p
            } else {
                if (p.x in -2.6f..-0.2f) left += p else if (p.x in 0.2f..2.6f) right += p
            }
        }

        // ---- 4) Linien finden
        val inL = ransac(left)
        val inR = ransac(right)
        var meas = joint(inL, inR)
        // ---- 5) Plausibilität
        if (meas != null && meas.hasL && meas.hasR && (meas.aR - meas.aL) !in 2.3f..4.8f) {
            meas = if (inL.size >= inR.size) joint(inL, emptyList()) else joint(emptyList(), inR)
        }
        if (meas != null && (abs(meas.b) > 0.35f || abs(meas.c) > 0.012f)) meas = null

        // ---- 6) Glättung
        if (meas != null) update(meas, now)
        val s = st
        if (s == null || now - s.seenAny > 1000) {
            return Lanes()
        }
        // Spurwechsel: eine Linie liegt jetzt auf der anderen Seite des Autos -> Linien neu zuordnen
        if (s.aL > -0.1f) { s.aR = s.aL; s.aL -= s.width }
        else if (s.aR < 0.1f) { s.aL = s.aR; s.aR += s.width }

        val model = LaneModel(
            left = s.aL, right = s.aR, heading = s.b, curve = s.c,
            leftSeen = now - s.seenL < 1000, rightSeen = now - s.seenR < 1000, yellow = yellowMode,
        )
        return Lanes(
            left = if (model.leftSeen) imageLine(s.aL, s, w, h, f, yh, hm) else null,
            right = if (model.rightSeen) imageLine(s.aR, s, w, h, f, yh, hm) else null,
            model = model,
        )
    }

    /** Gerade im Kamerabild (für die Anzeige über dem Kamerabild) aus dem Straßenmodell */
    private fun imageLine(a: Float, s: State, w: Int, h: Int, f: Float, yh: Float, hm: Float): LaneLine {
        fun uv(z: Float): Pair<Float, Float> {
            val x = a + s.b * z + s.c * z * z
            return (w / 2f + x * f / z) / w to (yh + f * hm / z) / h
        }
        val (u1, v1) = uv(6f)
        val (u2, v2) = uv(20f)
        val m = (u2 - u1) / (v2 - v1)
        return LaneLine(m, u1 - m * v1)
    }

    private class Meas(val aL: Float, val aR: Float, val b: Float, val c: Float, val hasL: Boolean, val hasR: Boolean)

    private fun update(m: Meas, now: Long) {
        val s = st
        if (s == null || now - s.seenAny > 1000) {
            val width = s?.width ?: 3.5f
            st = State(
                aL = if (m.hasL) m.aL else m.aR - width,
                aR = if (m.hasR) m.aR else m.aL + width,
                b = m.b, c = m.c, width = width,
                seenL = if (m.hasL) now else NEVER, seenR = if (m.hasR) now else NEVER, seenAny = now,
            )
            return
        }
        val kPos = 0.45f
        if (m.hasL) { s.aL += (m.aL - s.aL) * kPos; s.seenL = now }
        if (m.hasR) { s.aR += (m.aR - s.aR) * kPos; s.seenR = now }
        if (m.hasL && m.hasR) s.width += ((m.aR - m.aL) - s.width) * 0.15f
        if (m.hasL && !m.hasR) s.aR += ((s.aL + s.width) - s.aR) * kPos
        if (m.hasR && !m.hasL) s.aL += ((s.aR - s.width) - s.aL) * kPos
        s.b += (m.b - s.b) * 0.35f
        s.c += (m.c - s.c) * 0.2f
        s.seenAny = now
    }

    private fun tol(z: Float) = 0.10f + 0.01f * z

    /** Robuste Gerade x = a + b·z durch die Punkte einer Seite; liefert die passenden Punkte. */
    private fun ransac(pts: List<Pt>): List<Pt> {
        if (pts.size < 8) return emptyList()
        var best: List<Pt> = emptyList()
        repeat(60) {
            val p = pts[rnd.nextInt(pts.size)]
            val q = pts[rnd.nextInt(pts.size)]
            val dz = q.z - p.z
            if (abs(dz) < 2f) return@repeat
            val b = (q.x - p.x) / dz
            if (abs(b) > 0.4f) return@repeat
            val a = p.x - b * p.z
            val inl = pts.filter { abs(a + b * it.z - it.x) < tol(it.z) }
            if (inl.size > best.size) best = inl
        }
        if (best.size < 8) return emptyList()
        val span = best.maxOf { it.z } - best.minOf { it.z }
        return if (span >= 3f) best else emptyList()
    }

    /** Gemeinsames Modell: beide Linien parallel mit gleicher Richtung und Krümmung (kleinste Quadrate). */
    private fun joint(l: List<Pt>, r: List<Pt>): Meas? {
        val hasL = l.isNotEmpty()
        val hasR = r.isNotEmpty()
        if (!hasL && !hasR) return null
        val all = l + r
        val span = all.maxOf { it.z } - all.minOf { it.z }
        val curve = span >= 12f && all.size >= 20
        // Unbekannte: [aL?, aR?, b, c?]
        val nA = (if (hasL) 1 else 0) + (if (hasR) 1 else 0)
        val n = nA + 1 + (if (curve) 1 else 0)
        val ata = Array(n) { DoubleArray(n) }
        val atb = DoubleArray(n)
        fun add(p: Pt, side: Int) {
            val row = DoubleArray(n)
            row[side] = 1.0
            row[nA] = p.z.toDouble()
            if (curve) row[nA + 1] = (p.z * p.z).toDouble()
            // nahe Punkte sind genauer -> stärker gewichten
            val wgt = 1.0 / (1.0 + p.z / 15.0)
            for (i in 0 until n) {
                atb[i] += wgt * row[i] * p.x
                for (j in 0 until n) ata[i][j] += wgt * row[i] * row[j]
            }
        }
        l.forEach { add(it, 0) }
        r.forEach { add(it, if (hasL) 1 else 0) }
        val sol = solve(ata, atb) ?: return null
        val aL = if (hasL) sol[0].toFloat() else 0f
        val aR = if (hasR) sol[if (hasL) 1 else 0].toFloat() else 0f
        return Meas(aL, aR, sol[nA].toFloat(), if (curve) sol[nA + 1].toFloat() else 0f, hasL, hasR)
    }

    companion object {
        const val Z_NEAR = 3f
        const val Z_FAR = 45f
        private const val NEVER = -1_000_000L

        /** Kleines Gleichungssystem lösen (Gauß mit Pivotsuche) */
        fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            val m = Array(n) { i -> a[i].copyOf(n + 1).also { it[n] = b[i] } }
            for (col in 0 until n) {
                var piv = col
                for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
                if (abs(m[piv][col]) < 1e-9) return null
                val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
                for (r in 0 until n) {
                    if (r == col) continue
                    val fct = m[r][col] / m[col][col]
                    for (k in col..n) m[r][k] -= fct * m[col][k]
                }
            }
            return DoubleArray(n) { m[it][n] / m[it][it] }
        }
    }
}
