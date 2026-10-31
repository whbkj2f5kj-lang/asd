package de.jarvis.w204.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import de.jarvis.w204.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * GPS-Position + OpenStreetMap-Straßendaten -> Tempolimit der aktuellen Straße.
 * Straßen werden in Kacheln (~2 × 2 km) über die Overpass-API geladen und 30 Tage gespeichert,
 * bekannte Strecken brauchen also kaum noch Daten.
 */
class MapLimits(context: Context, private val scope: CoroutineScope) {

    data class State(
        val running: Boolean = false,
        val hasFix: Boolean = false,
        val gps: String = "aus",
        val road: String? = null,
        val roadKey: String? = null,
        val limit: MapLimit? = null,
        val tiles: Int = 0,
        val loading: Boolean = false,
        val error: String? = null,
        /** OSM-Straßenart der zugeordneten Straße (motorway, primary, residential …) */
        val highway: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val acc: Int? = null,
    )

    private val ctx = context.applicationContext
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val dir = File(ctx.filesDir, "osm").apply { mkdirs() }
    private val tiles = ConcurrentHashMap<Long, List<Way>>()
    private val loading = ConcurrentHashMap.newKeySet<Long>()
    private val failedAt = ConcurrentHashMap<Long, Long>()
    private val net = Mutex()
    private val busy = AtomicBoolean(false)
    private var server = 0

    private var listener: LocationListener? = null
    private var watchdog: Job? = null
    @Volatile private var lastFix = 0L
    @Volatile private var center = 0 to 0

    private var shown: Match? = null
    private var candidate: Match? = null
    private var candidateCount = 0

