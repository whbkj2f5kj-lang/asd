package de.jarvis.w204.vision

import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
    analyzer: ImageAnalysis.Analyzer,
    onCameras: (Int) -> Unit,
    onError: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(cameraIndex, owner) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
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

                val preview = Preview.Builder().setResolutionSelector(resolution).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor, analyzer)

                p.unbindAll()
                p.bindToLifecycle(owner, selector, preview, analysis)
                onError(null)
            } catch (t: Throwable) {
                onError("Kamera-Fehler: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
