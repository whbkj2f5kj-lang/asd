package de.jarvis.w204.launcher

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jarvis.w204.MainViewModel
import de.jarvis.w204.ui.DashboardApp
import de.jarvis.w204.ui.rememberClock
import kotlinx.coroutines.delay

/** false, solange der Startbildschirm über dem Cockpit liegt (Karte zeichnet dann nicht) */
val LocalCockpitVisible = staticCompositionLocalOf { true }

/**
 * Oberste Ebene: Cockpit + Startbildschirm darüber.
 * Das Cockpit bleibt darunter am Leben – Kamera, Schildererkennung, OBD und Warnungen laufen weiter,
 * wenn man auf dem Startbildschirm ist.
 */
@Composable
fun LauncherRoot(vm: MainViewModel) {
    val home by vm.home.collectAsStateWithLifecycle()
    val drawer by vm.drawer.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Zurück im Cockpit -> Startbildschirm (innere Dialoge des Cockpits haben Vorrang)
    BackHandler(enabled = !home) { vm.showHome() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (home) 0f else 1f }) {
            CompositionLocalProvider(LocalCockpitVisible provides !home) {
                DashboardApp(vm)
            }
        }
        AnimatedVisibility(
            home,
            enter = fadeIn(tween(260)) + scaleIn(tween(320), initialScale = 1.04f),
            exit = fadeOut(tween(220)) + scaleOut(tween(260), targetScale = 1.04f),
        ) {
            val v by vm.obd.vehicle.collectAsStateWithLifecycle()
            val status by vm.obd.status.collectAsStateWithLifecycle()
            val limit by vm.speedLimit.collectAsStateWithLifecycle()
            val warn by vm.warnLimit.collectAsStateWithLifecycle()
            val settings by vm.settings.collectAsStateWithLifecycle()
            val tiles by vm.apps.tiles.collectAsStateWithLifecycle()
            val apps by vm.apps.apps.collectAsStateWithLifecycle()
            val clock = rememberClock()
            // andere Startbildschirme (Original-Launcher) selten neu abfragen
            var homesTick by remember { mutableIntStateOf(0) }
            LaunchedEffect(apps) { homesTick++ }
            val homes = remember(homesTick) { vm.apps.otherHomes() }
            val speed = v.speed
            val w = warn
            val state = LauncherState(
                vehicle = v,
                limit = limit,
                over = w != null && speed != null && speed > w + settings.tolerance,
                obdText = if (status.live) "OBD verbunden" else status.message.ifBlank { "OBD getrennt" }.take(28),
                obdLive = status.live,
                clock = clock,
                tiles = tiles,
                apps = apps,
                homes = homes,
            )
            val actions = remember {
                LauncherActions(
                    openCockpit = { vm.showCockpit(it) },
                    openSettings = { vm.requestSettings() },
                    launch = { app ->
                        if (!vm.apps.launch(app)) Toast.makeText(context, "${app.label} lässt sich nicht starten", Toast.LENGTH_SHORT).show()
                    },
                    launchHome = { vm.apps.launchHome(it) },
                    assign = { t, app -> vm.apps.assign(t, app) },
                    resetTile = { vm.apps.resetAssignment(it) },
                    appInfo = { vm.apps.appInfo(it) },
                    mediaKey = { vm.apps.mediaKey(it) },
                    icon = { vm.apps.icon(it) },
                    toast = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
                )
            }
            BackHandler(enabled = !drawer) { /* Startbildschirm: Zurück tut nichts */ }
            LauncherHome(
                state, actions, drawerOpen = drawer, onDrawer = { vm.drawer.value = it },
                // Berührungen nicht ans Cockpit darunter durchreichen
                modifier = Modifier.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                },
            )
        }
    }
    // App-Liste beim Zurückkehren auffrischen (neu installierte Apps)
    LaunchedEffect(home) { if (home) { delay(300); vm.apps.refresh() } }
}
