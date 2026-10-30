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
    val jarvis = Jarvis(app)
    private val brain = JarvisBrain(jarvis)

    private val _speedLimit = MutableStateFlow<Int?>(null)
    val speedLimit: StateFlow<Int?> = _speedLimit

    private val _visionFrame = MutableStateFlow<VisionFrame?>(null)
    val visionFrame: StateFlow<VisionFrame?> = _visionFrame

    private val _scene = MutableStateFlow(Scene())
    val scene: StateFlow<Scene> = _scene

    val cameraCount = MutableStateFlow(0)
    val cameraError = MutableStateFlow<String?>(null)

    private val sceneBuilder = SceneBuilder()

    /** Läuft im Analyse-Thread der Kamera. */
    val vision = VisionPipeline(
        context = app,
        onFrame = { f ->
            _visionFrame.value = f
            _scene.value = sceneBuilder.update(f, _settings.value.calibration, SystemClock.elapsedRealtime())
        },
        onLimit = { onLimitDetected(it) },
    )

    init {
        applyRuntime(_settings.value)

        // Neue Fahrt (Zündung an / Verbindung steht) -> Begrüßung
        viewModelScope.launch {
            obd.status.map { it.state }.distinctUntilChanged().collect { st ->
                if (st == ConnState.CONNECTED || st == ConnState.DEMO) brain.onSessionStart()
                if (st == ConnState.IGNITION_OFF) _speedLimit.value = null
            }
        }

        // Ansagen-Regeln bei jeder Änderung prüfen
        viewModelScope.launch {
            combine(obd.vehicle, obd.status, _speedLimit, _settings, _scene) { v, st, limit, s, scene ->
                if (st.live) brain.evaluate(v, limit, s.tolerance, scene.leadDistance)
            }.collect()
        }
    }

    private fun onLimitDetected(v: Int) {
        if (_speedLimit.value == v) return
        _speedLimit.value = v
        if (_settings.value.announceSigns) jarvis.say("Tempolimit $v.", "sign$v", 20_000)
    }

    fun clearLimit() {
        _speedLimit.value = null
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
        jarvis.speakEnabled = s.voice
        vision.signsEnabled = s.signRecognition
        vision.objectsEnabled = s.objectRecognition
        vision.calibration = s.calibration
        if (!s.objectRecognition) _scene.value = Scene()
    }

    fun onVoiceQuery(text: String) {
        jarvis.say(brain.answer(text, obd.vehicle.value, _speedLimit.value, _scene.value.leadDistance))
    }

    override fun onCleared() {
        obd.stop()
        jarvis.shutdown()
        vision.close()
    }
}
