package de.jarvis.w204.debug

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Debug-Protokoll für die Auswertung: eine JSON-Zeile pro Ereignis in Download/W204/Debug.
 * Zeitstempel "t" = monotone Millisekunden, dieselbe Uhr wie die Video-Zeitstempel der Dashcam
 * (Abschnittsbeginn steht als "seg"-Zeile im Protokoll) -> Protokoll und Video lassen sich bildgenau zuordnen.
 * Schreibt sofort (kein "ausstehend"), damit bei Stromausfall nichts verloren geht.
 */
class DebugLog(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "debuglog") }
    private var writer: BufferedWriter? = null
    private var lines = 0
    @Volatile var active = false
        private set
    @Volatile var fileName: String? = null
        private set

    fun start(header: String) {
        if (active) return
        active = true
        exec.execute {
            try {
                val name = "W204-Debug-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(Date()) + ".txt"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/W204/Debug")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Datei nicht anlegbar")
                val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("Datei nicht beschreibbar")
                writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 64 * 1024)
                fileName = "Download/W204/Debug/$name"
                lines = 0
                write(header)
            } catch (t: Throwable) {
                Log.w("DebugLog", "Protokoll nicht startbar", t)
                active = false
            }
        }
    }

    fun log(line: String) {
        if (!active) return
        exec.execute { write(line) }
    }

    private fun write(line: String) {
        val w = writer ?: return
        runCatching {
            w.write(line)
            w.newLine()
            if (++lines % 40 == 0) w.flush()
        }
    }

    fun stop() {
        if (!active) return
        active = false
        exec.execute {
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
            writer = null
        }
    }
}
