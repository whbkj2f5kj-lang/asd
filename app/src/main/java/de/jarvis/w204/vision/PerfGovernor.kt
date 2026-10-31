package de.jarvis.w204.vision

import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Leistungsregler: Die Schildererkennung soll jedes Kamerabild (bis 15 pro Sekunde) in möglichst hoher
 * Auflösung auswerten – höhere Auflösung = Schilder werden früher (weiter weg) gefunden.
 * Die Fahrzeugerkennung bekommt, was übrig ist (mindestens etwa 1× pro Sekunde).
 *
 * Alle 2 Sekunden:
 *  - Schilder schaffen ihr Zeitbudget nicht -> zuerst Fahrzeuge seltener, dann Schild-Auflösung runter
 *  - deutlich Luft -> zuerst Schild-Auflösung rauf, dann Fahrzeuge wieder öfter
 *  - Chip zu heiß (≥ 80 °C) -> überall einen Schritt zurück
 */
class PerfGovernor {
    @Volatile var signWidth = 640
        private set
    @Volatile var vehicleIntervalMs = 250L
        private set

    @Volatile var cameraFps = 0f
    @Volatile var signMs = 0f
        private set
    @Volatile var vehicleMs = 0f
    @Volatile var laneMs = 0f
    @Volatile var tempC: Float? = null
        private set
    @Volatile var appCpu: Float? = null
        private set

    private var lastTick = 0L
    private var cpuTicks = -1L
    private var cpuAt = 0L

    fun signDone(ms: Long, frameWidth: Int, now: Long) {
        signMs = if (signMs == 0f) ms.toFloat() else signMs * 0.8f + ms * 0.2f
        if (now - lastTick >= 2000) {
            lastTick = now
            readSystem(now)
            adjust(frameWidth)
        }
    }

    /** Eine Regelstufe (auch direkt testbar). */
    fun adjust(frameWidth: Int) {
        val maxW = min(MAX_SIGN_WIDTH, frameWidth)
        val fps = if (cameraFps > 1f) min(cameraFps, 15f) else 10f
        val budget = 1000f / fps
        val hot = (tempC ?: 0f) >= 80f
        when {
            hot -> {
                signWidth = max(MIN_SIGN_WIDTH, signWidth - STEP)
                vehicleIntervalMs = min(MAX_VEHICLE_MS, (vehicleIntervalMs * 1.5f).toLong())
            }
            signMs > budget * 0.9f -> {
                if (vehicleIntervalMs < MAX_VEHICLE_MS) vehicleIntervalMs = min(MAX_VEHICLE_MS, (vehicleIntervalMs * 1.5f).toLong())
                else signWidth = max(MIN_SIGN_WIDTH, signWidth - STEP)
            }
            signMs < budget * 0.55f -> {
                if (signWidth < maxW) signWidth = min(maxW, signWidth + STEP)
                else if (vehicleIntervalMs > MIN_VEHICLE_MS) vehicleIntervalMs = max(MIN_VEHICLE_MS, (vehicleIntervalMs / 1.3f).toLong())
            }
        }
    }

    private fun readSystem(now: Long) {
        // Temperatur: höchster plausibler Wert der Thermal-Zonen (falls das Radio sie lesen lässt)
        tempC = runCatching {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
                ?.mapNotNull { z -> File(z, "temp").takeIf { it.canRead() }?.readText()?.trim()?.toFloatOrNull() }
                ?.map { if (it > 1000f) it / 1000f else it }
                ?.filter { it in 10f..130f }
                ?.maxOrNull()
        }.getOrNull()
        // Prozessorzeit der App (in "Kernen": 1,0 = ein Kern voll ausgelastet)
        runCatching {
            val f = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')
            val ticks = f[11].toLong() + f[12].toLong()   // utime + stime
            if (cpuTicks >= 0 && now > cpuAt) appCpu = (ticks - cpuTicks) * 10f / (now - cpuAt)   // 100 Ticks/s
            cpuTicks = ticks
            cpuAt = now
        }
    }

    fun summary(): String {
        fun r(v: Float) = v.roundToInt()
        return "Kamera ${r(cameraFps)} B/s · Schilder ${r(signMs)} ms @ ${signWidth} px · Fahrzeuge alle ${vehicleIntervalMs} ms (${r(vehicleMs)} ms) · Spur ${r(laneMs)} ms" +
            (appCpu?.let { " · CPU " + String.format(java.util.Locale.GERMANY, "%.1f", it) + " Kerne" } ?: "") +
            (tempC?.let { " · ${r(it)} °C" } ?: "")
    }

    companion object {
        const val MIN_SIGN_WIDTH = 640
        const val MAX_SIGN_WIDTH = 1280
        const val STEP = 160
        const val MIN_VEHICLE_MS = 200L
        const val MAX_VEHICLE_MS = 800L
    }
}
