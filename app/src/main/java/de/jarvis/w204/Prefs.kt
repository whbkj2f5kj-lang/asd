package de.jarvis.w204

import android.content.Context
import de.jarvis.w204.vision.Calibration

data class Settings(
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val demo: Boolean = false,
    val voice: Boolean = true,
    val announceSigns: Boolean = true,
    val tolerance: Int = 5,
    val signRecognition: Boolean = true,
    val objectRecognition: Boolean = true,
    val cameraEnabled: Boolean = true,
    /** 0 = automatisch, 1 = Android-Kamera, 2 = USB direkt */
    val cameraSource: Int = 0,
    /** 0 = Modern (Pfeil-Instrumente), 1 = Klassisch (Rundinstrumente) */
    val layout: Int = 0,
    /** Farbwelt: 0 Kupfer, 1 Eisblau, 2 Sport-Rot, 3 Amber */
    val palette: Int = 0,
    val tpms: Boolean = true,
    val sollFront: Float = 2.4f,
    val sollRear: Float = 2.6f,
    val tpmsDelta: Float = 0.3f,
    val cameraIndex: Int = 0,
    val camHeight: Float = 1.25f,
    val camFov: Float = 90f,
    val horizon: Float = 0.5f,
) {
    val calibration: Calibration get() = Calibration(camHeight, horizon, camFov)
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dashboard_settings", Context.MODE_PRIVATE)

    fun load() = Settings(
        deviceAddress = sp.getString("addr", null),
        deviceName = sp.getString("name", null),
        demo = sp.getBoolean("demo", false),
        voice = sp.getBoolean("voice", true),
        announceSigns = sp.getBoolean("announce", true),
        tolerance = sp.getInt("tol", 5),
        signRecognition = sp.getBoolean("signs", true),
        objectRecognition = sp.getBoolean("objects", true),
        cameraEnabled = sp.getBoolean("camOn", true),
        cameraSource = sp.getInt("camSrc", 0),
        layout = sp.getInt("layout", 0),
        palette = sp.getInt("palette", 0),
        tpms = sp.getBoolean("tpms", true),
        sollFront = sp.getFloat("sollF", 2.4f),
        sollRear = sp.getFloat("sollR", 2.6f),
        tpmsDelta = sp.getFloat("tpmsD", 0.3f),
        cameraIndex = sp.getInt("cam", 0),
        camHeight = sp.getFloat("camH", 1.25f),
        camFov = sp.getFloat("camFov", 90f),
        horizon = sp.getFloat("horizon", 0.5f),
    )

    fun save(s: Settings) {
        sp.edit()
            .putString("addr", s.deviceAddress)
            .putString("name", s.deviceName)
            .putBoolean("demo", s.demo)
            .putBoolean("voice", s.voice)
            .putBoolean("announce", s.announceSigns)
            .putInt("tol", s.tolerance)
            .putBoolean("signs", s.signRecognition)
            .putBoolean("objects", s.objectRecognition)
            .putBoolean("camOn", s.cameraEnabled)
            .putInt("camSrc", s.cameraSource)
            .putInt("layout", s.layout)
            .putInt("palette", s.palette)
            .putBoolean("tpms", s.tpms)
            .putFloat("sollF", s.sollFront)
            .putFloat("sollR", s.sollRear)
            .putFloat("tpmsD", s.tpmsDelta)
            .putInt("cam", s.cameraIndex)
            .putFloat("camH", s.camHeight)
            .putFloat("camFov", s.camFov)
            .putFloat("horizon", s.horizon)
            .apply()
    }
}
