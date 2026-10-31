package de.jarvis.w204.map

import kotlin.math.floor

/** Gebäude als Grundriss + Höhe (für die 3D-Karte). */
class Building(val id: Long, val lat: DoubleArray, val lon: DoubleArray, val height: Float) {
    val cLat = lat.average()
    val cLon = lon.average()
}

object Buildings {
    // kleine Kacheln (~550 × 570 m): Gebäudedaten sind in der Stadt dicht
    fun tileOf(lat: Double, lon: Double) = floor(lat * 200 + 1e-9).toInt() to floor(lon * 125 + 1e-9).toInt()

    fun query(ty: Int, tx: Int): String {
        val s = ty / 200.0; val n = (ty + 1) / 200.0
        val w = tx / 125.0; val e = (tx + 1) / 125.0
        fun f(v: Double) = String.format(java.util.Locale.US, "%.5f", v)
        return "[out:json][timeout:25];way[\"building\"](${f(s)},${f(w)},${f(n)},${f(e)});out tags geom qt;"
    }

    /** Höhe: height-Tag, sonst Stockwerke × 3,2 m, sonst je nach Art geschätzt */
    fun from(w: Way): Building? {
        if (w.lat.size < 4) return null
        val t = w.tags
        val h = t["height"]?.replace(",", ".")?.filter { it.isDigit() || it == '.' }?.toFloatOrNull()
            ?: t["building:levels"]?.toFloatOrNull()?.let { it * 3.2f + 1f }
            ?: when (t["building"]) {
                "garage", "garages", "shed", "carport", "roof", "hut" -> 3f
                "house", "detached", "semidetached_house", "terrace", "bungalow" -> 8f
                "apartments", "residential" -> 14f
                "commercial", "retail", "office", "industrial", "warehouse" -> 11f
                "church", "cathedral" -> 22f
                else -> 9f + (w.id % 5).toFloat()      // etwas Abwechslung
            }
        return Building(w.id, w.lat, w.lon, h.coerceIn(2.5f, 120f))
    }
}
