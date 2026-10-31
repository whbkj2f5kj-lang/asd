package de.jarvis.w204.vision

import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import android.view.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import de.jarvis.w204.CrashGuard
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/**
 * Kamerabild + Bildanalyse. Zeigt alle Kameras, die Android kennt (eingebaute und USB/UVC,
 * sofern das Radio sie als externe Kamera freigibt). Auswahl über den Index in den Einstellungen.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraFeed(
    cameraIndex: Int,
    vision: VisionPipeline,
    onCameras: (Int) -> Unit,
    onError: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val frame by vision.preview.collectAsState()

    DisposableEffect(cameraIndex, owner) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var bound: androidx.camera.core.UseCase? = null
        var disposed = false

        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get()
                provider = p
                val infos = p.availableCameraInfos
                onCameras(infos.size)
                if (infos.isEmpty()) {
                    onError("Keine Kamera gefunden.\nUSB-Kamera angesteckt?")
                    return@addListener
                }
                val ids = infos.map { Camera2CameraInfo.from(it).cameraId }
                val wanted = ids[cameraIndex.coerceIn(0, ids.lastIndex)]
                val selector = CameraSelector.Builder()
                    .addCameraFilter { list -> list.filter { Camera2CameraInfo.from(it).cameraId == wanted } }
                    .build()

                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()

                // Kein CameraX-Preview: manche Radios melden eine Bildschirmdrehung von 359°,
                // daran stürzt die Vorschau-Ausrichtung ab. Wir zeigen die Analysebilder selbst an
                // und legen die Drehung fest auf 0°.
                val builder = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setTargetRotation(Surface.ROTATION_0)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                // Möglichst hohe Mindest-Bildrate erzwingen: kürzere Belichtung -> weniger Bewegungsunschärfe bei Fahrt
                runCatching {
                    val info = infos[ids.indexOf(wanted)]
                    val ranges = Camera2CameraInfo.from(info)
                        .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    val best = ranges?.filter { it.upper <= 30 }?.maxWithOrNull(compareBy({ it.lower }, { it.upper }))
                    if (best != null && best.lower >= 15) {
                        androidx.camera.camera2.interop.Camera2Interop.Extender(builder)
                            .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, best)
                    }
                }
                val analysis = builder.build()
                analysis.setAnalyzer(executor, vision)

                CrashGuard.guard("camera") {
                    p.unbindAll()
                    p.bindToLifecycle(owner, selector, analysis)
                    bound = analysis
                }
                onError(null)
            } catch (t: Throwable) {
                onError("Kamera-Fehler: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            bound?.let { b -> runCatching { provider?.unbind(b) } }
            executor.shutdown()
            vision.clearPreview()
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        frame?.let {
            Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}
