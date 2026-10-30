package de.jarvis.w204.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
    center: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(J.bg, J.bg, Color(0xFF07101A)))
        )
    ) {
        val h = maxHeight
        val big = (h.value * 0.17f).sp
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(status, onClick = onSettings)
                Spacer(Modifier.weight(1f))
                MicButton(listening, onMic)
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Einstellungen", tint = J.dim)
                }
            }

            Row(Modifier.weight(1f).fillMaxWidth()) {
                // ---- links: Geschwindigkeit
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    ChevronGauge(
                        value = v.speed?.toFloat(),
                        min = 0f, apexValue = 120f, max = 220f,
                        ticks = (0..22).map { it * 10f },
                        label = { if (it.toInt() in setOf(0, 30, 60, 90, 120, 150, 180, 220)) it.toInt().toString() else null },
                        unit = "km/h",
                        mirrored = false,
                        modifier = Modifier.fillMaxHeight().fillMaxWidth(0.68f).align(Alignment.CenterEnd),
                    )
                    BigValue(
                        value = v.speed?.toString() ?: "–",
                        unit = "km/h",
                        sub = null,
                        size = big,
                        alignEnd = false,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp, top = h * 0.16f),
                    )
                }

                // ---- Mitte: Verkehrsassistent
                Box(Modifier.weight(1.3f).fillMaxHeight(), content = center)

                // ---- rechts: Drehzahl + Ladedruck
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    ChevronGauge(
                        value = v.rpm?.toFloat(),
                        min = 0f, apexValue = 2500f, max = W204Profile.RPM_GAUGE_MAX,
                        ticks = (0..10).map { it * 500f },
                        label = { if (it.toInt() % 1000 == 0) (it.toInt() / 1000).toString() else null },
                        unit = "×1000 U/min",
                        mirrored = true,
                        redFrom = W204Profile.RPM_RED_FROM,
                        modifier = Modifier.fillMaxHeight().fillMaxWidth(0.68f).align(Alignment.CenterStart),
                    )
                    BigValue(
                        value = v.boostBar?.let { fmt1(it) } ?: "–",
                        unit = "bar",
                        sub = v.load?.let { "$it % Last" },
                        size = big,
                        alignEnd = true,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 22.dp, top = h * 0.16f),
                    )
                }
            }

            StatusBar(v, message, clock)
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
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = J.text, fontSize = size, lineHeight = size, fontWeight = FontWeight.Light, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text(unit, color = J.dim, fontSize = 16.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        if (sub != null) Text(sub, color = J.dim, fontSize = 13.sp)
    }
}

@Composable
private fun StatusBar(v: VehicleState, message: String?, clock: String) {
    var showMessage by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        if (message != null) {
            showMessage = true
            delay(9000)
            showMessage = false
        }
    }
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stat(v.voltage?.let { fmt1(it) }, "V", voltColor(v.voltage, v.engineRunning))
        Spacer(Modifier.width(28.dp))
        Text(clock, color = J.text, fontSize = 20.sp, fontWeight = FontWeight.Light)

        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            AnimatedVisibility(visible = showMessage && message != null, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(J.panel2).padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(J.accent))
                    Spacer(Modifier.width(8.dp))
                    Text(message ?: "", color = J.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
            v.consumption?.let { Stat(fmt1(it), "l/100", J.accent, label = "Verbrauch") }
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
            Text(label, color = J.dim, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
        }
        Text(value ?: "–", color = if (value == null) J.dim else J.text, fontSize = 20.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.width(3.dp))
        Text(unit, color = J.dim, fontSize = 13.sp)
    }
}
