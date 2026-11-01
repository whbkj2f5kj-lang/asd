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
        /** Fahrtrichtung in Grad (0 = Nord), null wenn zu langsam */
        val bearing: Float? = null,
        /** Tempo laut GPS in m/s */
        val speedMs: Float? = null,
        /** monotone Zeit der Position (ms) */
        val fixAt: Long = 0L,
        /** Dauer des letzten Kartendownloads (Anzeige) */
        val lastDownload: String? = null,
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

    @Volatile private var shown: Match? = null
    private var candidate: Match? = null
    private var candidateCount = 0

    fun permitted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---- Positionsfilter + Koppelnavigation
    private val filter = PosFilter()
    private val filterLock = Any()
    @Volatile private var obdSpeedMs: Float? = null
    @Volatile private var obdSpeedAt = 0L
    private val saved = ctx.getSharedPreferences("lastpos", Context.MODE_PRIVATE)
    private var savedAt = 0L
    @Volatile private var lastMatchAt = 0L

    /** Tempo vom Tacho (OBD): rechnet zwischen den GPS-Punkten und bei Ausfall weiter */
    fun setVehicleSpeed(kmh: Int?) {
        obdSpeedMs = kmh?.let { it / 3.6f }
        obdSpeedAt = SystemClock.elapsedRealtime()
    }

    private fun obdSpeed(): Float? = obdSpeedMs.takeIf { SystemClock.elapsedRealtime() - obdSpeedAt < 3000 }

    /** Weiterrechnen entlang der zugeordneten Straße */
    private val follower = PosFilter.Follower { lat, lon, h, m ->
        val sh = shown ?: return@Follower null
        MapMatcher.advance(sh.way, lat, lon, h, m)
    }

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
                if (provider == LocationManager.GPS_PROVIDER) gpsOff = true
            }
        }
        // GPS + (falls vorhanden) Netz/Fused als Rückfallebene; "passive" bekommt zusätzlich jede Position,
        // die andere Apps (Navi, CarPlay …) gerade abfragen
        var any = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused", LocationManager.PASSIVE_PROVIDER)) {
            if (p !in providers) continue
            try {
                lm.requestLocationUpdates(p, if (p == LocationManager.GPS_PROVIDER) 0L else 1000L, 0f, l, Looper.getMainLooper())
                any = true
            } catch (t: Throwable) {
                Log.w(TAG, "Standortquelle $p nicht nutzbar", t)
            }
        }
        // Google-Standortdienst: dieselbe Quelle wie Google Maps – liefert oft schon eine Position,
        // wenn der GPS-Chip allein noch keinen Fix hat
        if (startGms()) any = true
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

        // Startposition: zuletzt gespeicherte (Auto steht meist noch da, wo es abgestellt wurde)
        // oder die letzte bekannte des Systems – je nachdem, was neuer ist. Karte ist dann sofort da.
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        val sLat = saved.getString("lat", null)?.toDoubleOrNull()
        val sLon = saved.getString("lon", null)?.toDoubleOrNull()
        val sTime = saved.getLong("time", 0L)
        val sHead = saved.getFloat("head", -1f).takeIf { it >= 0f }
        val seed = when {
            last != null && (sLat == null || last.time > sTime) -> Triple(last.latitude, last.longitude, null as Float?)
            sLat != null && sLon != null -> Triple(sLat, sLon, sHead)
            else -> null
        }
        seed?.let { (la, lo, h) ->
            synchronized(filterLock) { filter.seed(la, lo, h, SystemClock.elapsedRealtime()) }
            scope.launch(Dispatchers.Default) { prefetch(la, lo) }
        }

        _state.value = State(running = true, gps = if (gpsOff) "Standort ist am Radio ausgeschaltet" else "Warte auf GPS-Signal …")
        // 5× pro Sekunde: Position weiterrechnen (Tacho) und veröffentlichen
        watchdog = scope.launch {
            var tick = 0
            while (isActive) {
                delay(200)
                tick++
                runCatching { tickPosition(tick % 10 == 0) }.onFailure { Log.w(TAG, "Positionstakt", it) }
            }
        }
    }

    private var gmsClient: com.google.android.gms.location.FusedLocationProviderClient? = null
    private var gmsCb: com.google.android.gms.location.LocationCallback? = null
    @Volatile private var lastGmsFix = 0L

    private fun startGms(): Boolean = try {
        val avail = com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx)
        if (avail != com.google.android.gms.common.ConnectionResult.SUCCESS) false else {
            val client = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(ctx)
            val req = com.google.android.gms.location.LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000L,
            ).setMinUpdateIntervalMillis(500L).setWaitForAccurateLocation(false).build()
            val cb = object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(r: com.google.android.gms.location.LocationResult) {
                    lastGmsFix = SystemClock.elapsedRealtime()
                    r.locations.forEach { onLocation(it) }
                }
            }
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())
            gmsClient = client
            gmsCb = cb
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Google-Standort nicht verfügbar", t)
        false
    }

    @Volatile private var gpsOff = false
    @Volatile private var startedAt = 0L
    @Volatile private var lastGpsFix = 0L
    @Volatile private var lastChipFix = 0L
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
        gmsCb?.let { cb -> runCatching { gmsClient?.removeLocationUpdates(cb) } }
        gmsCb = null
        gmsClient = null
        watchdog?.cancel()
        watchdog = null
        synchronized(filterLock) { filter.pose?.let { savePos(it, force = true) } }
        shown = null
        candidate = null
        _state.value = State()
    }

    private fun onLocation(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        lastFix = now
        val acc = if (loc.hasAccuracy()) loc.accuracy else 50f
        // Fused (Google) ist meist GPS-gestützt: genau genug zählt wie GPS
        val isGps = loc.provider == LocationManager.GPS_PROVIDER || (loc.provider == "fused" && acc <= 30f)
        if (loc.provider == LocationManager.GPS_PROVIDER) lastChipFix = now
        if (isGps) lastGpsFix = now else {
            lastOtherFix = now
            // Netz-Position nur nutzen, solange GPS nichts liefert
            if (now - lastGpsFix < 5000) return
            if (acc > 2000f) return
        }
        // Zeitpunkt der Messung (das Radio liefert sie oft verzögert aus)
        val at = (loc.elapsedRealtimeNanos / 1_000_000L).takeIf { it in (now - 5000)..now } ?: now
        val spd = if (loc.hasSpeed()) loc.speed else null
        val brg = if (loc.hasBearing()) loc.bearing else null
        val ok = synchronized(filterLock) {
            filter.update(loc.latitude, loc.longitude, acc, brg, spd, isGps, at, obdSpeed(), follower)
        }
        if (!ok) return
        val p = synchronized(filterLock) { filter.pose } ?: return
        if (isGps && acc <= 50f) savePos(p, force = false)
        if (!busy.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Default) {
            try {
                rematch(p)
            } catch (t: Throwable) {
                Log.w(TAG, "Kartenabgleich fehlgeschlagen", t)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun savePos(p: PosFilter.Pose, force: Boolean) {
        if (p.source == PosFilter.Source.SAVED) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - savedAt < 20_000) return
        savedAt = now
        saved.edit()
            .putString("lat", p.lat.toString())
            .putString("lon", p.lon.toString())
            .putFloat("head", p.heading ?: -1f)
            .putLong("time", System.currentTimeMillis())
            .apply()
    }

    /** Takt: weiterrechnen, veröffentlichen, bei Koppelnavigation regelmäßig neu zuordnen */
    private fun tickPosition(slow: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val p = synchronized(filterLock) { filter.predict(now, obdSpeed(), follower) }
        val s = _state.value
        val text = statusText(p, now)
        if (p == null) {
            if (slow) _state.value = s.copy(hasFix = false, gps = text)
            return
        }
        val dead = p.source == PosFilter.Source.DEAD_RECKONING
        val sinceFix = now - p.lastFixAt
        // Tempolimit der Straße bleibt bei kurzem Ausfall (Tunnel) gültig, solange wir der Straße folgen
        val fix = when (p.source) {
            PosFilter.Source.SAVED -> false
            PosFilter.Source.DEAD_RECKONING -> s.hasFix && sinceFix < 60_000 && shown != null
            else -> s.hasFix
        }
        _state.value = s.copy(
            lat = p.lat, lon = p.lon, acc = p.acc.roundToInt(),
            bearing = p.heading ?: s.bearing,
            speedMs = p.speedMs,
            fixAt = now,
            hasFix = fix,
            gps = if (slow || dead || p.source == PosFilter.Source.SAVED || s.gps.startsWith("Warte")) text else s.gps,
        )
        if (dead && now - lastMatchAt > 1500 && busy.compareAndSet(false, true)) {
            scope.launch(Dispatchers.Default) {
                try { rematch(p) } catch (t: Throwable) { Log.w(TAG, "Kartenabgleich", t) } finally { busy.set(false) }
            }
        }
    }

    private fun statusText(p: PosFilter.Pose?, now: Long): String {
        if (gpsOff) return "Standort ist am Radio ausgeschaltet – in den Android-Einstellungen einschalten"
        val sats = "Satelliten: $satsVisible sichtbar, $satsGood mit gutem Signal, $satsUsed genutzt"
        val wait = (now - startedAt) / 1000
        val hint = when {
            wait > 120 && satsVisible == 0 -> " – keine Satelliten: GPS-Antenne am Radio prüfen"
            wait > 120 && satsGood < 4 -> " – Signal zu schwach: Antenne frei unter die Scheibe/aufs Dach"
            else -> ""
        }
        return when (p?.source) {
            null -> "Warte auf GPS-Signal (${wait / 60}:${"%02d".format(wait % 60)}) · $sats$hint"
            PosFilter.Source.SAVED -> "Letzte bekannte Position – warte auf GPS (${wait / 60}:${"%02d".format(wait % 60)}) · $sats$hint"
            PosFilter.Source.DEAD_RECKONING -> {
                val gone = (now - p.lastFixAt) / 1000
                if (gone * 1000 < PosFilter.MAX_DEAD_MS && obdSpeed() != null) "GPS-Signal schwach – Position aus Tacho geschätzt (seit $gone s) · $sats"
                else "GPS-Signal verloren · $sats$hint"
            }
            PosFilter.Source.NETWORK -> (if (now - lastGmsFix < 5000) "Standort über Google-Dienste" else "Nur ungefährer Standort (Netz)") + " ±${p.acc.roundToInt()} m · $sats"
            PosFilter.Source.GPS -> "GPS ok ±${p.acc.roundToInt()} m · " + (if (now - lastGmsFix < 5000 && now - lastChipFix > 5000) "über Google-Dienste" else "$satsUsed Satelliten")
        }
    }

    /** Kartenstücke rund um eine (auch ungenaue) Position schon laden */
    private fun prefetch(lat: Double, lon: Double) {
        val (ty, tx) = Osm.tileOf(lat, lon)
        center = ty to tx
        ensure(ty, tx)
        for (dy in -1..1) for (dx in -1..1) if (dy != 0 || dx != 0) ensure(ty + dy, tx + dx)
    }

    /** Straße + Tempolimit zur (gefilterten) Position bestimmen */
    private fun rematch(p: PosFilter.Pose) {
        lastMatchAt = SystemClock.elapsedRealtime()
        val acc = p.acc.roundToInt()
        if (acc < 1000) prefetch(p.lat, p.lon)
        // Straßen zuordnen nur bei ausreichender Genauigkeit
        if (acc > 45 || p.source == PosFilter.Source.SAVED) {
            if (p.source != PosFilter.Source.DEAD_RECKONING) _state.value = _state.value.copy(hasFix = false)
            return
        }
        val (ty, tx) = Osm.tileOf(p.lat, p.lon)
        val ways = HashMap<Long, Way>()
        for (dy in -1..1) for (dx in -1..1) tiles[Osm.tileKey(ty + dy, tx + dx)]?.forEach { ways[it.id] = it }

        val bearing = p.heading?.takeIf { p.speedMs > 2.5f }
        var m = MapMatcher.match(ways.values, p.lat, p.lon, bearing, shown?.way?.id)
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
                    val t0 = SystemClock.elapsedRealtime()
                    val dl = net.withLock { download(ty, tx) }
                    val sec = (SystemClock.elapsedRealtime() - t0) / 1000.0
                    _state.value = _state.value.copy(lastDownload = String.format(java.util.Locale.GERMANY, "%.1f s", sec) + if (dl == null) " (fehlgeschlagen)" else "")
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

    private fun download(ty: Int, tx: Int): String? = downloadQuery(Osm.query(ty, tx))

    private fun downloadQuery(query: String): String? {
        val body = "data=" + URLEncoder.encode(query, "UTF-8")
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

    // ---------------------------------------------------------------- 3D-Karte

    /** Straßen rund um eine Position (aus den geladenen Kartenstücken) */
    fun waysAround(lat: Double, lon: Double): List<Way> {
        val (ty, tx) = Osm.tileOf(lat, lon)
        val out = HashMap<Long, Way>()
        for (dy in -1..1) for (dx in -1..1) tiles[Osm.tileKey(ty + dy, tx + dx)]?.forEach { out[it.id] = it }
        return out.values.toList()
    }

    /** Gebäude nur laden, solange die Kartenansicht sichtbar ist (spart Daten) */
    @Volatile var wantBuildings = false
    private val bTiles = ConcurrentHashMap<Long, List<Building>>()
    private val bLoading = ConcurrentHashMap.newKeySet<Long>()
    private val bFailed = ConcurrentHashMap<Long, Long>()
    private val bDir = File(ctx.filesDir, "osm_b").apply { mkdirs() }

    fun buildingsAround(lat: Double, lon: Double): List<Building> {
        val (ty, tx) = Buildings.tileOf(lat, lon)
        val out = ArrayList<Building>()
        for (dy in -1..1) for (dx in -1..1) {
            val key = Osm.tileKey(ty + dy, tx + dx)
            val list = bTiles[key]
            if (list != null) out += list else if (wantBuildings) ensureBuildings(ty + dy, tx + dx)
        }
        return out
    }

    private fun ensureBuildings(ty: Int, tx: Int) {
        val key = Osm.tileKey(ty, tx)
        if (bTiles.containsKey(key) || !bLoading.add(key)) return
        scope.launch(Dispatchers.IO) {
            try {
                val f = File(bDir, "${ty}_$tx.json")
                var text: String? = if (f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS * 3) runCatching { f.readText() }.getOrNull() else null
                if (text == null && SystemClock.elapsedRealtime() - (bFailed[key] ?: -RETRY_MS) >= RETRY_MS) {
                    text = net.withLock { downloadQuery(Buildings.query(ty, tx)) }
                    if (text != null) { val tmp = File(bDir, "${ty}_$tx.tmp"); tmp.writeText(text); tmp.renameTo(f) }
                    else bFailed[key] = SystemClock.elapsedRealtime()
                }
                if (text != null) {
                    bTiles[key] = Osm.parseOverpass(text).mapNotNull { Buildings.from(it) }
                    if (bTiles.size > 40) bTiles.keys.take(bTiles.size - 30).forEach { bTiles.remove(it) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Gebäude $ty/$tx", t)
                bFailed[key] = SystemClock.elapsedRealtime()
            } finally {
                bLoading.remove(key)
            }
        }
    }

    fun clearCache() {
        bTiles.clear()
        bDir.listFiles()?.forEach { it.delete() }
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
