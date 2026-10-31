package de.jarvis.w204.vision

/**
 * Spurverlassenswarnung: meldet, wenn das Auto über eine Linie zu fahren droht.
 * Nur ab [MIN_KMH], wenn die Spur seit ≥ 2 s sicher erkannt ist (beide Linien) und das Auto
 * sich tatsächlich auf die Linie zubewegt – ruhiges Fahren nah an der Linie löst nichts aus.
 * Den Blinker sehen wir per OBD nicht, deshalb pro Seite höchstens alle 8 s.
 */
class LaneDeparture(private val halfWidth: Float = 0.89f) {
    private var stableSince = -1L
    private val hist = ArrayDeque<Triple<Long, Float, Float>>()   // Zeit, linker Abstand, rechter Abstand
    private val lastWarn = longArrayOf(-100_000L, -100_000L)

    /** @return -1 = droht links rauszufahren, +1 = rechts, 0 = nichts */
    fun update(scene: Scene, speedKmh: Int?, now: Long): Int {
        val ok = scene.laneDetected && scene.laneBoth && (scene.laneRight - scene.laneLeft) in 2.6f..4.6f
        if (!ok) { stableSince = -1L; hist.clear(); return 0 }
        if (stableSince < 0) stableSince = now
        // Abstand der Radaußenkante zur Linie (positiv = noch innerhalb)
        val gapL = -scene.laneLeft - halfWidth
        val gapR = scene.laneRight - halfWidth
        hist.addLast(Triple(now, gapL, gapR))
        while (hist.isNotEmpty() && now - hist.first().first > 700) hist.removeFirst()
        if ((speedKmh ?: 0) < MIN_KMH || now - stableSince < 2000 || hist.size < 3) return 0

        val dt = (now - hist.first().first) / 1000f
        if (dt < 0.3f) return 0
        val vL = (hist.first().second - gapL) / dt     // > 0: nähert sich der linken Linie
        val vR = (hist.first().third - gapR) / dt
        val side = when {
            gapL < 0.06f && vL > 0.15f -> -1
            gapR < 0.06f && vR > 0.15f -> 1
            else -> 0
        }
        if (side == 0) return 0
        val i = if (side < 0) 0 else 1
        if (now - lastWarn[i] < 8000) return 0
        lastWarn[i] = now
        return side
    }

    companion object {
        const val MIN_KMH = 60
    }
}
