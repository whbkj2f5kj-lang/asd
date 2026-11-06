package de.jarvis.w204.vision

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import de.jarvis.w204.CrashGuard
import kotlin.math.abs

/** Ist ein USB-Gerät eine Kamera (UVC = USB-Video-Klasse)? */
fun UsbDevice.isUvcCamera(): Boolean {
    if (deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
    for (i in 0 until interfaceCount) {
        if (getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
    }
    return false
}

/** Hat die Kamera ein eingebautes Mikrofon? Dann verlangt Android für den USB-Zugriff die Mikrofon-Berechtigung. */
fun UsbDevice.hasAudio(): Boolean = (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }

private fun granted(c: Context, p: String) =
    androidx.core.content.ContextCompat.checkSelfPermission(c, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

/** Warum Android den Zugriff ohne Nachfrage verweigern würde (oder null) */
fun usbCameraBlocker(c: Context, d: UsbDevice): String? = when {
    !granted(c, android.Manifest.permission.CAMERA) ->
        "Android verlangt für USB-Kameras die Kamera-Berechtigung.\nEinstellungen → Apps → Dashboard W204 → Berechtigungen → Kamera erlauben."
    d.hasAudio() && !granted(c, android.Manifest.permission.RECORD_AUDIO) ->
        "Diese Kamera hat ein eingebautes Mikrofon – dafür verlangt Android die Mikrofon-Berechtigung, sonst wird der Zugriff ohne Nachfrage verweigert.\nEinstellungen → Apps → Dashboard W204 → Berechtigungen → Mikrofon erlauben, dann Kamera neu anstecken."
    else -> null
}

fun usbCameras(context: Context): List<UsbDevice> = try {
    context.getSystemService(UsbManager::class.java)?.deviceList?.values.orEmpty().filter { it.isUvcCamera() }
} catch (t: Throwable) {
    emptyList()
}

/** Alle USB-Geräte mit Klassen – zur Diagnose, wenn eine Kamera nicht erkannt wird. */
fun usbDiagnostics(context: Context): List<String> = try {
    val manager = context.getSystemService(UsbManager::class.java)
    manager?.deviceList?.values.orEmpty().map { d ->
        val classes = (0 until d.interfaceCount).map { d.getInterface(it).interfaceClass }.distinct()
        val kind = when {
            d.isUvcCamera() -> "KAMERA"
            8 in classes -> "Speicher"
            9 == d.deviceClass -> "Hub"
            3 in classes -> "Eingabe"
            1 in classes -> "Audio"
            2 in classes || 10 in classes -> "Netzwerk/Seriell"
            else -> "Sonstiges"
        }
        val name = listOfNotNull(
            runCatching { d.manufacturerName }.getOrNull(),
            runCatching { d.productName }.getOrNull(),
        ).joinToString(" ").ifBlank { "unbenannt" }
        "$kind: $name · %04X:%04X · Klasse ${d.deviceClass}, Schnittstellen $classes".format(d.vendorId, d.productId)
    }.ifEmpty { listOf("Android meldet gar keine USB-Geräte") }
} catch (t: Throwable) {
    listOf("USB-Abfrage fehlgeschlagen: ${t.message}")
}

fun UsbDevice.label(): String {
    val name = runCatching { productName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "USB-Kamera"
    return "$name (%04X:%04X)".format(vendorId, productId)
}

/**
 * Reihenfolge der Versuche: MJPEG zuerst (komprimiert, passt sicher durch USB 2.0), bis 1280×720,
 * 16:9 bevorzugt, mindestens 15 B/s. Danach unkomprimiert, zuletzt alles andere.
 * Viele neue Kameras (z. B. IMX662) liefern unkomprimiert nur sehr kleine Bilder oder 5 B/s –
 * oder melden H.264/H.265, das der Treiber nicht entpackt.
 */
internal fun sizeCandidates(sizes: List<Size>?): List<Size> {
    if (sizes.isNullOrEmpty()) return emptyList()
    val known = sizes.filter { it.type == UVCCamera.UVC_VS_FRAME_MJPEG || it.type == UVCCamera.UVC_VS_FRAME_UNCOMPRESSED }
    fun score(s: Size): Int {
        var v = 0
        if (s.type == UVCCamera.UVC_VS_FRAME_MJPEG) v += 4_000_000
        if (s.width <= 1280 && s.height <= 720) v += 2_000_000
        if (s.fps == 0 || s.fps >= 15) v += 1_000_000
        if (abs(s.width * 9 - s.height * 16) < s.width / 20) v += 500_000
        v += minOf(s.width * s.height, 1280 * 720) / 2
        return v
    }
    return known.sortedByDescending(::score).distinctBy { Triple(it.type, it.width, it.height) }.take(6)
}

private fun Size.describe() = "${if (type == UVCCamera.UVC_VS_FRAME_MJPEG) "MJPEG" else "YUV"} ${width}×$height" + (if (fps > 0) " @$fps" else "")

/**
 * Kamerabild direkt über USB (eigener UVC-Treiber), für Radios, die USB-Kameras
 * nicht an Android durchreichen. Liefert die Bilder zusätzlich an die Erkennung.
 */
@Composable
fun UsbCameraFeed(
    vision: VisionPipeline,
    onStatus: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    val textureView = remember { TextureView(context) }

    DisposableEffect(Unit) {
        val helper: ICameraHelper = CameraHelper()
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)
        var candidates: List<Size> = emptyList()
        var attempt = 0
        var formats = ""
        fun tryOpen() {
            val c = candidates.getOrNull(attempt)
            if (c != null) {
                onStatus("Öffne USB-Kamera: ${c.describe()} (Versuch ${attempt + 1}/${candidates.size})")
                runCatching { helper.openCamera(c) }.onFailure { onStatus("USB-Kamera: ${c.describe()} geht nicht – ${it.message}") }
            } else {
                onStatus("Öffne USB-Kamera mit Standardeinstellung …")
                runCatching { helper.openCamera() }
            }
        }
        var texture: SurfaceTexture? = textureView.surfaceTexture

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                texture = st
                if (opened.get()) helper.addSurface(st, false)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                if (opened.get()) helper.removeSurface(st)
                texture = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        onStatus("Suche USB-Kamera …")
        helper.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                if (device.isUvcCamera()) {
                    val block = usbCameraBlocker(context, device)
                    if (block != null) { onStatus(block); return }
                    onStatus("USB-Kamera gefunden – Zugriff erlauben")
                    helper.selectDevice(device)
                }
            }

            override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                CrashGuard.enter("usb_camera")
                val all = runCatching { helper.supportedSizeList }.getOrNull()
                formats = all.orEmpty().joinToString(", ") { it.describe() + "/t${it.type}" }.take(400)
                candidates = sizeCandidates(all)
                attempt = 0
                if (all.isNullOrEmpty()) onStatus("USB-Kamera meldet keine Formate – versuche Standard …")
                tryOpen()
            }

            override fun onCameraOpen(device: UsbDevice) {
                helper.startPreview()
                val s = helper.previewSize
                val w = s?.width ?: 640
                val h = s?.height ?: 480
                aspect = w.toFloat() / h
                opened.set(true)
                texture?.let { helper.addSurface(it, false) }
                helper.setFrameCallback({ frame -> vision.submitRgbx(frame, w, h) }, UVCCamera.PIXEL_FORMAT_RGBX)
                CrashGuard.leave("usb_camera")
                onStatus(null)
            }

            override fun onCameraClose(device: UsbDevice) {
                opened.set(false)
                texture?.let { helper.removeSurface(it) }
            }

            override fun onDeviceClose(device: UsbDevice) {}

            override fun onDetach(device: UsbDevice) {
                if (device.isUvcCamera()) onStatus("USB-Kamera getrennt")
            }

            override fun onCancel(device: UsbDevice) {
                CrashGuard.leave("usb_camera")
                onStatus(device.let { usbCameraBlocker(context, it) }
                    ?: "USB-Zugriff nicht erlaubt.\nKamera kurz ab- und wieder anstecken und „Erlauben“ wählen.")
            }

            override fun onError(device: UsbDevice?, e: CameraException?) {
                // nächstes Format probieren, bevor wir aufgeben
                if (!opened.get() && attempt < candidates.size) {
                    attempt++
                    runCatching { helper.closeCamera() }
                    tryOpen()
                    return
                }
                CrashGuard.leave("usb_camera")
                onStatus("USB-Kamera-Fehler (${e?.code ?: "?"}: ${e?.message ?: ""}).\nFormate: ${formats.ifBlank { "keine gemeldet" }}\nKamera ab- und wieder anstecken.")
            }
        })

        onDispose {
            textureView.surfaceTextureListener = null
            CrashGuard.leave("usb_camera")
            runCatching { helper.release() }
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(factory = { textureView }, modifier = Modifier.aspectRatio(aspect))
    }
}
