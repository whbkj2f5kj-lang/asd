package de.jarvis.w204.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jarvis.w204.MainViewModel
import de.jarvis.w204.car.ConnState
import de.jarvis.w204.car.ObdStatus
import de.jarvis.w204.car.VehicleState
import de.jarvis.w204.car.W204Profile
import de.jarvis.w204.car.fmt1
import de.jarvis.w204.vision.CameraFeed
import de.jarvis.w204.vision.UsbCameraFeed
import de.jarvis.w204.vision.Scene
import de.jarvis.w204.voice.VoiceInput
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

fun hasPermission(c: Context, p: String) =
    ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
}.toTypedArray()

@Composable
fun DashboardApp(vm: MainViewModel) {
    val context = LocalContext.current
    val vehicle by vm.obd.vehicle.collectAsStateWithLifecycle()
    val status by vm.obd.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val limit by vm.speedLimit.collectAsStateWithLifecycle()
    val scene by vm.scene.collectAsStateWithLifecycle()
    val message by vm.jarvis.message.collectAsStateWithLifecycle()

    var showDiagnose by remember { mutableStateOf(false) }
    var showTires by remember { mutableStateOf(false) }
    val tires by vm.tpms.tires.collectAsStateWithLifecycle()
    val tpmsStatus by vm.tpms.status.collectAsStateWithLifecycle()
    val diag by vm.diag.collectAsStateWithLifecycle()
    val faultCodes = diag.result?.codes?.distinctBy { it.code }.orEmpty()
    val faults = faultCodes.size
    val faultSeverity = faultCodes.maxOfOrNull { it.info.severity } ?: 0
    var showSettings by remember { mutableStateOf(settings.deviceAddress == null && !settings.demo) }
    var cameraFull by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var camGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        camGranted = hasPermission(context, Manifest.permission.CAMERA)
        vm.onPermissions()
    }
    LaunchedEffect(Unit) { launcher.launch(requiredPermissions()) }

    val voice = remember {
        VoiceInput(context.applicationContext, onText = { vm.onVoiceQuery(it) }, onFail = { vm.jarvis.say(it) })
    }
    DisposableEffect(Unit) { onDispose { voice.destroy() } }
    val listening by voice.listening.collectAsStateWithLifecycle()

    val currentLimit = limit
    val speed = vehicle.speed
    val limitNote by vm.limitNote.collectAsStateWithLifecycle()
    val warnLimit by vm.warnLimit.collectAsStateWithLifecycle()
    val over = warnLimit != null && speed != null && speed > warnLimit!! + settings.tolerance

    val clock = rememberClock()
    val driveMode by vm.driveMode.collectAsStateWithLifecycle()
    val startupAt by vm.startupAt.collectAsStateWithLifecycle()
    val basePalette = Palettes.all[settings.palette.coerceIn(0, Palettes.all.lastIndex)]
    val palette = animatePalette(
        if (!settings.driveModeLook) basePalette else when (driveMode) {
            MainViewModel.DriveMode.SPORT -> Palettes.red
            MainViewModel.DriveMode.ECO -> Palettes.eco
            else -> basePalette
        }
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalGaugePalette provides palette
    ) {
    Box(Modifier.fillMaxSize().background(J.bg)) {
        if (settings.layout == 0) {
            ModernCluster(
                v = vehicle,
                status = status,
                message = message,
                clock = clock,
                listening = listening,
                onMic = { voice.start() },
                onSettings = { showSettings = true },
                faults = faults,
                faultSeverity = faultSeverity,
                onDiagnose = { showDiagnose = true },
                tireText = if (settings.tpms && (tires.isNotEmpty() || tpmsStatus.link == de.jarvis.w204.tpms.TpmsLink.CONNECTED))
                    tireSummary(vm, tires, settings).first else null,
                tireLevel = tireSummary(vm, tires, settings).second,
                onTires = { showTires = true },
                extra = { DashcamButton(vm) },
                sweepKey = startupAt,
                driveMode = if (settings.driveModeLook && status.live) driveMode.label else null,
            ) {
                CenterPager(vm, Modifier.fillMaxSize()) {
                    ScenePanel(
                        vm = vm,
                        scene = scene,
                        camGranted = camGranted,
                        cameraBound = !cameraFull,
                        showCamera = showCamera,
                        limit = currentLimit,
                        over = over,
                        speed = speed,
                        onToggle = { showCamera = it },
                        onExpand = { cameraFull = true },
                        onRequestPermission = { launcher.launch(requiredPermissions()) },
                        modifier = Modifier.fillMaxSize(),
                        framed = false,
                    )
                }
            }
        } else Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            TopBar(status, message, listening, onMic = { voice.start() }, onSettings = { showSettings = true },
                faults = faults, faultSeverity = faultSeverity, onDiagnose = { showDiagnose = true }, extra = { DashcamButton(vm) })
            Spacer(Modifier.height(10.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DrivePanel(vehicle, Modifier.weight(1.05f).fillMaxHeight())
                EnginePanel(vehicle, Modifier.weight(0.85f).fillMaxHeight())
                CenterPager(vm, Modifier.weight(1.3f).fillMaxHeight()) {
                    ScenePanel(
                        vm = vm,
                        scene = scene,
                        camGranted = camGranted,
                        cameraBound = !cameraFull,
                        showCamera = showCamera,
                        limit = currentLimit,
                        over = over,
                        speed = speed,
                        onToggle = { showCamera = it },
                        onExpand = { cameraFull = true },
                        onRequestPermission = { launcher.launch(requiredPermissions()) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (cameraFull) FullscreenCamera(vm, camGranted, currentLimit, over, onClose = { cameraFull = false })
        // Warnkarte im OEM-Stil
        val alert by vm.alert.collectAsStateWithLifecycle()
        AlertCard(alert, onDismiss = { vm.dismissAlert() }, modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
        if (showSettings) SettingsScreen(vm, onClose = { showSettings = false })
        if (showDiagnose) DiagnoseScreen(vm, onClose = { showDiagnose = false })
        if (showTires) TireScreen(vm, onClose = { showTires = false })
        val crash by vm.crashReport.collectAsStateWithLifecycle()
        crash?.let { (report, off) -> CrashScreen(report.text, off, onClose = { vm.dismissCrash() }) }
    }
    }
}

@Composable
private fun CrashScreen(text: String, disabled: List<String>, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(J.bg).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Die App ist beim letzten Start abgestürzt", color = J.warn, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (disabled.isEmpty()) "Bitte diesen Bildschirm abfotografieren und an Claude schicken."
                    else "Vorsichtshalber abgeschaltet: ${disabled.joinToString()}. Bitte abfotografieren und an Claude schicken.",
                    color = J.dim,
                    fontSize = 14.sp,
                )
            }
            TextButton(onClick = onClose) { Text("WEITER", color = J.accent, fontSize = 16.sp) }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text.lines().take(40).joinToString("\n"),
            color = J.text,
            fontSize = 12.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(J.panel2).padding(12.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        )
    }
}

// ---------------- Kopfzeile ----------------

@Composable
private fun TopBar(
    status: ObdStatus,
    message: String?,
    listening: Boolean,
    onMic: () -> Unit,
    onSettings: () -> Unit,
    faults: Int,
    faultSeverity: Int,
    onDiagnose: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    var showMessage by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        if (message != null) {
            showMessage = true
            delay(9000)
            showMessage = false
        }
    }
    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Dashboard", color = J.text, fontSize = 19.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(10.dp))
        Text("C 200 CDI", color = J.dim, fontSize = 14.sp)
        Spacer(Modifier.width(18.dp))
        Box(Modifier.weight(1f)) {
            androidx.compose.animation.AnimatedVisibility(visible = showMessage && message != null, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(J.panel2).padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(J.accent))
                    Spacer(Modifier.width(8.dp))
                    Text(message ?: "", color = J.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        StatusChip(status, onClick = onSettings)
        Spacer(Modifier.width(16.dp))
        Clock()
        Spacer(Modifier.width(12.dp))
        extra()
        Spacer(Modifier.width(10.dp))
        DiagButton(faults, faultSeverity, onDiagnose)
        Spacer(Modifier.width(10.dp))
        MicButton(listening, onMic)
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Einstellungen", tint = J.dim)
        }
    }
}

@Composable
internal fun StatusChip(s: ObdStatus, onClick: () -> Unit) {
    val (color, label) = when (s.state) {
        ConnState.CONNECTED -> J.ok to "OBD"
        ConnState.DEMO -> J.accent to "Demo"
        ConnState.CONNECTING, ConnState.INITIALIZING -> J.warn to "Verbinde"
        ConnState.IGNITION_OFF -> J.dim to "Zündung aus"
        ConnState.NO_DEVICE -> J.danger to "Adapter wählen"
        ConnState.BT_OFF -> J.danger to "Bluetooth aus"
        ConnState.ERROR -> J.danger to "Getrennt"
        ConnState.IDLE -> J.dim to "Bereit"
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(label, color = J.dim, fontSize = 13.sp)
    }
}

@Composable
private fun Clock() {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.GERMANY) }
    var now by remember { mutableStateOf(fmt.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            now = fmt.format(Date())
            delay(5000)
        }
    }
    Text(now, color = J.text, fontSize = 19.sp, fontWeight = FontWeight.Light)
}

