package de.jarvis.w204.obd

import de.jarvis.w204.car.VehicleState

/** Standard-OBD-PIDs (Mode 01), die der OM651 typischerweise liefert. */
enum class Pid(val code: Int, val bytes: Int) {
    LOAD(0x04, 1),
    COOLANT(0x05, 1),
    MAP(0x0B, 1),
    RPM(0x0C, 2),
    SPEED(0x0D, 1),
    IAT(0x0F, 1),
    MAF(0x10, 2),
    RAIL(0x23, 2),
    FUEL(0x2F, 1),
    FUEL_RATE(0x5E, 2),
    MAP_EXT(0x87, 5),
    BARO(0x33, 1),
    AMBIENT(0x46, 1);

    val command: String get() = "01%02X".format(code)
}

object ObdParser {
    private val errorMarkers = listOf("NODATA", "ERROR", "UNABLE", "STOPPED", "BUSINIT", "BUFFERFULL", "?")

    fun normalize(raw: String): String = raw.uppercase().replace("SEARCHING...", "").replace(" ", "")

    fun isError(raw: String): Boolean {
        val n = normalize(raw)
        return n.isBlank() || errorMarkers.any { n.contains(it) }
    }

    /** Datenbytes einer Mode-01-Antwort; null wenn nicht vorhanden. Mehrere Steuergeräte = mehrere Zeilen. */
    fun data(raw: String, pid: Int, bytes: Int): IntArray? {
        val prefix = "41%02X".format(pid)
        for (line in normalize(raw).split('\r', '\n')) {
            val i = line.indexOf(prefix)
            if (i < 0) continue
            val hex = line.substring(i + prefix.length)
            if (hex.length < bytes * 2) continue
            val out = IntArray(bytes)
            var ok = true
            for (b in 0 until bytes) {
                val v = hex.substring(b * 2, b * 2 + 2).toIntOrNull(16)
                if (v == null) { ok = false; break }
                out[b] = v
            }
            if (ok) return out
        }
        return null
    }

    /** Auswertung der "unterstützte PIDs"-Bitmaske (0100, 0120, 0140), Vereinigung über alle Steuergeräte. */
    fun supported(raw: String, base: Int): Set<Int> {
        val out = mutableSetOf<Int>()
        for (line in normalize(raw).split('\r', '\n')) {
            val d = data(line, base, 4) ?: continue
            for (byteIdx in 0 until 4) for (bit in 0 until 8) {
                if ((d[byteIdx] and (0x80 shr bit)) != 0) out += base + byteIdx * 8 + bit + 1
            }
        }
        return out
    }

    fun apply(s: VehicleState, pid: Pid, d: IntArray): VehicleState = when (pid) {
        Pid.LOAD -> s.copy(load = d[0] * 100 / 255)
        Pid.COOLANT -> s.copy(coolant = d[0] - 40)
        Pid.MAP -> s.copy(mapKpa = d[0])
        // 0x87: A = welche Sensoren, B-C = Sensor A, D-E = Sensor B (je 1/32 kPa) – reicht über 255 kPa hinaus
        Pid.MAP_EXT -> when {
            d[0] and 0x01 != 0 -> s.copy(mapKpa = (d[1] * 256 + d[2]) / 32)
            d[0] and 0x02 != 0 -> s.copy(mapKpa = (d[3] * 256 + d[4]) / 32)
            else -> s
        }
        Pid.FUEL_RATE -> s.copy(fuelRate = (d[0] * 256 + d[1]) / 20.0)
        Pid.RPM -> s.copy(rpm = (d[0] * 256 + d[1]) / 4)
        Pid.SPEED -> s.copy(speed = d[0])
        Pid.IAT -> s.copy(intakeTemp = d[0] - 40)
        Pid.MAF -> s.copy(maf = (d[0] * 256 + d[1]) / 100.0)
        Pid.RAIL -> s.copy(railBar = (d[0] * 256 + d[1]) * 10 / 100)   // kPa*10 -> bar
        Pid.FUEL -> s.copy(fuelLevel = d[0] * 100 / 255)
        Pid.BARO -> s.copy(baroKpa = d[0])
        Pid.AMBIENT -> s.copy(ambient = d[0] - 40)
    }

    /** Antwort auf ATRV, z. B. "12.6V" */
    fun voltage(raw: String): Double? =
        Regex("(\\d{1,2}[.,]\\d)").find(raw)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
}
