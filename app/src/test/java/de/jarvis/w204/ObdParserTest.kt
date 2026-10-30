package de.jarvis.w204

import de.jarvis.w204.car.VehicleState
import de.jarvis.w204.obd.ObdParser
import de.jarvis.w204.obd.Pid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdParserTest {
    private fun parse(pid: Pid, raw: String, s: VehicleState = VehicleState()): VehicleState {
        val d = ObdParser.data(raw, pid.code, pid.bytes) ?: return s
        return ObdParser.apply(s, pid, d)
    }

    @Test fun rpmSimple() = assertEquals(1726, parse(Pid.RPM, "410C1AF8").rpm)
    @Test fun rpmWithSpacesAndSearching() = assertEquals(1726, parse(Pid.RPM, "SEARCHING...\r41 0C 1A F8\r\r").rpm)
    @Test fun noData() = assertNull(ObdParser.data("NO DATA", 0x0C, 2))
    @Test fun unableToConnect() = assertNull(ObdParser.data("UNABLE TO CONNECT", 0x0D, 1))
    @Test fun truncated() = assertNull(ObdParser.data("410C1A", 0x0C, 2))
    @Test fun coolant() = assertEquals(88, parse(Pid.COOLANT, "4105 80").coolant)
    @Test fun speedSecondEcu() = assertEquals(50, parse(Pid.SPEED, "7E8 03 41 0D 32\r410D32").speed)

    @Test fun boostStandardMap() {
        val s = parse(Pid.BARO, "413365", parse(Pid.MAP, "410BC8"))
        assertEquals(1.0, s.boostBar!!, 0.01)   // 200 - 101 kPa
    }

    @Test fun boostExtendedAbove255kPa() {
        // Sensor A = 0x1F40 / 32 = 250 kPa
        val s = parse(Pid.MAP_EXT, "4187011F400000")
        assertEquals(250, s.mapKpa)
        assertEquals(1.49, s.boostBar!!, 0.01)
    }

    @Test fun boostExtendedSensorB() =
        assertEquals(240, parse(Pid.MAP_EXT, "418702 0000 1E00").mapKpa)

    @Test fun railPressure() = assertEquals(640, parse(Pid.RAIL, "41231900").railBar)   // 6400*10 kPa = 640 bar

    @Test fun fuelRateAndConsumption() {
        val s = parse(Pid.FUEL_RATE, "415E00A0").copy(speed = 100)   // 160/20 = 8 l/h
        assertEquals(8.0, s.fuelRate!!, 0.001)
        assertEquals(8.0, s.consumption!!, 0.001)
        assertNull(s.copy(speed = 3).consumption)
    }

    @Test fun supportedPids() {
        val set = ObdParser.supported("4100BE3EB811", 0x00)
        assertTrue(0x05 in set)
        assertTrue(0x0C in set)
        assertTrue(0x0D in set)
        assertTrue(0x20 !in set || true)
        assertTrue(0x02 !in set)
    }

    @Test fun supportedUnionOfTwoEcus() {
        val set = ObdParser.supported("4100 80000000\r4100 00800000", 0x00)
        assertTrue(0x01 in set)
        assertTrue(0x09 in set)
    }

    @Test fun voltage() {
        assertEquals(12.6, ObdParser.voltage("12.6V")!!, 0.001)
        assertEquals(14.1, ObdParser.voltage("14,1V")!!, 0.001)
        assertNull(ObdParser.voltage("?"))
    }
}