@Composable
internal fun MicButton(listening: Boolean, onClick: () -> Unit) {
    val bg = if (listening) J.accent else J.panel2
    val fg = if (listening) Color.Black else J.text
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            drawRoundRect(fg, topLeft = Offset(w * 0.33f, 0f), size = Size(w * 0.34f, h * 0.6f), cornerRadius = CornerRadius(w * 0.17f))
            drawArc(fg, 0f, 180f, false, topLeft = Offset(w * 0.15f, h * 0.2f), size = Size(w * 0.7f, h * 0.55f), style = Stroke(w * 0.09f))
            drawLine(fg, Offset(w / 2f, h * 0.75f), Offset(w / 2f, h * 0.95f), strokeWidth = w * 0.09f)
        }
    }
}

// ---------------- Links: Geschwindigkeit + Drehzahl ----------------

@Composable
private fun DrivePanel(v: VehicleState, modifier: Modifier) {
    Column(modifier.panel().padding(12.dp)) {
        ThinGauge(
            value = v.rpm?.toFloat(),
            lo = 0f,
            hi = W204Profile.RPM_GAUGE_MAX,
            redFrom = W204Profile.RPM_RED_FROM,
            major = 1000f,
            minor = 500f,
            label = { (it / 1000f).toInt().toString() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val m = min(maxWidth.value, maxHeight.value)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        v.speed?.toString() ?: "–",
                        color = J.text,
                        fontSize = (m * 0.30f).sp,
                        lineHeight = (m * 0.31f).sp,
                        fontWeight = FontWeight.ExtraLight,
                    )
                    Text("km/h", color = J.dim, fontSize = (m * 0.055f).sp)
                    Spacer(Modifier.height((m * 0.03f).dp))
                    Text(
                        v.rpm?.let { String.format(Locale.GERMANY, "%,d", it) + " U/min" } ?: "– U/min",
                        color = J.accent,
                        fontSize = (m * 0.055f).sp,
                    )
                }
            }
        }
        LoadBar(v.load)
    }
}