    fun permitted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (listener != null) return
        if (!permitted()) { _state.value = State(gps = "Standort-Berechtigung fehlt – App neu starten und erlauben"); return }
        val providers = runCatching { lm.allProviders }.getOrDefault(emptyList())
        if (LocationManager.GPS_PROVIDER !in providers && LocationManager.NETWORK_PROVIDER !in providers) {
            _state.value = State(gps = "Kein GPS im Gerät gefunden (Anbieter: ${providers.joinToString()})"); return
        }
        // Alle Methoden selbst umsetzen: unter Android 10 hat das Interface noch keine Standard-Methoden
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) { if (provider == LocationManager.GPS_PROVIDER) gpsOff = false }
            override fun onProviderDisabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER) {
                    gpsOff = true
                    _state.value = _state.value.copy(hasFix = false, gps = "Standort ist am Radio ausgeschaltet")
                }
            }
        }
        // GPS + (falls vorhanden) Netz/Fused als Rückfallebene
        var any = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")) {
            if (p !in providers) continue
            try {
                lm.requestLocationUpdates(p, 1000L, 0f, l, Looper.getMainLooper())
                any = true
            } catch (t: Throwable) {
                Log.w(TAG, "Standortquelle $p nicht nutzbar", t)
            }
        }
        if (!any) { _state.value = State(gps = "GPS nicht nutzbar"); return }
        listener = l
        startedAt = SystemClock.elapsedRealtime()
        gpsOff = runCatching { !lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

        // Hilfsdaten für schnellen Start (A-GPS: Satellitenbahnen + Uhrzeit aus dem Internet)
        runCatching { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_time_injection", null) }
        runCatching { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_xtra_injection", null) }

        // Satelliten zählen: zeigt, ob überhaupt Empfang da ist (Antenne!)
        val cb = object : android.location.GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                var used = 0
                var good = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) used++
                    if (status.getCn0DbHz(i) >= 25f) good++
                }
                satsVisible = status.satelliteCount
                satsUsed = used
                satsGood = good
            }
        }
        runCatching { lm.registerGnssStatusCallback(cb, android.os.Handler(Looper.getMainLooper())) }
        gnssCb = cb

        // Letzte bekannte Position: Karte schon vorladen, bevor das GPS steht
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { last -> scope.launch(Dispatchers.Default) { prefetch(last.latitude, last.longitude) } }

        _state.value = State(running = true, gps = if (gpsOff) "Standort ist am Radio ausgeschaltet" else "Warte auf GPS-Signal …")
        watchdog = scope.launch {
            while (isActive) {
                delay(2000)
                val s = _state.value
                val now = SystemClock.elapsedRealtime()
                val gpsFresh = now - lastGpsFix < 10_000
                if (gpsOff) {
                    _state.value = s.copy(hasFix = false, gps = "Standort ist am Radio ausgeschaltet – in den Android-Einstellungen einschalten")
                } else if (!gpsFresh) {
                    val sats = "Satelliten: $satsVisible sichtbar, $satsGood mit gutem Signal, $satsUsed genutzt"
                    val wait = (now - startedAt) / 1000
                    val head = when {
                        lastGpsFix > 0 -> "GPS-Signal verloren"
                        now - lastOtherFix < 10_000 -> "Nur ungefährer Standort (Netz)"
                        else -> "Warte auf GPS-Signal (${wait / 60}:${"%02d".format(wait % 60)})"
                    }
                    val hint = when {
                        wait > 120 && satsVisible == 0 -> " – keine Satelliten: GPS-Antenne am Radio prüfen"
                        wait > 120 && satsGood < 4 -> " – Signal zu schwach: Antenne frei unter die Scheibe/aufs Dach"
                        else -> ""
                    }
                    _state.value = s.copy(hasFix = now - lastOtherFix < 10_000 && s.hasFix, gps = "$head · $sats$hint")
                }
            }
        }
    }

    @Volatile private var gpsOff = false
    @Volatile private var startedAt = 0L
    @Volatile private var lastGpsFix = 0L
    @Volatile private var lastOtherFix = 0L
    @Volatile private var satsVisible = 0
    @Volatile private var satsUsed = 0
    @Volatile private var satsGood = 0
    private var gnssCb: android.location.GnssStatus.Callback? = null

    fun stop() {
        listener?.let { runCatching { lm.removeUpdates(it) } }
        listener = null
        gnssCb?.let { runCatching { lm.unregisterGnssStatusCallback(it) } }
        gnssCb = null
        watchdog?.cancel()
        watchdog = null
        shown = null
        candidate = null
        _state.value = State()
    }

    private fun onLocation(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        lastFix = now
        val isGps = loc.provider == LocationManager.GPS_PROVIDER
        if (isGps) lastGpsFix = now else {
            lastOtherFix = now
            // Netz-/Fused-Position nur nutzen, solange GPS nichts liefert
            if (now - lastGpsFix < 5000) return
        }
        if (!busy.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Default) {
            try {
                process(loc)
            } catch (t: Throwable) {
                Log.w(TAG, "Kartenabgleich fehlgeschlagen", t)
            } finally {
                busy.set(false)
            }
        }
    }

    /** Kartenstücke rund um eine (auch ungenaue) Position schon laden */
    private fun prefetch(lat: Double, lon: Double) {
        val (ty, tx) = Osm.tileOf(lat, lon)
        center = ty to tx
        ensure(ty, tx)
        for (dy in -1..1) for (dx in -1..1) if (dy != 0 || dx != 0) ensure(ty + dy, tx + dx)
    }

    private fun process(loc: Location) {
        val acc = if (loc.hasAccuracy()) loc.accuracy.roundToInt() else null
        val isGps = loc.provider == LocationManager.GPS_PROVIDER
        val gpsText = (if (isGps) "GPS ok" else "Standort (Netz)") + (acc?.let { " ±$it m" } ?: "") +
            (if (isGps) " · $satsUsed Satelliten" else "")
        if (acc != null && acc < 1000) prefetch(loc.latitude, loc.longitude)
        // Straßen zuordnen nur bei ausreichender Genauigkeit
        if (acc != null && acc > (if (isGps) 40 else 25)) {
            _state.value = _state.value.copy(hasFix = false, gps = "$gpsText – zu ungenau für die Straßenzuordnung")
            return
        }
        val (ty, tx) = Osm.tileOf(loc.latitude, loc.longitude)
        center = ty to tx
        ensure(ty, tx)
        for (dy in -1..1) for (dx in -1..1) if (dy != 0 || dx != 0) ensure(ty + dy, tx + dx)

        val ways = HashMap<Long, Way>()
        for (dy in -1..1) for (dx in -1..1) tiles[Osm.tileKey(ty + dy, tx + dx)]?.forEach { ways[it.id] = it }

        val bearing = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 2.5f) loc.bearing else null
        var m = MapMatcher.match(ways.values, loc.latitude, loc.longitude, bearing, shown?.way?.id)
        val prev = shown
        if (m != null && bearing == null && prev != null && prev.way.id == m.way.id) m = Match(m.way, prev.forward, m.dist)

        // erst nach 2 gleichen Zuordnungen wechseln (kein Flackern an Kreuzungen)
        if (m?.way?.id == candidate?.way?.id) candidateCount++ else candidateCount = 1
        candidate = m
        if (candidateCount >= 2 || shown == null) shown = m

        val cal = Calendar.getInstance()
        val dow = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val cur = shown
        val tileLoaded = tiles.containsKey(Osm.tileKey(ty, tx))
        _state.value = _state.value.copy(
            lat = loc.latitude,
            lon = loc.longitude,
            acc = acc,
            hasFix = true,
            gps = gpsText,
            road = cur?.way?.label ?: cur?.let { roadType(it.way.highway) },
            roadKey = cur?.way?.roadKey,
            highway = cur?.way?.highway,
            limit = cur?.let { MaxSpeed.limitFor(it.way, it.forward, dow, minute) },
            tiles = tiles.size,
            loading = !tileLoaded,
        )
    }

    private fun ensure(ty: Int, tx: Int) {
        val key = Osm.tileKey(ty, tx)
        if (tiles.containsKey(key) || !loading.add(key)) return
        scope.launch(Dispatchers.IO) {
            try {
                val f = File(dir, "${ty}_$tx.json")
                val now = System.currentTimeMillis()
                val fresh = f.exists() && now - f.lastModified() < MAX_AGE_MS
                var text: String? = if (f.exists()) runCatching { f.readText() }.getOrNull() else null
                if (!fresh && SystemClock.elapsedRealtime() - (failedAt[key] ?: -RETRY_MS) >= RETRY_MS) {
                    val dl = net.withLock { download(ty, tx) }
                    if (dl != null) {
                        val tmp = File(dir, "${ty}_$tx.tmp")
                        tmp.writeText(dl)
                        tmp.renameTo(f)
                        text = dl
                        _state.value = _state.value.copy(error = null)
                    } else {
                        failedAt[key] = SystemClock.elapsedRealtime()
                    }
                }
                if (text != null) {
                    tiles[key] = Osm.parseOverpass(text)
                    trim()
                    _state.value = _state.value.copy(tiles = tiles.size)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Kachel $ty/$tx", t)
                failedAt[key] = SystemClock.elapsedRealtime()
            } finally {
                loading.remove(key)
            }
        }
    }

    /** Speicher begrenzen: weit entfernte Kacheln vergessen (bleiben auf dem Speicher). */
    private fun trim() {
        if (tiles.size <= 30) return
        val (cy, cx) = center
        tiles.keys.sortedByDescending { k -> max(abs((k shr 32).toInt() - cy), abs(k.toInt() - cx)) }
            .take(tiles.size - 25).forEach { tiles.remove(it) }
    }

    private fun download(ty: Int, tx: Int): String? {
        val body = "data=" + URLEncoder.encode(Osm.query(ty, tx), "UTF-8")
        var last: String? = null
        repeat(SERVERS.size) {
            val url = SERVERS[server % SERVERS.size]
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 45_000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("User-Agent", "DashboardW204/${BuildConfig.VERSION_NAME} (Android car head unit)")
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                if (code == 200) {
                    val text = c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect()
                    if ("\"elements\"" in text && "runtime error" !in text) return text
                    last = "Kartenserver überlastet"
                } else {
                    last = "Kartenserver: HTTP $code"
                    c.disconnect()
                }
            } catch (t: Throwable) {
                last = "Keine Internetverbindung"
            }
            server++
        }
        _state.value = _state.value.copy(error = last)
        return null
    }

    fun clearCache() {
        tiles.clear()
        dir.listFiles()?.forEach { it.delete() }
        _state.value = _state.value.copy(tiles = 0)
    }

    private fun roadType(h: String) = when (h) {
        "motorway" -> "Autobahn"
        "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link" -> "Auffahrt"
        "residential" -> "Wohnstraße"
        "living_street" -> "Verkehrsberuhigt"
        else -> null
    }

    companion object {
        private const val TAG = "MapLimits"
        private const val MAX_AGE_MS = 30L * 24 * 3600_000
        private const val RETRY_MS = 60_000L
        private val SERVERS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )
    }
}
