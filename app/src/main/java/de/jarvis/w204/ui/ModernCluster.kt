package de.jarvis.w204.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.jarvis.w204.car.ObdStatus
import de.jarvis.w204.car.VehicleState
import de.jarvis.w204.car.W204Profile
import de.jarvis.w204.car.fmt1
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun rememberClock(): String {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.GERMANY) }
    var now by remember { mutableStateOf(fmt.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            now = fmt.format(Date())
            delay(5000)
        }
    }
    return now
}

/**
 * Modernes Cockpit: links Tempo als Pfeil-Instrument, rechts Drehzahl gespiegelt,
 * große Zahlen außen, Verkehrsassistent in der Mitte, schlanke Statusleiste unten.
 */
@Composable
fun ModernCluster(
    v: VehicleState,
    status: ObdStatus,
    message: String?,
    clock: String,
    listening: Boolean,
    onMic: () -> Unit,
    onSettings: () -> Unit,
    faults: Int = 0,
    faultSeverity: Int = 0,
    onDiagnose: () -> Unit = {},
    tireText: String? = null,
    tireLevel: Int = -1,
    onTires: () -> Unit = {},
    extra: @Composable () -> Unit = {},
    /** ändert sich bei Zündung an -> Instrumente schwingen einmal aus */
    sweepKey: Long = 0L,
    /** COMFORT / SPORT / ECO (unten mittig, wie im OEM-Cockpit) */
    driveMode: String? = null,
    center: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalGaugePalette.current
    val noise = rememberNoiseBrush()

    // ---- Startanimation: Licht blendet auf, Zeiger schwingen bis Anschlag und zurück
    val sweep = remember { androidx.compose.animation.core.Animatable(0f) }
    val intro = remember { androidx.compose.animation.core.Animatable(if (sweepKey > 0L) 0f else 1f) }
    var sweeping by remember { mutableStateOf(false) }
    LaunchedEffect(sweepKey) {
        if (sweepKey <= 0L) return@LaunchedEffect
        sweeping = true
        intro.snapTo(0.15f)
        launch { intro.animateTo(1f, androidx.compose.animation.core.tween(900)) }
        sweep.snapTo(0f)
        sweep.animateTo(1f, androidx.compose.animation.core.tween(850, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        delay(150)
        sweep.animateTo(0f, androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        delay(900)
        sweeping = false
    }
    val speedShown = if (sweeping) sweep.value * 220f else v.speed?.toFloat()
    val rpmShown = if (sweeping) sweep.value * W204Profile.RPM_GAUGE_MAX else v.rpm?.toFloat()
    // Zahlen zählen weich statt zu springen
    val speedAnim by androidx.compose.animation.core.animateFloatAsState(v.speed?.toFloat() ?: 0f, androidx.compose.animation.core.tween(350), label = "spd")
    val boostAnim by androidx.compose.animation.core.animateFloatAsState(v.boostBar?.toFloat() ?: 0f, androidx.compose.animation.core.tween(350), label = "bst")

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(J.bg, J.bg, Color(0xFF07101A))))
            .drawBehind {
                // Tiefe: weiches Licht hinter den Instrumenten in der Farbwelt + feine Struktur
                val r = size.height * 0.75f
                drawCircle(Brush.radialGradient(listOf(palette.glow.copy(alpha = 0.07f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.55f), radius = r), r, androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.55f))
                drawCircle(Brush.radialGradient(listOf(palette.glow.copy(alpha = 0.07f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.55f), radius = r), r, androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.55f))
                drawRect(Brush.radialGradient(listOf(Color(0x1A2A6CFF), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 1.1f), radius = size.width * 0.5f))
                drawRect(noise)
            }
            .graphicsLayer { alpha = intro.value }
    ) {
        val h = maxHeight
        val big = (h.value * 0.15f).sp
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(status, onClick = onSettings)
                Spacer(Modifier.weight(1f))
                extra()
                Spacer(Modifier.width(10.dp))
                DiagButton(faults, faultSeverity, onDiagnose)
                Spacer(Modifier.width(10.dp))
                MicButton(listening, onMic)
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Einstellungen", tint = J.dim)
                }
            }

            Row(Modifier.weight(1f).fillMaxWidth()) {
                // ---- links: Geschwindigkeit
                Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    BigValue(
                        value = if (sweeping) (speedShown ?: 0f).toInt().toString() else if (v.speed != null) speedAnim.roundToInt().toString() else "–",
                        unit = "km/h",
                        sub = null,
                        size = big,
                        alignEnd = false,
                        modifier = Modifier.weight(0.42f).padding(start = 16.dp, top = h * 0.18f),
                    )
                    ChevronGauge(
                        value = speedShown,
                        min = 0f, apexValue = 120f, max = 220f,
                        ticks = (0..22).map { it * 10f },
                        label = { if (it.toInt() in setOf(0, 30, 60, 90, 120, 150, 180, 220)) it.toInt().toString() else null },
                        unit = "km/h",
                        mirrored = false,
                        modifier = Modifier.weight(0.58f).fillMaxHeight(),
                    )
                }

                // ---- Mitte: Verkehrsassistent / Karte / Werte
                Box(Modifier.weight(1.25f).fillMaxHeight().padding(horizontal = 6.dp)) {
                    center()
                    // Begrüßung während der Startanimation
                    androidx.compose.animation.AnimatedVisibility(
                        visible = sweeping, enter = fadeIn(), exit = fadeOut(),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        Column(
                            Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xE6000000)).padding(horizontal = 28.dp, vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
                            Text(
                                when (hour) { in 5..10 -> "Guten Morgen"; in 11..17 -> "Guten Tag"; else -> "Guten Abend" },
                                color = J.text, fontSize = 30.sp, fontWeight = FontWeight.Light,
                            )
                            Text(W204Profile.TITLE, color = palette.glow, fontSize = 13.sp, letterSpacing = 3.sp)
                        }
                    }
                }

                // ---- rechts: Drehzahl + Ladedruck
                Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    ChevronGauge(
                        value = rpmShown,
                        min = 0f, apexValue = 2500f, max = W204Profile.RPM_GAUGE_MAX,
                        ticks = (0..10).map { it * 500f },
                        label = { if (it.toInt() % 1000 == 0) (it.toInt() / 1000).toString() else null },
                        unit = "×1000 U/min",
                        mirrored = true,
                        redFrom = W204Profile.RPM_RED_FROM,
                        modifier = Modifier.weight(0.58f).fillMaxHeight(),
                    )
                    BigValue(
                        value = v.boostBar?.let { fmt1(boostAnim.toDouble()) } ?: "–",
                        unit = "bar",
                        sub = v.load?.let { "$it % Last" },
                        size = big,
                        alignEnd = true,
                        modifier = Modifier.weight(0.42f).padding(end = 18.dp, top = h * 0.18f),
                    )
                }
            }

            StatusBar(v, message, clock, tireText, tireLevel, onTires, driveMode, palette.glow)
        }
    }
}

