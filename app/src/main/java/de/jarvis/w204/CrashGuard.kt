package de.jarvis.w204

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
    }
}

/**
 * Merkt sich Abstürze und zeigt sie beim nächsten Start an.
 * Java-Fehler: Stacktrace wird gespeichert.
 * Native Abstürze (z. B. in KI-Bibliotheken): "Brotkrumen" vor riskanten Schritten –
 * bleibt einer stehen, ist die App genau dort gestorben.
 */
object CrashGuard {
    private const val FILE = "last_crash.txt"
    private const val PREFIX = "stage_"
    private lateinit var sp: SharedPreferences
    private lateinit var dir: File

    data class Report(val text: String, val stages: Set<String>)

    @Volatile var pending: Report? = null
        private set

    private lateinit var times: SharedPreferences

    /** Abstürze der letzten [windowMs] (für den Notausgang zum Original-Startbildschirm) */
    fun recentCrashes(windowMs: Long): Int {
        if (!::times.isInitialized) return 0
        val now = System.currentTimeMillis()
        return (times.getString("t", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.count { now - it < windowMs }
    }

    fun clearCrashTimes() {
        if (::times.isInitialized) times.edit().remove("t").commit()
    }

    fun install(context: Context) {
        times = context.getSharedPreferences("crashtimes", Context.MODE_PRIVATE)
        sp = context.getSharedPreferences("crashguard", Context.MODE_PRIVATE)
        dir = context.filesDir

        val stuck = sp.all.keys.filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }.toSet()
        val file = File(dir, FILE)
        val javaReport = if (file.exists()) runCatching { file.readText() }.getOrNull() else null
        if (javaReport != null || stuck.isNotEmpty()) {
            val text = javaReport ?: (
                "Die App wurde ohne Java-Fehlermeldung beendet (nativer Absturz).\n" +
                    "Zuletzt aktiv: ${stuck.joinToString { describe(it) }}\n\n" + deviceInfo()
                )
            pending = Report(text, stuck)
        }
        file.delete()
        sp.edit().clear().commit()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val now = System.currentTimeMillis()
                val old = (times.getString("t", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < 600_000L }
                times.edit().putString("t", (old + now).joinToString(",")).commit()
            }
            runCatching {
                val time = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date())
                File(dir, FILE).writeText(
                    "Absturz am $time im Thread \"${thread.name}\"\n" +
                        "Zuletzt aktiv: ${activeStages().joinToString { describe(it) }.ifBlank { "–" }}\n\n" +
                        deviceInfo() + "\n\n" + Log.getStackTraceString(error)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun clear() {
        pending = null
    }

    fun <T> guard(stage: String, block: () -> T): T {
        enter(stage)
        try {
            return block()
        } finally {
            leave(stage)
        }
    }

    fun enter(stage: String) {
        if (::sp.isInitialized) sp.edit().putBoolean(PREFIX + stage, true).commit()
    }

    fun leave(stage: String) {
        if (::sp.isInitialized) sp.edit().remove(PREFIX + stage).commit()
    }

    private fun activeStages(): Set<String> =
        if (!::sp.isInitialized) emptySet()
        else sp.all.keys.filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }.toSet()

    fun describe(stage: String): String = when (stage) {
        "camera" -> "Kamera starten"
        "usb_camera" -> "USB-Kamera öffnen"
        "frame" -> "erstes Kamerabild verarbeiten"
        "vehicles_init" -> "Fahrzeugerkennung laden"
        "vehicles_run" -> "Fahrzeugerkennung"
        "ocr_init" -> "Schildererkennung laden"
        "ocr_run" -> "Schildererkennung"
        else -> stage
    }

    private fun deviceInfo(): String =
        "Gerät: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Chip: ${Build.HARDWARE} · ABIs: ${Build.SUPPORTED_ABIS.joinToString()}\n" +
            "App: ${BuildConfig.VERSION_NAME}"
}
