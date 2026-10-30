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

fun usbCameras(context: Context): List<UsbDevice> = try {
    context.getSystemService(UsbManager::class.java)?.deviceList?.values.orEmpty().filter { it.isUvcCamera() }
} catch (t: Throwable) {
    emptyList()
}

fun UsbDevice.label(): String {
    val name = runCatching { productName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "USB-Kamera"
    return "$name (%04X:%04X)".format(vendorId, productId)
}

/** Größte Auflösung bis 1280×720 – mehr bringt der Erkennung nichts und kostet USB-Bandbreite. */
private fun pickSize(sizes: List<Size>?): Size? {
    if (sizes.isNullOrEmpty()) return null
    val fitting = sizes.filter { it.width <= 1280 && it.height <= 720 }
    val pool = fitting.ifEmpty { sizes }
    return pool.maxByOrNull { it.width * it.height - abs(it.width * 9 - it.height * 16) }
}

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
                    onStatus("USB-Kamera gefunden – Zugriff erlauben")
                    helper.selectDevice(device)
                }
            }

            override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                CrashGuard.enter("usb_camera")
                val size = pickSize(helper.supportedSizeList)
                if (size != null) helper.openCamera(size) else helper.openCamera()
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
                onStatus("USB-Zugriff nicht erlaubt.\nKamera kurz ab- und wieder anstecken und „Erlauben“ wählen.")
            }

            override fun onError(device: UsbDevice?, e: CameraException?) {
                CrashGuard.leave("usb_camera")
                onStatus("USB-Kamera-Fehler (${e?.code ?: "?"}).\nKamera ab- und wieder anstecken.")
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
