package de.jarvis.w204

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import de.jarvis.w204.car.ConnState
import de.jarvis.w204.car.ObdStatus
import de.jarvis.w204.car.VehicleState
import de.jarvis.w204.ui.J
import de.jarvis.w204.ui.JarvisTheme
import de.jarvis.w204.ui.LaneView
import de.jarvis.w204.ui.ModernCluster
import de.jarvis.w204.ui.SpeedLimitSign
import de.jarvis.w204.ui.LocalGaugePalette
import de.jarvis.w204.ui.Palettes
import de.jarvis.w204.ui.GaugePalette
import androidx.compose.runtime.CompositionLocalProvider
import de.jarvis.w204.vision.ObjKind
import de.jarvis.w204.vision.Scene
import de.jarvis.w204.vision.SceneObject
import org.junit.Rule
import org.junit.Test

/** Rendert die Oberfläche als Bild (ohne Gerät), damit sie vor dem Einbau geprüft werden kann. */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1280,
            screenHeight = 720,
            xdpi = 240,
            ydpi = 240,
            density = Density.HIGH,
            orientation = ScreenOrientation.LANDSCAPE,
            softButtons = false,
        ),
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
        maxPercentDifference = 100.0,
    )

    private val car = VehicleState(
        rpm = 1850, speed = 98, coolant = 88, intakeTemp = 31, mapKpa = 221, baroKpa = 101,
        load = 42, voltage = 14.1, ambient = 9, railBar = 640, fuelRate = 6.3,
    )

    private val scene = Scene(
        objects = listOf(
            SceneObject(1, ObjKind.CAR, 0.1f, 24f, lead = true),
            SceneObject(2, ObjKind.CAR, 3.5f, 32f, lead = false),
            SceneObject(3, ObjKind.TRUCK, -3.6f, 48f, lead = false),
        ),
        laneDetected = true,
        leadDistance = 24f,
    )

    private fun cluster(p: GaugePalette) = paparazzi.snapshot {
        CompositionLocalProvider(LocalGaugePalette provides p) {
        JarvisTheme {
            ModernCluster(
                v = car,
                status = ObdStatus(ConnState.CONNECTED, "Verbunden", rate = 14.0),
                message = "Tempolimit 100.",
                clock = "17:58",
                listening = false,
                onMic = {},
                onSettings = {},
            ) {
                LaneView(scene, car.speed, J.accent, Modifier.fillMaxSize(), transparent = true, animate = false)
                SpeedLimitSign(100, false, Modifier.align(Alignment.TopStart).padding(14.dp).size(62.dp))
            }
        }
        }
    }

    @Test fun farbeEisblau() = cluster(Palettes.ice)
    @Test fun farbeRot() = cluster(Palettes.red)
    @Test fun farbeAmber() = cluster(Palettes.amber)

    @Test
    fun modern() = paparazzi.snapshot {
        JarvisTheme {
            ModernCluster(
                v = car,
                status = ObdStatus(ConnState.CONNECTED, "Verbunden", rate = 14.0),
                message = "Tempolimit 100.",
                clock = "17:58",
                listening = false,
                onMic = {},
                onSettings = {},
            ) {
                LaneView(scene, car.speed, J.accent, Modifier.fillMaxSize(), transparent = true, animate = false)
                SpeedLimitSign(100, false, Modifier.align(Alignment.TopStart).padding(14.dp).size(62.dp))
            }
        }
    }

    @Test
    fun modernOverRevAndSpeeding() = paparazzi.snapshot {
        JarvisTheme {
            ModernCluster(
                v = car.copy(speed = 131, rpm = 4600, mapKpa = 290, coolant = 52),
                status = ObdStatus(ConnState.CONNECTED, "Verbunden"),
                message = null,
                clock = "17:58",
                listening = true,
                onMic = {},
                onSettings = {},
            ) {
                LaneView(Scene(), 131, J.accent, Modifier.fillMaxSize(), transparent = true, animate = false)
                SpeedLimitSign(120, true, Modifier.align(Alignment.TopStart).padding(14.dp).size(62.dp))
            }
        }
    }

    @Test
    fun modernNoConnection() = paparazzi.snapshot {
        JarvisTheme {
            ModernCluster(
                v = VehicleState(),
                status = ObdStatus(ConnState.ERROR, "Getrennt"),
                message = null,
                clock = "17:58",
                listening = false,
                onMic = {},
                onSettings = {},
            ) {
                LaneView(Scene(), null, J.accent, Modifier.fillMaxSize(), transparent = true, animate = false)
                SpeedLimitSign(null, false, Modifier.align(Alignment.TopStart).padding(14.dp).size(62.dp))
            }
        }
    }
}
