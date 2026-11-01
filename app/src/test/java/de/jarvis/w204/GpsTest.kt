package de.jarvis.w204

import de.jarvis.w204.map.MapMatcher
import de.jarvis.w204.map.PosFilter
import de.jarvis.w204.map.Way
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

class GpsTest {
    private val lat0 = 51.48
    private val lon0 = 7.21
    private val ky = 110_540.0
    private val kx = 111_320.0 * cos(Math.toRadians(lat0))
    private fun n(m: Double) = lat0 + m / ky          // m nach Norden
    private fun e(m: Double) = lon0 + m / kx          // m nach Osten
    private fun dist(aLat: Double, aLon: Double, bLat: Double, bLon: Double) = hypot((aLat - bLat) * ky, (aLon - bLon) * kx)

    @Test fun deadReckoningWithObdSpeed() {
        val f = PosFilter()
        f.update(n(0.0), lon0, 5f, 0f, 20f, true, 0L, 20f, null)
        // 10 s ohne GPS, Tacho sagt 20 m/s nach Norden -> ~200 m weiter
        var p: PosFilter.Pose? = null
        for (t in 200L..10_000L step 200L) p = f.predict(t, 20f, null)
        assertNotNull(p)
        assertEquals(200.0, (p!!.lat - lat0) * ky, 2.0)
        assertEquals(PosFilter.Source.DEAD_RECKONING, p.source)
    }

    @Test fun outlierRejectedThenAccepted() {
        val f = PosFilter()
        f.update(n(0.0), lon0, 5f, 0f, 0f, true, 0L, 0f, null)
        f.update(n(0.0), lon0, 5f, null, 0f, true, 1000L, 0f, null)
        // einzelner Sprung um 400 m -> verworfen
        assertFalse(f.update(n(400.0), lon0, 10f, null, 0f, true, 2000L, 0f, null))
        assertTrue(dist(f.pose!!.lat, f.pose!!.lon, lat0, lon0) < 5)
        // bleibt er dort, wird er übernommen
        f.update(n(400.0), lon0, 10f, null, 0f, true, 3000L, 0f, null)
        assertTrue(f.update(n(400.0), lon0, 10f, null, 0f, true, 4000L, 0f, null))
        assertTrue(dist(f.pose!!.lat, f.pose!!.lon, n(400.0), lon0) < 15)
    }

    @Test fun inaccurateFixPullsLittle() {
        val f = PosFilter()
        f.update(n(0.0), lon0, 4f, null, 0f, true, 0L, 0f, null)
        f.update(n(30.0), lon0, 60f, null, 0f, true, 1000L, 0f, null)
        assertTrue((f.pose!!.lat - lat0) * ky < 5)
    }

    @Test fun savedSeedReplacedByFirstFix() {
        val f = PosFilter()
        f.seed(n(5000.0), lon0, null, 0L)
        assertEquals(PosFilter.Source.SAVED, f.pose!!.source)
        // stehend: rechnet nicht weiter
        f.predict(5000L, 15f, null)
        assertEquals(5000.0, (f.pose!!.lat - lat0) * ky, 0.5)
        f.update(n(0.0), lon0, 20f, null, null, true, 6000L, null, null)
        assertEquals(PosFilter.Source.GPS, f.pose!!.source)
        assertTrue(dist(f.pose!!.lat, f.pose!!.lon, lat0, lon0) < 1)
    }

    @Test fun followsCurvedRoad() {
        // L-förmige Straße: 100 m nach Norden, dann 100 m nach Osten
        val w = Way(1, mapOf("highway" to "primary"), doubleArrayOf(n(0.0), n(100.0), n(100.0)), doubleArrayOf(lon0, lon0, e(100.0)))
        val r = MapMatcher.advance(w, n(50.0), e(3.0), 2f, 100.0)!!
        // 50 m bis zur Ecke, dann 50 m nach Osten
        assertEquals(100.0, (r.first - lat0) * ky, 1.0)
        assertEquals(50.0, (r.second - lon0) * kx, 1.0)
        assertTrue(abs(r.third - 90f) < 2f)
    }

    @Test fun followRoadBackwards() {
        val w = Way(1, mapOf("highway" to "primary"), doubleArrayOf(n(0.0), n(100.0)), doubleArrayOf(lon0, lon0))
        val r = MapMatcher.advance(w, n(80.0), lon0, 180f, 30.0)!!
        assertEquals(50.0, (r.first - lat0) * ky, 1.0)
        assertTrue(abs(r.third - 180f) < 2f)
    }

    @Test fun crossingRoadNotFollowed() {
        val w = Way(1, mapOf("highway" to "primary"), doubleArrayOf(n(0.0), n(100.0)), doubleArrayOf(lon0, lon0))
        assertEquals(null, MapMatcher.advance(w, n(50.0), lon0, 90f, 10.0))
    }
}
