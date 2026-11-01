package de.jarvis.w204

import androidx.compose.foundation.background
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
import de.jarvis.w204.ui.softEdges
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
                driveMode = "COMFORT",
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
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(horizontal = de.jarvis.w204.ui.LocalCenterInset.current)) {
                    LaneView(scene, car.speed, J.accent, Modifier.fillMaxSize(), transparent = true, animate = false)
                    SpeedLimitSign(100, false, Modifier.align(Alignment.TopStart).padding(14.dp).size(62.dp))
                }
            }
        }
    }

    /** Cockpit mit Karte: reicht bis hinter die Instrumente, weiche Ränder */
    @Test
    fun modernMap() {
        val (ways, houses) = city()
        paparazzi.snapshot {
            JarvisTheme {
                ModernCluster(
                    v = car,
                    status = ObdStatus(ConnState.CONNECTED, "Verbunden", rate = 14.0),
                    message = null,
                    clock = "17:58",
                    listening = false,
                    onMic = {},
                    onSettings = {},
                ) {
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        val f = de.jarvis.w204.ui.LocalCenterInset.current / maxWidth
                        de.jarvis.w204.ui.Map3DCanvas(
                            ways, houses, de.jarvis.w204.ui.MapPose(51.48 - 300 / 110540.0, 7.21, 0f), "Nord 0",
                            Modifier.fillMaxSize().then(run { Modifier.softEdges((f - 0.24f).coerceAtLeast(0f), (f - 0.02f).coerceAtLeast(0.12f), top = 0.02f, bottom = 0.10f) }),
                            viewFraction = 1f - 2f * f,
                        )
                    }
                }
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

    /** Künstliche Innenstadt: Straßenraster + Gebäudeblöcke um (51.48, 7.21) */
    private fun city(): Pair<List<de.jarvis.w204.map.Way>, List<de.jarvis.w204.map.Building>> {
        val lat0 = 51.48; val lon0 = 7.21
        val dLat = 1 / 110540.0; val dLon = 1 / (111320.0 * Math.cos(Math.toRadians(lat0)))
        val ways = ArrayList<de.jarvis.w204.map.Way>()
        var id = 1L
        for (k in -6..6) {
            val hw = if (k == 0) "primary" else if (k % 3 == 0) "secondary" else "residential"
            // Nord-Süd-Straßen
            ways += de.jarvis.w204.map.Way(id++, mapOf("highway" to hw, "name" to "Nord $k"), doubleArrayOf(lat0 - 800 * dLat, lat0 + 800 * dLat), doubleArrayOf(lon0 + k * 90 * dLon, lon0 + k * 90 * dLon))
            // Ost-West-Straßen
            ways += de.jarvis.w204.map.Way(id++, mapOf("highway" to if (k % 4 == 0) "tertiary" else "residential", "name" to "Ost $k"), doubleArrayOf(lat0 + k * 110 * dLat, lat0 + k * 110 * dLat), doubleArrayOf(lon0 - 700 * dLon, lon0 + 700 * dLon))
        }
        val houses = ArrayList<de.jarvis.w204.map.Building>()
        for (i in -6 until 6) for (j in -6 until 6) for (q in 0 until 4) {
            val x0 = i * 90 + 12.0 + (q % 2) * 38; val y0 = j * 110 + 12.0 + (q / 2) * 48
            val w = 16.0 + ((i * 5 + j * 3 + q) and 7) * 2; val h = 14.0 + ((i + j * 7 + q * 3) and 7) * 3
            val xs = doubleArrayOf(x0, x0 + w, x0 + w, x0, x0)
            val ys = doubleArrayOf(y0, y0, y0 + h, y0 + h, y0)
            houses += de.jarvis.w204.map.Building(id++, DoubleArray(5) { lat0 + ys[it] * dLat }, DoubleArray(5) { lon0 + xs[it] * dLon }, (7 + ((i * 7 + j * 13 + q) and 7) * 3).toFloat())
        }
        return ways to houses
    }

    @Test fun launcher() {
        val apps = listOf("Bluetooth", "Car Settings", "Music", "ZLINK", "Amap Auto", "YouTube", "DSP", "Video", "Original car info")
            .map { de.jarvis.w204.launcher.AppEntry("p." + it.lowercase().replace(" ", ""), "c", it) }
        val tiles = de.jarvis.w204.launcher.Tile.entries.filter { it.assignable }.associateWith { de.jarvis.w204.launcher.guessTile(it, apps) }
        paparazzi.snapshot {
            JarvisTheme {
                de.jarvis.w204.launcher.LauncherHome(
                    de.jarvis.w204.launcher.LauncherState(car, 100, false, "OBD verbunden", true, "17:58", tiles, apps),
                    de.jarvis.w204.launcher.LauncherActions(), drawerOpen = false, onDrawer = {}, animate = false,
                )
            }
        }
    }

    @Test fun launcherPage2() {
        paparazzi.snapshot {
            JarvisTheme {
                de.jarvis.w204.launcher.LauncherHome(
                    de.jarvis.w204.launcher.LauncherState(car, 100, false, "OBD verbunden", true, "17:58", emptyMap(), emptyList(), page = 1),
                    de.jarvis.w204.launcher.LauncherActions(), drawerOpen = false, onDrawer = {}, animate = false,
                )
            }
        }
    }

    @Test fun launcherApps() {
        val apps = listOf("Bluetooth", "Car Settings", "Music", "Bluetooth Music", "DSP", "Image", "File Explorer", "Google", "YouTube", "ZLINK", "CarAPP", "Amap Auto")
            .map { de.jarvis.w204.launcher.AppEntry("p." + it.lowercase().replace(" ", ""), "c", it) }
        paparazzi.snapshot {
            JarvisTheme {
                de.jarvis.w204.launcher.LauncherHome(
                    de.jarvis.w204.launcher.LauncherState(car, 100, false, "OBD verbunden", true, "17:58", emptyMap(), apps,
                        homes = listOf(de.jarvis.w204.launcher.AppEntry("com.android.launcher3", "L", "Launcher"))),
                    de.jarvis.w204.launcher.LauncherActions(), drawerOpen = true, onDrawer = {}, animate = false,
                )
            }
        }
    }

    @Test fun map3d() {
        val (ways, houses) = city()
        paparazzi.snapshot {
            JarvisTheme {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().androidx_bg()) {
                    de.jarvis.w204.ui.Map3DCanvas(ways, houses, de.jarvis.w204.ui.MapPose(51.48 - 300 / 110540.0, 7.21, 0f), "Nord 0", Modifier.fillMaxSize())
                }
            }
        }
    }

    @Test fun values() {
        val hist = mapOf(
            "rpm" to List(60) { 1500f + 600f * Math.sin(it / 6.0).toFloat() },
            "boost" to List(60) { 0.6f + 0.5f * Math.sin(it / 5.0).toFloat() },
            "coolant" to List(60) { 70f + it * 0.3f },
            "exhaust" to List(60) { 250f + it * 5f },
        )
        paparazzi.snapshot {
            JarvisTheme {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().androidx_bg()) {
                    de.jarvis.w204.ui.ValuesGrid(car.copy(exhaustTemp = 540, oilTemp = 96, maf = 21.4, lambda = 1.8, fuelLevel = 30), hist, Modifier.fillMaxSize())
                }
            }
        }
    }

    @Test fun alertCard() {
        paparazzi.snapshot {
            CompositionLocalProvider(LocalGaugePalette provides Palettes.red) {
                JarvisTheme {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                        ModernCluster(
                            v = car.copy(rpm = 3900, load = 85), status = ObdStatus(ConnState.CONNECTED, "Verbunden", rate = 14.0),
                            message = null, clock = "18:05", listening = false, onMic = {}, onSettings = {}, driveMode = "SPORT",
                        ) {
                            LaneView(scene, car.speed, J.accent, Modifier.fillMaxSize(), transparent = true, animate = false)
                        }
                        de.jarvis.w204.ui.AlertCard(
                            de.jarvis.w204.ui.Alert(de.jarvis.w204.ui.Alert.Level.WARN, "Reifendruck niedrig", "Vorne links 1,9 bar – bitte bei Gelegenheit prüfen.", 0L),
                            onDismiss = {},
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
                        )
                    }
                }
            }
        }
    }

    private fun Modifier.androidx_bg() = this.then(Modifier.background(J.bg))
}
