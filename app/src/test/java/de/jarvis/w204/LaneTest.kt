package de.jarvis.w204

import de.jarvis.w204.vision.Calibration
import de.jarvis.w204.vision.LaneDeparture
import de.jarvis.w204.vision.LaneDetector
import de.jarvis.w204.vision.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LaneTest {
    private val cal = Calibration(heightM = 1.25f, horizon = 0.5f, fovDeg = 90f)
    private val sw = 320
    private val sh = 180
    private val f = (sw / 2f) / 1f        // tan(45°) = 1
    private val road = 0xFF404040.toInt()

    /** Straße mit Linien x(z) = a + c·z² ins Bild zeichnen */
    private fun draw(px: IntArray, a: Float, c: Float, color: Int) {
        val yh = cal.horizon * sh
        for (y in (yh + 3).toInt() until sh) {
            val z = f * cal.heightM / (y - yh)
            val x = a + c * z * z
            val u = (sw / 2f + x * f / z).toInt()
            for (dx in 0..1) { val uu = u + dx; if (uu in 0 until sw) px[y * sw + uu] = color }
        }
    }

    @Test fun findsCurve() {
        val px = IntArray(sw * sh) { road }
        draw(px, -1.75f, 0.003f, 0xFFFFFFFF.toInt())
        draw(px, 1.75f, 0.003f, 0xFFFFFFFF.toInt())
        val det = LaneDetector()
        var lanes = det.detect(px, sw, sh, cal, 0)
        for (i in 1..5) lanes = det.detect(px, sw, sh, cal, i * 100L)
        val m = lanes.model
        assertNotNull(m)
        assertTrue("links ${m!!.left}", abs(m.left + 1.75f) < 0.2f)
        assertTrue("rechts ${m.right}", abs(m.right - 1.75f) < 0.2f)
        assertTrue("Krümmung ${m.curve}", abs(m.curve - 0.003f) < 0.0012f)
        assertTrue(m.leftSeen && m.rightSeen)
    }

    @Test fun oneLineIsCompletedWithLaneWidth() {
        val px = IntArray(sw * sh) { road }
        draw(px, -1.6f, 0f, 0xFFFFFFFF.toInt())
        val m = LaneDetector().detect(px, sw, sh, cal, 0).model!!
        assertTrue(m.leftSeen)
        assertTrue(!m.rightSeen)
        assertEquals(3.5f, m.width, 0.3f)
    }

    @Test fun yellowConstructionMarkingsWin() {
        val px = IntArray(sw * sh) { road }
        draw(px, -1.75f, 0f, 0xFFFFFFFF.toInt())
        draw(px, 1.75f, 0f, 0xFFFFFFFF.toInt())
        val yellow = (0xFF shl 24) or (240 shl 16) or (190 shl 8) or 30
        draw(px, -1.45f, 0f, yellow)
        draw(px, 1.45f, 0f, yellow)
        val det = LaneDetector()
        var lanes = det.detect(px, sw, sh, cal, 0)
        for (i in 1..20) lanes = det.detect(px, sw, sh, cal, i * 100L)
        val m = lanes.model!!
        assertTrue(m.yellow)
        assertTrue("links ${m.left}", abs(m.left + 1.45f) < 0.15f)
        assertTrue("rechts ${m.right}", abs(m.right - 1.45f) < 0.15f)
    }

    @Test fun nightLowContrast() {
        // dunkle Straße, Linie nur mäßig hell (Scheinwerferlicht)
        val px = IntArray(sw * sh) { 0xFF141414.toInt() }
        val dim = 0xFF5A5A5A.toInt()
        draw(px, -1.75f, 0f, dim)
        draw(px, 1.75f, 0f, dim)
        val m = LaneDetector().detect(px, sw, sh, cal, 0).model
        assertNotNull("Nacht: Spur nicht gefunden", m)
        assertTrue(m!!.leftSeen && m.rightSeen)
    }

    @Test fun departureWarning() {
        fun scene(l: Float) = Scene(laneLeft = l, laneRight = l + 3.5f, laneDetected = true, laneBoth = true)
        // ruhig in der Mitte, dann nach links driften
        val ldw = LaneDeparture()
        var t = 0L
        repeat(30) { assertEquals(0, ldw.update(scene(-1.75f), 90, t)); t += 100 }
        var warned = 0
        var l = -1.75f
        repeat(30) {
            l += 0.05f      // 0,5 m/s Richtung linke Linie
            val r = ldw.update(scene(l), 90, t)
            if (r != 0) warned = r
            t += 100
        }
        assertEquals(-1, warned)

        // gleiche Drift, aber langsam unterwegs -> keine Warnung
        val slow = LaneDeparture()
        t = 0; l = -1.75f; warned = 0
        repeat(30) { slow.update(scene(-1.75f), 40, t); t += 100 }
        repeat(30) { l += 0.05f; if (slow.update(scene(l), 40, t) != 0) warned = 1; t += 100 }
        assertEquals(0, warned)

        // dicht an der Linie, aber ruhig -> keine Warnung
        val steady = LaneDeparture()
        t = 0; warned = 0
        repeat(60) { if (steady.update(scene(-0.92f), 100, t) != 0) warned = 1; t += 100 }
        assertEquals(0, warned)
    }

    @Test fun solver() {
        val a = arrayOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 3.0))
        val x = LaneDetector.solve(a, doubleArrayOf(3.0, 5.0))!!
        assertEquals(0.8, x[0], 1e-9)
        assertEquals(1.4, x[1], 1e-9)
    }
}
