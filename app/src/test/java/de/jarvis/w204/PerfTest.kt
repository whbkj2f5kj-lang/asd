package de.jarvis.w204

import de.jarvis.w204.vision.PerfGovernor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfTest {
    @Test fun headroomRaisesSignResolutionFirst() {
        val g = PerfGovernor()
        g.cameraFps = 10f
        repeat(3) { g.signDone(20, 1280, 0) }      // 20 ms von 100 ms Budget -> viel Luft
        g.adjust(1280)
        assertEquals(800, g.signWidth)
        repeat(5) { g.adjust(1280) }
        assertEquals(1280, g.signWidth)            // bis zur vollen Kamerabreite
        assertTrue(g.vehicleIntervalMs < 250)      // danach dürfen Fahrzeuge öfter
    }

    @Test fun overloadThrottlesVehiclesBeforeSigns() {
        val g = PerfGovernor()
        g.cameraFps = 15f
        repeat(10) { g.signDone(120, 1280, 0) }    // 120 ms > 66 ms Budget
        g.adjust(1280)
        assertEquals(640, g.signWidth)
        assertTrue(g.vehicleIntervalMs > 250)
        repeat(5) { g.adjust(1280) }
        assertEquals(PerfGovernor.MAX_VEHICLE_MS, g.vehicleIntervalMs)
        assertEquals(PerfGovernor.MIN_SIGN_WIDTH, g.signWidth)   // nie unter 640
    }

    @Test fun neverAboveFrameWidth() {
        val g = PerfGovernor()
        g.cameraFps = 10f
        g.signDone(5, 960, 0)
        repeat(10) { g.adjust(960) }
        assertEquals(960, g.signWidth)
    }
}
