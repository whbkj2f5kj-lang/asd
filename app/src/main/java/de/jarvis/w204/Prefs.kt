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
            .putInt("cam", s.cameraIndex)
            .putFloat("camH", s.camHeight)
            .putFloat("camFov", s.camFov)
            .putFloat("horizon", s.horizon)
            .apply()
    }
}
