package de.jarvis.w204.volta

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.Choreographer
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.IndirectLight
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import de.jarvis.w204.CrashGuard
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Das eigene 3D-Modell (glTF-Binärdatei) liegt im App-Speicher. */
object CarModel {
    fun file(c: Context) = File(c.filesDir, "car3d.glb")
    fun exists(c: Context) = file(c).let { it.exists() && it.length() > 1000 }

    /** Vom Nutzer gewählte .glb-Datei übernehmen; liefert Fehlertext oder null */
    fun import(c: Context, uri: Uri): String? = runCatching {
        val tmp = File(c.filesDir, "car3d.tmp")
        c.contentResolver.openInputStream(uri)?.use { inp -> tmp.outputStream().use { inp.copyTo(it) } } ?: return "Datei nicht lesbar"
        val head = tmp.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        if (String(head, Charsets.US_ASCII) != "glTF") { tmp.delete(); return "Keine .glb-Datei (glTF-Binärformat nötig)" }
        if (tmp.length() > 150L * 1024 * 1024) { tmp.delete(); return "Datei zu groß (über 150 MB)" }
        tmp.renameTo(file(c))
        null
    }.getOrElse { "Fehler: ${it.message}" }

    fun remove(c: Context) { file(c).delete() }
}

private var filamentReady = false

/**
 * 3D-Auto mit Filament: durchsichtiger Hintergrund, mit dem Finger drehbar.
 * Gezeichnet wird nur, solange sich etwas bewegt – spart Leistung für die Schildererkennung.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun Car3D(version: Int, modifier: Modifier = Modifier, glow: Color = Color(0xFF1E88FF)) {
    Box(modifier) {
        // Lichtkegel von oben + Bodenschatten (wie Studio-Licht)
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawRect(Brush.radialGradient(listOf(glow.copy(alpha = 0.32f), Color.Transparent), Offset(w * 0.5f, h * 0.35f), w * 0.42f))
            drawOval(
                Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent), Offset(w * 0.5f, h * 0.78f), w * 0.3f),
                topLeft = Offset(w * 0.2f, h * 0.70f), size = Size(w * 0.6f, h * 0.16f),
            )
        }
        if (LocalInspectionMode.current) return@Box
        val ctx = androidx.compose.ui.platform.LocalContext.current
        if (!CarModel.exists(ctx)) return@Box

        val holder = remember(version) { arrayOfNulls<Any>(1) }
        androidx.compose.runtime.key(version) { AndroidView(
            factory = { c ->
                TextureView(c).apply {
                    isOpaque = false
                    if (!filamentReady) { Utils.init(); filamentReady = true }
                    val ui = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { isOpaque = false }
                    val mv = ModelViewer(this, uiHelper = ui)
                    mv.view.blendMode = View.BlendMode.TRANSLUCENT
                    mv.renderer.clearOptions = mv.renderer.clearOptions.apply {
                        clear = true
                        clearColor = floatArrayOf(0f, 0f, 0f, 0f)
                    }
                    // weiches Umgebungslicht (ohne Bilddatei: nur Grundhelligkeit)
                    mv.scene.indirectLight = IndirectLight.Builder()
                        .irradiance(1, floatArrayOf(0.9f, 0.95f, 1.0f))
                        .intensity(35_000f)
                        .build(mv.engine)
                    CrashGuard.guard("car3d") {
                        val bytes = CarModel.file(c).readBytes()
                        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
                        buf.rewind()
                        mv.loadModelGlb(buf)
                        mv.transformToUnitCube()
                    }
                    var renderUntil = System.nanoTime() + 3_000_000_000L
                    setOnTouchListener { _, e ->
                        mv.onTouchEvent(e)
                        renderUntil = System.nanoTime() + 1_500_000_000L
                        true
                    }
                    val cb = object : Choreographer.FrameCallback {
                        override fun doFrame(t: Long) {
                            Choreographer.getInstance().postFrameCallback(this)
                            if (t < renderUntil) runCatching { mv.render(t) }
                        }
                    }
                    Choreographer.getInstance().postFrameCallback(cb)
                    holder[0] = cb
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) }
        DisposableEffect(version) {
            onDispose {
                (holder[0] as? Choreographer.FrameCallback)?.let { Choreographer.getInstance().removeFrameCallback(it) }
            }
        }
    }
}
