package de.jarvis.w204.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max

/** Ein Kamerabild -> Schilder, Fahrzeuge und Fahrspur in einem Durchgang (~8 Bilder/s). */
class VisionPipeline(
    context: Context,
    private val onFrame: (VisionFrame) -> Unit,
    onLimit: (Int) -> Unit,
) : ImageAnalysis.Analyzer {

    private val appContext = context.applicationContext
    @Volatile var signsEnabled = true
    @Volatile var objectsEnabled = true
    @Volatile var calibration = Calibration()

    private val _detectorError = MutableStateFlow<String?>(null)
    val detectorError: StateFlow<String?> = _detectorError

    private val signs = SignReader(onLimit)
    private val lanes = LaneDetector()
    private var vehicles: VehicleDetector? = null
    private var vehiclesTried = false
    private var lastRun = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRun < MIN_INTERVAL_MS) return
            if (!signsEnabled && !objectsEnabled) return
            lastRun = now

            var bmp = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            if (rot != 0) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            }

            val sw = SCAN_WIDTH
            val sh = max(1, bmp.height * sw / bmp.width)
            val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)

            val signBoxes = if (signsEnabled) signs.detect(bmp, px, sw, sh, now) else emptyList()

            var objects: List<Detection> = emptyList()
            var laneResult = Lanes()
            if (objectsEnabled) {
                vehicleDetector()?.let { det ->
                    val mid = Bitmap.createScaledBitmap(bmp, 640, max(1, bmp.height * 640 / bmp.width), true)
                    objects = det.detect(mid)
                }
                laneResult = lanes.detect(px, sw, sh, calibration.horizon, now)
            }

            onFrame(VisionFrame(bmp.width, bmp.height, signBoxes, objects, laneResult))
        } catch (t: Throwable) {
            Log.w(TAG, "Bildanalyse fehlgeschlagen", t)
        } finally {
            image.close()
        }
    }

    private fun vehicleDetector(): VehicleDetector? {
        if (!vehiclesTried) {
            vehiclesTried = true
            vehicles = VehicleDetector.create(appContext)
            if (vehicles == null) _detectorError.value = "Fahrzeugerkennung auf diesem Gerät nicht verfügbar"
        }
        return vehicles
    }

    fun close() {
        signs.close()
        vehicles?.close()
    }

    companion object {
        private const val TAG = "VisionPipeline"
        private const val SCAN_WIDTH = 320
        private const val MIN_INTERVAL_MS = 110L
    }
}
