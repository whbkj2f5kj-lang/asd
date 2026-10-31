package de.jarvis.w204

import de.jarvis.w204.vision.Calibration
import de.jarvis.w204.vision.Detection
import de.jarvis.w204.vision.LaneDetector
import de.jarvis.w204.vision.ObjKind
import de.jarvis.w204.vision.Projector
import de.jarvis.w204.vision.SceneBuilder
import de.jarvis.w204.vision.VisionFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class VisionTest {
    private val cal = Calibration(heightM = 1.25f, horizon = 0.5f, fovDeg = 90f)
    private val w = 1280
    private val h = 720
    private val f = (w / 2f) / tan(Math.toRadians(45.0).toFloat())

    @Test fun groundDistance() {
        val p = Projector(cal, w, h)
        assertEquals(20f, p.groundZ(360f + f * 1.25f / 20f)!!, 0.01f)
        assertNull(p.groundZ(300f))   // über dem Horizont
    }

    @Test fun lateralOffset() {
        val p = Projector(cal, w, h)
        assertEquals(0f, p.lateral(640f, 20f), 0.001f)
        assertTrue(p.lateral(900f, 20f) > 0f)
    }

    /** Auto genau vor uns in 20 m: Breite 1,8 m, Unterkante auf der Straße. */
    private fun carAt(z: Float): Detection {
        val wpx = f * 1.8f / z
        val bottom = 360f + f * 1.25f / z
        val hpx = f * 1.45f / z
        return Detection(
            ObjKind.CAR,
            (640f - wpx / 2) / w, (bottom - hpx) / h,
            (640f + wpx / 2) / w, bottom / h,
            0.9f,
        )
    }

    @Test fun leadDistanceNeedsTwoFrames() {
        val sb = SceneBuilder()
        val first = sb.update(VisionFrame(w, h, objects = listOf(carAt(20f))), cal, 0)
        assertNull(first.leadDistance)          // ein einzelnes Bild zählt noch nicht
        val second = sb.update(VisionFrame(w, h, objects = listOf(carAt(20f))), cal, 100)
        assertNotNull(second.leadDistance)
        assertEquals(20f, second.leadDistance!!, 1.5f)
        assertEquals(1, second.objects.size)
        assertTrue(second.objects[0].lead)
    }

    @Test fun trackDisappearsAfterTimeout() {
        val sb = SceneBuilder()
        sb.update(VisionFrame(w, h, objects = listOf(carAt(30f))), cal, 0)
        sb.update(VisionFrame(w, h, objects = listOf(carAt(30f))), cal, 100)
        val later = sb.update(VisionFrame(w, h), cal, 2000)
        assertTrue(later.objects.isEmpty())
        assertNull(later.leadDistance)
    }

    @Test fun carInNeighbourLaneIsNotLead() {
        val sb = SceneBuilder()
        val c = carAt(20f)
        val shift = 0.25f
        val side = c.copy(l = c.l + shift, r = c.r + shift)
        sb.update(VisionFrame(w, h, objects = listOf(side)), cal, 0)
        val s = sb.update(VisionFrame(w, h, objects = listOf(side)), cal, 100)
        assertEquals(1, s.objects.size)
        assertTrue(!s.objects[0].lead)
        assertNull(s.leadDistance)
    }

    @Test fun ownBonnetIgnored() {
        val sb = SceneBuilder()
        val bonnet = Detection(ObjKind.CAR, 0.02f, 0.8f, 0.98f, 1f, 0.9f)
        sb.update(VisionFrame(w, h, objects = listOf(bonnet)), cal, 0)
        assertTrue(sb.update(VisionFrame(w, h, objects = listOf(bonnet)), cal, 100).objects.isEmpty())
    }

    @Test fun laneLinesFromSyntheticImage() {
        // Graue Straße, zwei weiße Linien, die im Fluchtpunkt zusammenlaufen
        val sw = 320
        val sh = 180
        val px = IntArray(sw * sh) { 0xFF404040.toInt() }
        val vy = sh * 0.5f
        for (y in (vy + 4).toInt() until sh) {
            val t = (y - vy) / (sh - vy)
            val xl = (sw / 2f - t * sw * 0.42f).toInt()
            val xr = (sw / 2f + t * sw * 0.42f).toInt()
            for (dx in 0..1) {
                px[y * sw + (xl + dx).coerceIn(0, sw - 1)] = 0xFFFFFFFF.toInt()
                px[y * sw + (xr + dx).coerceIn(0, sw - 1)] = 0xFFFFFFFF.toInt()
            }
        }
        val lanes = LaneDetector().detect(px, sw, sh, cal, 0)
        assertNotNull("linke Linie", lanes.left)
        assertNotNull("rechte Linie", lanes.right)
        assertTrue(lanes.left!!.m < 0f)
        assertTrue(lanes.right!!.m > 0f)

        // Spur in Straßenkoordinaten muss plausibel breit sein
        val sb = SceneBuilder()
        val scene = sb.update(VisionFrame(sw, sh, lanes = lanes), cal, 0)
        assertTrue(scene.laneDetected)
        assertTrue("Spurbreite ${scene.laneRight - scene.laneLeft}", scene.laneRight - scene.laneLeft in 2.0f..5.0f)
    }
}
