package de.jarvis.w204

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.jarvis.w204.car.ConnState
import de.jarvis.w204.obd.ObdManager
import de.jarvis.w204.vision.Scene
import de.jarvis.w204.vision.SceneBuilder
import de.jarvis.w204.vision.VisionFrame
import de.jarvis.w204.vision.VisionPipeline
import de.jarvis.w204.vision.label
import de.jarvis.w204.voice.Jarvis
import de.jarvis.w204.voice.JarvisBrain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val _settings = MutableStateFlow(prefs.load())
    val settings: StateFlow<Settings> = _settings

    val obd = ObdManager(app, viewModelScope)
    val scanner = de.jarvis.w204.obd.EcuScanner(app, obd, viewModelScope)
    val tpms = de.jarvis.w204.tpms.TpmsManager(app, viewModelScope)
    val map = de.jarvis.w204.map.MapLimits(app, viewModelScope)
    val dashcam = de.jarvis.w204.dashcam.Dashcam(app, viewModelScope)

    /** 0 = ok/keine Daten, 1 = Druck niedrig/Batterie, 2 = Luftverlust */
    fun tireLevel(t: de.jarvis.w204.tpms.TireState, s: Settings): Int {
        if (t.noSignal) return 0
        if (t.leak) return 2
        val soll = if (t.wheel.front) s.sollFront else s.sollRear
        if (t.wheel != de.jarvis.w204.tpms.Wheel.SPARE && t.bar < soll - s.tpmsDelta) return if (t.bar < soll - 2 * s.tpmsDelta) 2 else 1
        if (t.tempC >= 85) return 1
        return 0
    }

    data class DiagState(
        val busy: Boolean = false,
        val result: de.jarvis.w204.obd.DtcResult? = null,
        val error: String? = null,
        val readAt: String? = null,
    )
    val diag = MutableStateFlow(DiagState())

    fun readFaults() {
        if (diag.value.busy) return
        diag.value = diag.value.copy(busy = true, error = null)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val r = de.jarvis.w204.obd.DtcReader.read(obd)
                val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(java.util.Date())
                diag.value = DiagState(result = r, readAt = t)
                jarvis.say(faultSummary(r))
            } catch (t: Throwable) {
                diag.value = diag.value.copy(busy = false, error = t.message ?: "Auslesen fehlgeschlagen")
            }
        }
    }

    private fun faultSummary(r: de.jarvis.w204.obd.DtcResult?): String {
        if (r == null) return "Ich habe den Fehlerspeicher noch nicht ausgelesen. Öffne dazu die Diagnose."
        val codes = r.codes.distinctBy { it.code }
        if (codes.isEmpty()) return if (r.mil == true) "Die Motorkontrollleuchte ist an, aber ich finde keinen Fehlercode." else "Keine Fehler gespeichert."
        val worst = codes.maxByOrNull { it.info.severity }!!
        val head = if (codes.size == 1) "Ein Fehler gespeichert." else "${codes.size} Fehler gespeichert."
        val urgent = when (worst.info.severity) {
            3 -> " Achtung: ${worst.info.title}. Bitte sicher anhalten."
            2 -> " Wichtigster: ${worst.info.title}. Bitte zeitnah prüfen lassen."
            else -> " Nichts Dringendes. Wichtigster: ${worst.info.title}."
        }
        return head + urgent
    }
    val jarvis = Jarvis(app)
    val regenActive get() = brainRef.regenActive
    private val brain = JarvisBrain(jarvis)
    private val brainRef get() = brain

    private val _speedLimit = MutableStateFlow<Int?>(null)
    val speedLimit: StateFlow<Int?> = _speedLimit

    /** Zusatzinfo zum Limit: "bei Nässe", "7–17 h", "innerorts", "Karte" … */
    val limitNote = MutableStateFlow<String?>(null)
    /** Limit, bei dessen Überschreitung gewarnt wird (ohne Nässe-Limits und geschätzte Kartenwerte) */
    private val _warnLimit = MutableStateFlow<Int?>(null)
    val warnLimit: StateFlow<Int?> = _warnLimit
    /** 0 = keins, 1 = Kamera, 2 = Karte */
    val limitSource = MutableStateFlow(0)
    /** Innerhalb geschlossener Ortschaft? (Kamera: Ortsschild, sonst Karte) */
    @Volatile private var inTown: Boolean? = null
    @Volatile private var townSeenAt = 0L

    /** Von der Kamera erkanntes Limit + auf welcher Straße (laut Karte) es gesehen wurde */
    private data class CamLimit(val value: Int?, val note: String?, val road: String?, val mapKmh: Int?, val mapNone: Boolean, val at: Long)
    private var cam: CamLimit? = null
    private val limitLock = Any()

    /** Überholverbot aktiv (Kamera) */
    val noPassing = MutableStateFlow(false)
    @Volatile private var noPassingAt = 0L
    @Volatile private var noPassingRoad: String? = null

    /** Überholverbot endet auch ohne Ende-Schild: nach dem Abbiegen oder spätestens nach 15 Minuten */
    private fun checkNoPassing() {
        if (!noPassing.value) return
        val now = SystemClock.elapsedRealtime()
        val key = map.state.value.takeIf { it.hasFix }?.roadKey
        if (noPassingRoad == null) noPassingRoad = key
        if (now - noPassingAt > 15 * 60_000L || (key != null && noPassingRoad != null && key != noPassingRoad && now - noPassingAt > 5_000)) {
            noPassing.value = false
        }
    }

    private val _visionFrame = MutableStateFlow<VisionFrame?>(null)
    val visionFrame: StateFlow<VisionFrame?> = _visionFrame

    private val _scene = MutableStateFlow(Scene())
    val scene: StateFlow<Scene> = _scene

    val cameraCount = MutableStateFlow(0)

    /** Bericht vom letzten Absturz + was deshalb abgeschaltet wurde. */
    val crashReport = MutableStateFlow<Pair<CrashGuard.Report, List<String>>?>(null)
    val cameraError = MutableStateFlow<String?>(null)

    /** Angeschlossene USB-Kameras (Name), alle 3 s aktualisiert */
    val usbCameraNames = MutableStateFlow<List<String>>(emptyList())
    val usbAll = MutableStateFlow<List<String>>(emptyList())

    private val sceneBuilder = SceneBuilder()
    private val ldw = de.jarvis.w204.vision.LaneDeparture()
    /** -1/+1: gerade Spurverlassenswarnung links/rechts */
    val laneDeparture = MutableStateFlow(0)
    @Volatile private var laneDepartureAt = 0L
    private val tone by lazy { runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 85) }.getOrNull() }
    private fun beep() { runCatching { tone?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 260) } }

    /** Läuft im Analyse-Thread der Kamera. */
    val vision = VisionPipeline(
        context = app,
        onFrame = { f ->
            _visionFrame.value = f
            val now = SystemClock.elapsedRealtime()
            val sc = sceneBuilder.update(f, _settings.value.calibration, now)
            _scene.value = sc
            if (_settings.value.laneWarn) {
                val d = ldw.update(sc, obd.vehicle.value.speed, now)
                if (d != 0) { laneDeparture.value = d; laneDepartureAt = now; beep() }
            }
            if (laneDeparture.value != 0 && now - laneDepartureAt > 1500) laneDeparture.value = 0
        },
        onSign = { onSignEvent(it) },
    )

    init {
        // Neue App-Version: Notabschaltungen der alten Version zurücknehmen – der Fehler ist evtl. behoben
        val sp = app.getSharedPreferences("dashboard_settings", android.content.Context.MODE_PRIVATE)
        if (sp.getInt("lastVersion", 0) != BuildConfig.VERSION_CODE) {
            sp.edit().putInt("lastVersion", BuildConfig.VERSION_CODE).apply()
            val s0 = _settings.value
            val reset = s0.copy(cameraEnabled = true, objectRecognition = true, signRecognition = true, cameraSource = 0)
            if (reset != s0) {
                _settings.value = reset
                prefs.save(reset)
            }
        }

        CrashGuard.pending?.let { report ->
            val t = report.text.lowercase()
            val st = report.stages
            val off = mutableListOf<String>()
            var s = _settings.value
            if ("vehicles_init" in st || "vehicles_run" in st || "mediapipe" in t) {
                s = s.copy(objectRecognition = false); off += "Fahrzeug- und Spurerkennung"
            }
            if ("ocr_init" in st || "ocr_run" in st || "mlkit" in t) {
                s = s.copy(signRecognition = false); off += "Schildererkennung"
            }
            val visionStage = st.any { it.startsWith("vehicles") || it.startsWith("ocr") }
            if ("usb_camera" in st || "libuvc" in t || "serenegiant" in t) {
                s = s.copy(cameraSource = 1); off += "USB-Kamera-Treiber"
            }
            if ("camera" in st || ("frame" in st && !visionStage) || (st.isEmpty() && "androidx.camera" in t)) {
                s = s.copy(cameraEnabled = false); off += "Kamera"
            }
            if (s != _settings.value) {
                _settings.value = s
                prefs.save(s)
            }
            crashReport.value = report to off
        }
        vision.recorder = dashcam
        dashcam.overlayBoxes = { _visionFrame.value?.signs ?: emptyList() }
        dashcam.overlayText = {
            val v = obd.vehicle.value
            listOfNotNull(v.speed?.let { "$it km/h" }, _speedLimit.value?.let { "Limit $it" }).joinToString("   ")
        }
        applyRuntime(_settings.value)
        if (_settings.value.tpms) tpms.start()

        // Dashcam: starke Bremsung -> Video automatisch sichern
        viewModelScope.launch {
            val hist = ArrayDeque<Pair<Long, Int>>()
            var lastEvent = 0L
            obd.vehicle.collect { v ->
                val sp = v.speed ?: return@collect
                val now = SystemClock.elapsedRealtime()
                hist.addLast(now to sp)
                while (hist.isNotEmpty() && now - hist.first().first > 1500) hist.removeFirst()
                val peak = hist.maxOf { it.second }
                // ≥ 30 km/h Geschwindigkeitsverlust in ≤ 1,5 s (≈ 5,5 m/s², deutlich mehr als normales Bremsen)
                if (_settings.value.dashcam && _settings.value.dashcamAutoSave && peak >= 35 && peak - sp >= 30 && now - lastEvent > 20_000) {
                    lastEvent = now
                    dashcam.protect("Starke Bremsung")
                    jarvis.say("Video gesichert.", "dc_brake", 20_000)
                }
            }
        }

        // Reifendruck-Warnungen
        viewModelScope.launch {
            combine(tpms.tires, _settings) { tires, s -> tires to s }.collect { (tires, s) ->
                for (t in tires.values) {
                    if (t.wheel == de.jarvis.w204.tpms.Wheel.SPARE || t.noSignal) continue
                    val bar = String.format(java.util.Locale.GERMANY, "%.1f", t.bar)
                    when {
                        t.leak -> jarvis.say("Achtung: Druckverlust ${t.wheel.label}! Bitte sicher anhalten und Reifen prüfen.", "leak_${t.wheel}", 60_000)
                        tireLevel(t, s) == 2 -> jarvis.say("Reifendruck ${t.wheel.label} sehr niedrig: $bar bar. Bitte bald anhalten und prüfen.", "low2_${t.wheel}", 120_000)
                        tireLevel(t, s) == 1 && t.tempC < 85 -> jarvis.say("Reifendruck ${t.wheel.label} niedrig: $bar bar.", "low_${t.wheel}", 15 * 60_000)
                        t.tempC >= 85 -> jarvis.say("Reifen ${t.wheel.label} ist sehr warm: ${t.tempC} Grad.", "hot_${t.wheel}", 15 * 60_000)
                    }
                    if (t.lowBattery) jarvis.say("Sensorbatterie ${t.wheel.label} ist schwach.", "batt_${t.wheel}", 24 * 3600_000L)
                }
            }
        }

        viewModelScope.launch {
            while (true) {
                usbCameraNames.value = de.jarvis.w204.vision.usbCameras(getApplication()).map { it.label() }
                usbAll.value = de.jarvis.w204.vision.usbDiagnostics(getApplication())
                kotlinx.coroutines.delay(3000)
            }
        }

        // Neue Fahrt (Zündung an / Verbindung steht) -> Begrüßung
        viewModelScope.launch {
            obd.status.map { it.state }.distinctUntilChanged().collect { st ->
                if (st == ConnState.CONNECTED || st == ConnState.DEMO) brain.onSessionStart()
                if (st == ConnState.IGNITION_OFF) { synchronized(limitLock) { cam = null; inTown = null }; recompute(); dashcam.flush(); noPassing.value = false }
            }
        }

        // Ansagen-Regeln bei jeder Änderung prüfen
        viewModelScope.launch {
            combine(obd.vehicle, obd.status, _warnLimit, _settings, _scene) { v, st, limit, s, scene ->
                if (st.live) brain.evaluate(v, limit, s.tolerance, scene.leadDistance)
            }.collect()
        }

        // Kartenlimit bei jeder neuen GPS-Position einrechnen
        viewModelScope.launch { map.state.collect { recompute(); checkNoPassing() } }
    }


    private fun setLimit(v: Int?, note: String?, say: String?) {
        val shownBefore = _speedLimit.value
        synchronized(limitLock) {
            val ms = map.state.value
            val m = if (_settings.value.mapLimits && ms.hasFix) ms.limit else null
            cam = CamLimit(v, note, if (ms.hasFix) ms.roadKey else null, m?.kmh, m?.none == true, SystemClock.elapsedRealtime())
        }
        recompute()
        // gleiches Limit wie schon angezeigt (z. B. aus der Karte) -> nicht nochmal ansagen
        if (say != null && _settings.value.announceSigns && shownBefore != v) jarvis.say(say, "sign_${v}_$note", 20_000)
    }

    /**
     * Kamera hat Vorrang. Ihr Limit gilt, bis man auf eine andere Straße abbiegt oder die Karte
     * später einen neuen Abschnitt meldet. Danach (oder ohne Schild) gilt die Karte.
     */
    private fun recompute() = synchronized(limitLock) {
        val now = SystemClock.elapsedRealtime()
        val ms = map.state.value
        val m = if (_settings.value.mapLimits && ms.hasFix) ms.limit else null
        val key = if (ms.hasFix) ms.roadKey else null

        if (m?.urban != null && now - townSeenAt > 60_000) inTown = m.urban

        var c = cam
        if (c != null && key != null) {
            val mapChanged = m?.kmh != c.mapKmh || (m?.none == true) != c.mapNone
            c = when {
                c.road == null -> c.copy(road = key, mapKmh = m?.kmh, mapNone = m?.none == true)
                c.road != key && now - c.at > 5_000 -> null                   // abgebogen
                mapChanged && now - c.at <= 30_000 -> c.copy(mapKmh = m?.kmh, mapNone = m?.none == true) // Kartenabschnitt beginnt etwas versetzt
                mapChanged -> null                                            // neuer Abschnitt laut Karte
                else -> c
            }
            cam = c
        }

        when {
            c != null -> {
                _speedLimit.value = c.value
                limitNote.value = c.note
                limitSource.value = 1
                _warnLimit.value = if (c.note == "bei Nässe") null else c.value
            }
            m != null && (m.kmh != null || m.none) -> {
                _speedLimit.value = m.kmh
                limitNote.value = when {
                    m.none -> "frei · Karte"
                    m.note != null -> m.note
                    m.estimated -> "≈ Karte"
                    else -> "Karte"
                }
                limitSource.value = 2
                _warnLimit.value = if (m.estimated) null else m.kmh
            }
            else -> {
                _speedLimit.value = null
                limitNote.value = if (m?.variable == true) "Wechselzeichen" else null
                limitSource.value = 0
                _warnLimit.value = null
            }
        }
    }

    private fun onSignEvent(e: de.jarvis.w204.vision.SignEvent) {
        when (e) {
            is de.jarvis.w204.vision.SignEvent.Limit -> {
                when {
                    e.note == "Lkw" -> return                     // gilt nicht für Pkw
                    e.note != null && !e.active -> return         // Uhrzeit passt gerade nicht
                    limitSource.value == 1 && _speedLimit.value == e.value && limitNote.value == e.note -> return
                    e.note == "bei Nässe" -> setLimit(e.value, e.note, "Tempolimit ${e.value} bei Nässe.")
                    e.note != null -> setLimit(e.value, e.note, "Tempolimit ${e.value}, ${e.note.replace("–", " bis ").replace(" h", " Uhr")}.")
                    else -> setLimit(e.value, null, "Tempolimit ${e.value}.")
                }
            }
            is de.jarvis.w204.vision.SignEvent.NoPassing -> {
                noPassingAt = SystemClock.elapsedRealtime()
                noPassingRoad = map.state.value.takeIf { it.hasFix }?.roadKey
                if (noPassing.value) return
                noPassing.value = true
                if (_settings.value.announceSigns) jarvis.say("Überholverbot.", "np", 20_000)
            }
            is de.jarvis.w204.vision.SignEvent.EndNoPassing -> {
                if (!noPassing.value) return
                noPassing.value = false
                if (_settings.value.announceSigns) jarvis.say("Ende Überholverbot.", "enp", 20_000)
            }
            is de.jarvis.w204.vision.SignEvent.EndLimit, de.jarvis.w204.vision.SignEvent.EndAll -> {
                if (e is de.jarvis.w204.vision.SignEvent.EndAll) noPassing.value = false
                // Nach der Aufhebung gilt wieder die allgemeine Regel
                when (inTown) {
                    true -> setLimit(50, "innerorts", "Ende der Begrenzung. Innerorts 50.")
                    false -> setLimit(100, "außerorts", "Ende der Begrenzung. Außerorts 100.")
                    null -> setLimit(null, null, if (e is de.jarvis.w204.vision.SignEvent.EndAll) "Ende aller Streckenverbote." else "Ende der Geschwindigkeitsbegrenzung.")
                }
            }
            is de.jarvis.w204.vision.SignEvent.TownEntry -> {
                townSeenAt = SystemClock.elapsedRealtime()
                if (inTown == true && limitSource.value == 1) return
                inTown = true
                val name = e.name?.let { " $it" } ?: ""
                setLimit(50, "innerorts", "Ortseingang$name. 50.")
            }
            is de.jarvis.w204.vision.SignEvent.TownExit -> {
                townSeenAt = SystemClock.elapsedRealtime()
                if (inTown == false && limitSource.value == 1) return
                inTown = false
                setLimit(100, "außerorts", "Ortsende. Außerorts 100.")
            }
        }
    }

    fun dismissCrash() {
        crashReport.value = null
        CrashGuard.clear()
    }

    /** Antippen des Schilds: Kamera-Limit verwerfen (danach gilt wieder die Karte) */
    fun clearLimit() {
        synchronized(limitLock) { cam = null }
        recompute()
    }

    fun restartGps() {
        map.stop()
        if (_settings.value.mapLimits) map.start()
    }

    /** Nach der Berechtigungsabfrage: GPS ggf. jetzt starten */
    fun onPermissions() {
        applyRuntime(_settings.value)
        connectIfIdle()
    }

    fun connect() {
        val s = _settings.value
        obd.start(s.deviceAddress, s.demo)
    }

    fun connectIfIdle() {
        if (obd.status.value.state == ConnState.IDLE) connect()
    }

    fun update(transform: (Settings) -> Settings) {
        val old = _settings.value
        val new = transform(old)
        if (new == old) return
        _settings.value = new
        prefs.save(new)
        applyRuntime(new)
        if (old.deviceAddress != new.deviceAddress || old.demo != new.demo) connect()
    }

    private fun applyRuntime(s: Settings) {
        tpms.setEnabled(s.tpms)
        jarvis.speakEnabled = s.voice
        vision.signsEnabled = s.signRecognition
        vision.objectsEnabled = s.objectRecognition
        vision.calibration = s.calibration
        if (s.mapLimits) map.start() else map.stop()
        dashcam.configure(de.jarvis.w204.dashcam.Dashcam.Config(s.dashcam, s.dashcamLoop, s.dashcamOverlay, s.dashcamVolume))
        recompute()
        if (!s.objectRecognition) _scene.value = Scene()
    }

    /** Dashcam-Knopf: aus -> einschalten, an -> aktuelles Video sichern */
    fun dashcamTap() {
        if (!_settings.value.dashcam) {
            update { it.copy(dashcam = true) }
            jarvis.say("Dashcam an.")
        } else {
            dashcam.protect("Manuell")
            jarvis.say("Video gesichert.", "dc_manual", 3000)
        }
    }

    fun onVoiceQuery(text: String) {
        val q = text.lowercase(java.util.Locale.GERMAN)
        if (listOf("video", "dashcam", "aufnahme", "sichern", "speicher das").any { it in q }) {
            when {
                listOf("aus", "stopp", "beenden").any { Regex("\\b$it\\b").containsMatchIn(q) } -> {
                    update { it.copy(dashcam = false) }; jarvis.say("Dashcam aus.")
                }
                !_settings.value.dashcam -> { update { it.copy(dashcam = true) }; jarvis.say("Dashcam an.") }
                else -> { dashcam.protect("Sprachbefehl"); jarvis.say("Video gesichert.") }
            }
            return
        }
        if (listOf("reifen", "luftdruck", "reifendruck").any { it in q }) {
            val t = tpms.tires.value.values.filter { it.wheel != de.jarvis.w204.tpms.Wheel.SPARE && !it.noSignal }
            if (t.isEmpty()) { jarvis.say("Ich habe noch keine Reifendaten."); return }
            val s = _settings.value
            val bad = t.filter { tireLevel(it, s) > 0 }
            val f = { x: Double -> String.format(java.util.Locale.GERMANY, "%.1f", x) }
            jarvis.say(
                if (bad.isEmpty()) "Alle Reifen in Ordnung. " + t.joinToString(", ") { "${it.wheel.short.lowercase()} ${f(it.bar)}" } + " bar."
                else bad.joinToString(" ") { "${it.wheel.label}: ${f(it.bar)} bar." } + " Bitte prüfen."
            )
            return
        }
        if (listOf("fehler", "diagnose", "fehlerspeicher", "kontrollleuchte", "motorleuchte").any { it in q }) {
            jarvis.say(faultSummary(diag.value.result))
            return
        }
        if (listOf("tempo", "limit", "schnell darf", "begrenzung", "schild").any { it in q }) {
            val l = _speedLimit.value
            val road = map.state.value.road?.let { " auf $it" } ?: ""
            jarvis.say(when {
                limitSource.value == 1 && l != null -> "Tempolimit $l, von der Kamera erkannt."
                limitSource.value == 2 && l != null -> "Laut Karte gilt$road Tempo $l" + (if (limitNote.value == "≈ Karte") ", geschätzt." else ".")
                limitSource.value == 2 -> "Laut Karte gilt$road kein Tempolimit."
                else -> "Ich kenne gerade kein Tempolimit."
            })
            return
        }
        jarvis.say(brain.answer(text, obd.vehicle.value, _speedLimit.value, _scene.value.leadDistance))
    }

    override fun onCleared() {
        tpms.release()
        runCatching { tone?.release() }
        map.stop()
        dashcam.flush()
        obd.stop()
        jarvis.shutdown()
        vision.close()
    }
}