@Composable
private fun LoadBar(load: Int?) {
    val frac by animateFloatAsState(((load ?: 0) / 100f).coerceIn(0f, 1f), tween(250), label = "load")
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row {
            Text("Motorlast", color = J.dim, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(load?.let { "$it %" } ?: "–", color = J.text, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(J.track)) {
            Box(
                Modifier.fillMaxWidth(frac).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(J.accent2, J.accent)))
            )
        }
    }
}

// ---------------- Mitte: Ladedruck + Messwerte ----------------

@Composable
private fun EnginePanel(v: VehicleState, modifier: Modifier) {
    val running = v.engineRunning
    Column(modifier.panel().padding(vertical = 10.dp)) {
        ThinGauge(
            value = v.boostBar?.toFloat(),
            lo = 0f,
            hi = W204Profile.BOOST_GAUGE_MAX,
            redFrom = null,
            major = 0.5f,
            minor = 0.25f,
            label = { if (it % 1f == 0f) it.toInt().toString() else String.format(Locale.GERMANY, "%.1f", it) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val m = min(maxWidth.value, maxHeight.value)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(v.boostBar?.let { fmt1(it) } ?: "–", color = J.text, fontSize = (m * 0.22f).sp, fontWeight = FontWeight.Light)
                    Text("bar Ladedruck", color = J.dim, fontSize = (m * 0.065f).sp)
                }
            }
        }
        Column(Modifier.weight(1.1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceEvenly) {
            ValueRow("Kühlmittel", v.coolant?.toString(), "°C", coolantColor(v.coolant))
            Divider()
            ValueRow("Ladeluft", v.intakeTemp?.toString(), "°C", v.intakeTemp?.let { if (it >= W204Profile.IAT_HIGH) J.warn else J.ok })
            Divider()
            ValueRow("Bordnetz", v.voltage?.let { fmt1(it) }, "V", voltColor(v.voltage, running))
            Divider()
            ValueRow("Außen", v.ambient?.toString(), "°C", v.ambient?.let { if (it <= 3) J.cold else J.ok })
            Divider()
            if (v.fuelRate != null) {
                val per100 = v.consumption
                ValueRow(
                    "Verbrauch",
                    fmt1(per100 ?: v.fuelRate),
                    if (per100 != null) "l/100" else "l/h",
                    J.accent,
                )
            } else {
                ValueRow("Raildruck", v.railBar?.toString(), "bar", v.railBar?.let { J.ok })
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(J.hairline))
}

internal fun coolantColor(c: Int?): Color? = when {
    c == null -> null
    c < W204Profile.COOLANT_COLD -> J.cold
    c >= W204Profile.COOLANT_HOT -> J.danger
    c >= W204Profile.COOLANT_HIGH -> J.warn
    else -> J.ok
}

internal fun voltColor(v: Double?, running: Boolean): Color? = when {
    v == null -> null
    running && (v < W204Profile.CHARGE_LOW || v > W204Profile.CHARGE_HIGH) -> J.warn
    !running && v < W204Profile.BATTERY_LOW -> J.warn
    else -> J.ok
}

// ---------------- Rechts: Fahrspur / Kamera ----------------

@Composable
private fun ScenePanel(
    vm: MainViewModel,
    scene: Scene,
    camGranted: Boolean,
    cameraBound: Boolean,
    showCamera: Boolean,
    limit: Int?,
    over: Boolean,
    speed: Int?,
    onToggle: (Boolean) -> Unit,
    onExpand: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier,
    framed: Boolean = true,
) {
    val detectorError by vm.vision.detectorError.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lead = scene.leadDistance
    // Abstand im Verhältnis zu "halber Tacho"
    val gapColor = when {
        lead == null || speed == null || speed < 30 -> J.accent
        lead < speed / 2f * 0.6f -> J.danger
        lead < speed / 2f -> J.warn
        else -> J.accent
    }

    Box(if (framed) modifier.panel() else modifier) {
        // Kamera läuft immer (für die Erkennung), sichtbar aber nur in der Kamera-Ansicht.
        // Ohne Rahmen reicht das Bild bis hinter die Instrumente und läuft an allen Rändern weich aus.
        if (camGranted && cameraBound && settings.cameraEnabled) {
            CameraWithOverlay(vm, Modifier.fillMaxSize().alpha(if (showCamera) 1f else 0f), showHorizon = false,
                onClick = { if (showCamera) onExpand() }, soft = !framed)
        }
        Box(Modifier.fillMaxSize().padding(horizontal = if (framed) 0.dp else LocalCenterInset.current)) {
        if (!showCamera) {
            val departure by vm.laneDeparture.collectAsStateWithLifecycle()
            LaneView(scene, speed, gapColor, Modifier.fillMaxSize(), transparent = !framed, departure = departure)
        }

        // unter den Reitern Assistent/Karte/Werte, damit nichts überlappt
        Column(Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeedLimitSign(limit, over, Modifier.size(62.dp).clickable { vm.clearLimit() })
                val prov by vm.provisional.collectAsStateWithLifecycle()
                if (prov != null) {
                    Box(Modifier.padding(start = 8.dp).size(42.dp).alpha(0.55f), contentAlignment = Alignment.TopEnd) {
                        SpeedLimitSign(prov, false, Modifier.fillMaxSize())
                        Text("?", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(CircleShape).background(Color(0xFF444444)).padding(horizontal = 5.dp))
                    }
                }
            }
            val noPass by vm.noPassing.collectAsStateWithLifecycle()
            if (noPass) NoPassingSign(Modifier.padding(top = 6.dp).size(40.dp).clickable { vm.noPassing.value = false })
            val note by vm.limitNote.collectAsStateWithLifecycle()
            note?.let {
                Text(
                    it,
                    color = Color.Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(4.dp)).background(Color.White).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }

        if (lead != null) {
            Column(Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalAlignment = Alignment.End) {
                Text("Abstand", color = J.dim, fontSize = 12.sp)
                Text("${lead.roundToInt()} m", color = gapColor, fontSize = 24.sp, fontWeight = FontWeight.Light)
            }
        }

        val hint = when {
            !settings.cameraEnabled -> "Kamera aus (Einstellungen)"
            !camGranted -> "Kamera-Berechtigung fehlt – antippen"
            !settings.objectRecognition && !showCamera -> "Fahrzeugerkennung aus"
            detectorError != null -> detectorError
            !scene.laneDetected && !showCamera -> "Spur nicht erkannt"
            scene.laneYellow && !showCamera -> "Baustelle – gelbe Markierung"
            else -> null
        }
        hint?.let {
            Text(
                it,
                color = J.dim,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 124.dp)
                    .clickable(enabled = !camGranted, onClick = onRequestPermission),
            )
        }

        Segmented(
            options = listOf("Spur", "Kamera"),
            selected = if (showCamera) 1 else 0,
            onSelect = { onToggle(it == 1) },
            // oben mittig sitzen die Reiter Assistent/Karte/Werte
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 18.dp),
        )
        }
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Text(
                label,
                color = if (sel) Color.Black else J.text,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (sel) J.text else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun CameraWithOverlay(vm: MainViewModel, modifier: Modifier, showHorizon: Boolean, onClick: () -> Unit, soft: Boolean = false) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val frame by vm.visionFrame.collectAsStateWithLifecycle()
    val error by vm.cameraError.collectAsStateWithLifecycle()
    val usbCams by vm.usbCameraNames.collectAsStateWithLifecycle()
    val useUsb = when (settings.cameraSource) {
        1 -> false
        2 -> true
        else -> usbCams.isNotEmpty()
    }

    val frameW = frame?.width ?: 16
    val frameH = frame?.height ?: 9
    Box(
        if (!soft) modifier.background(Color.Black)
        // weiche Ränder am eigentlichen Kamerabild (es wird eingepasst, nicht beschnitten)
        else modifier.softEdges(0f, 0.16f, top = 0.12f, bottom = 0.14f) { sz ->
            val sc = min(sz.width / frameW, sz.height / frameH)
            val iw = frameW * sc; val ih = frameH * sc
            androidx.compose.ui.geometry.Rect((sz.width - iw) / 2f, (sz.height - ih) / 2f, (sz.width + iw) / 2f, (sz.height + ih) / 2f)
        }
    ) {
        if (useUsb) {
            UsbCameraFeed(
                vision = vm.vision,
                onStatus = { vm.cameraError.value = it },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CameraFeed(
                cameraIndex = settings.cameraIndex,
                vision = vm.vision,
                onCameras = { vm.cameraCount.value = it },
                onError = { vm.cameraError.value = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Canvas(Modifier.fillMaxSize().clickable(onClick = onClick)) {
            val f = frame
            val fw = f?.width ?: 16
            val fh = f?.height ?: 9
            val scale = min(size.width / fw, size.height / fh)
            val ox = (size.width - fw * scale) / 2f
            val oy = (size.height - fh * scale) / 2f
            fun px(nx: Float) = ox + nx * fw * scale
            fun py(ny: Float) = oy + ny * fh * scale

            if (showHorizon) {
                val y = py(settings.horizon)
                drawLine(
                    J.warn, Offset(ox, y), Offset(ox + fw * scale, y), strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)),
                )
            }
            if (f == null) return@Canvas
            val y0 = settings.horizon + 0.06f
            listOfNotNull(f.lanes.left, f.lanes.right).forEach { l ->
                drawLine(J.accent, Offset(px(l.x(y0)), py(y0)), Offset(px(l.x(0.95f)), py(0.95f)), strokeWidth = 5f)
            }
            for (o in f.objects) {
                drawRoundRect(
                    J.accent,
                    topLeft = Offset(px(o.l), py(o.t)),
                    size = Size((o.r - o.l) * fw * scale, (o.b - o.t) * fh * scale),
                    cornerRadius = CornerRadius(6f),
                    style = Stroke(3f),
                )
            }
            for (b in f.signs) {
                drawRect(
                    if (b.value != null) J.ok else J.warn,
                    topLeft = Offset(px(b.l), py(b.t)),
                    size = Size((b.r - b.l) * fw * scale, (b.b - b.t) * fh * scale),
                    style = Stroke(3f),
                )
            }
        }
        error?.let {
            Text(it, color = J.warn, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(16.dp))
        }
    }
}

@Composable
private fun FullscreenCamera(vm: MainViewModel, camGranted: Boolean, limit: Int?, over: Boolean, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val settings by vm.settings.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (camGranted) CameraWithOverlay(vm, Modifier.fillMaxSize(), showHorizon = true, onClick = {})
        SpeedLimitSign(limit, over, Modifier.align(Alignment.TopEnd).padding(16.dp).size(110.dp))
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Text("Schließen", color = J.text, fontSize = 16.sp)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f).padding(16.dp)
                .clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(12.dp),
        ) {
            Text("Gelbe Linie auf den Horizont legen", color = J.text, fontSize = 14.sp)
            Slider(
                value = settings.horizon,
                onValueChange = { h -> vm.update { it.copy(horizon = (h * 100).roundToInt() / 100f) } },
                valueRange = 0.3f..0.7f,
            )
        }
    }
}
