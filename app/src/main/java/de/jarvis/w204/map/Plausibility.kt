package de.jarvis.w204.map

/**
 * Welches Tempolimit ist an dieser Stelle überhaupt plausibel? (null = keine Einschränkung)
 *  - Autobahn / Kraftfahrstraße (motorway, trunk): alles
 *  - Wohnstraße, verkehrsberuhigt, Zufahrt: höchstens 50
 *  - innerorts (Ortsschild gesehen oder laut Karte): höchstens 70
 *  - eingetragenes Kartenlimit: höchstens 30 km/h darüber (mind. 50)
 * Niedrigere Werte (Baustelle, Schule) sind immer plausibel.
 */
object Plausibility {
    fun maxLimit(highway: String?, urban: Boolean?, map: MapLimit?): Int? {
        if (highway != null && (highway.startsWith("motorway") || highway.startsWith("trunk"))) return null
        val caps = ArrayList<Int>()
        if (highway == "residential" || highway == "living_street" || highway == "service") caps += 50
        if (urban == true) caps += 70
        if (map != null && !map.estimated && !map.none && map.kmh != null) caps += maxOf(map.kmh + 30, 50)
        return caps.minOrNull()
    }
}
