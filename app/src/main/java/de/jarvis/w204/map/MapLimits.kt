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
        if (!permitted()) { _state.value = State(gps = "Standort-Berechtigung fehlt"); return }
        val providers = runCatching { lm.allProviders }.getOrDefault(emptyList())
        if (LocationManager.GPS_PROVIDER !in providers) { _state.value = State(gps = "Kein GPS im Gerät gefunden"); return }
        // Alle Methoden selbst umsetzen: unter Android 10 hat das Interface noch keine Standard-Methoden
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                _state.value = _state.value.copy(hasFix = false, gps = "GPS ist ausgeschaltet")
            }
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
        } catch (t: Throwable) {
            _state.value = State(gps = "GPS nicht nutzbar: ${t.message}")
            return
        }
        listener = l
        _state.value = State(running = true, gps = "Warte auf GPS-Signal …")
        watchdog = scope.launch {
            while (isActive) {
                delay(5000)
                val s = _state.value
                if (lastFix == 0L) continue
                if (SystemClock.elapsedRealtime() - lastFix > 10_000 && s.hasFix) {
                    _state.value = s.copy(hasFix = false, gps = "GPS-Signal verloren")
                }
            }
        }
    }

    fun stop() {
        listener?.let { runCatching { lm.removeUpdates(it) } }
        listener = null
        watchdog?.cancel()
        watchdog = null
        shown = null
        candidate = null
        _state.value = State()
    }

    private fun onLocation(loc: Location) {
        lastFix = SystemClock.elapsedRealtime()
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

    private fun process(loc: Location) {
        val acc = if (loc.hasAccuracy()) loc.accuracy.roundToInt() else null
        val gpsText = "GPS ok" + (acc?.let { " ±$it m" } ?: "")
        if (acc != null && acc > 40) {
            _state.value = _state.value.copy(hasFix = false, gps = "GPS ungenau (±$acc m)")
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
            hasFix = true,
            gps = gpsText,
            road = cur?.way?.label ?: cur?.let { roadType(it.way.highway) },
            roadKey = cur?.way?.roadKey,
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