@Composable
private fun BigValue(
    value: String,
    unit: String,
    sub: String?,
    size: androidx.compose.ui.unit.TextUnit,
    alignEnd: Boolean,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier) {
        // Schrift so groß wie möglich, aber "131" muss immer ganz hineinpassen
        val fit = (maxWidth.value / (3.2f * 0.56f)).sp
        val fs = if (fit.value < size.value) fit else size
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(value, color = J.text, fontSize = fs, lineHeight = fs, fontWeight = FontWeight.Light, maxLines = 1, softWrap = false)
        Text(unit, color = J.dim, fontSize = 16.sp)
        if (sub != null) Text(sub, color = J.dim, fontSize = 13.sp)
    }
    }
}

@Composable
private fun StatusBar(v: VehicleState, message: String?, clock: String, tireText: String?, tireLevel: Int, onTires: () -> Unit, driveMode: String? = null, modeColor: Color = J.accent) {
    var showMessage by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        if (message != null) {
            showMessage = true
            delay(9000)
            showMessage = false
        }
    }
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stat(v.voltage?.let { fmt1(it) }, "V", voltColor(v.voltage, v.engineRunning))
        Spacer(Modifier.width(28.dp))
        Text(clock, color = J.text, fontSize = 26.sp, fontWeight = FontWeight.Light)

        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // Fahrprogramm (wie "COMFORT" im BMW-Cockpit), weicht einer Meldung
            androidx.compose.animation.AnimatedVisibility(visible = driveMode != null && !(showMessage && message != null), enter = fadeIn(), exit = fadeOut()) {
                androidx.compose.animation.Crossfade(driveMode, label = "mode") { m ->
                    Text(m ?: "", color = modeColor, fontSize = 17.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Light)
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = showMessage && message != null, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier.glass(RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(J.accent))
                    Spacer(Modifier.width(8.dp))
                    Text(message ?: "", color = J.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tireText != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(J.panel2)
                        .clickable(onClick = onTires).padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (tireLevel < 0) J.track else tireColor(tireLevel)))
                    Spacer(Modifier.width(7.dp))
                    Text(tireText, color = if (tireLevel > 0) tireColor(tireLevel) else J.text, fontSize = 20.sp)
                }
            }
            if ((v.exhaustTemp ?: 0) >= 480 && v.engineRunning) {
                Text(
                    "DPF regeneriert",
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(J.warn).padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
            v.consumption?.let { Stat((if (v.fuelEstimated) "≈" else "") + fmt1(it), "l/100", J.accent, label = "Verbrauch") }
            v.fuelLevel?.let { Stat(it.toString(), "%", if (it < 12) J.warn else null, label = "Tank") }
            v.exhaustTemp?.let { Stat(it.toString(), "°C", if (it >= 480) J.warn else null, label = "Abgas") }
            v.oilTemp?.let { Stat(it.toString(), "°C", if (it >= 130) J.warn else null, label = "Öl") }
            Stat(v.coolant?.toString(), "°C", coolantColor(v.coolant), label = "Motor")
            Stat(v.ambient?.let { (if (it > 0) "+" else "") + it }, "°C", null, label = "Außen")
        }
    }
}

@Composable
private fun Stat(value: String?, unit: String, dot: Color?, label: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(7.dp))
        }
        if (label != null) {
            Text(label, color = J.dim, fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
        }
        Text(value ?: "–", color = if (value == null) J.dim else J.text, fontSize = 26.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.width(3.dp))
        Text(unit, color = J.dim, fontSize = 16.sp)
    }
}
