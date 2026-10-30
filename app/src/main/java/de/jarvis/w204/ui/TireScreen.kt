package de.jarvis.w204.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jarvis.w204.MainViewModel
import de.jarvis.w204.tpms.TireState
import de.jarvis.w204.tpms.TpmsLink
import de.jarvis.w204.tpms.Wheel
import java.util.Locale

fun tireColor(level: Int): Color = when (level) {
    2 -> J.danger
    1 -> J.warn
    else -> J.ok
}

/** Draufsicht mit vier Reifen – Druck, Temperatur, Zustand. */
@Composable
fun TireScreen(vm: MainViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val tires by vm.tpms.tires.collectAsStateWithLifecycle()
    val status by vm.tpms.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pal = LocalGaugePalette.current

    Surface(Modifier.fillMaxSize(), color = J.bg) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reifendruck", color = J.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        status.message + (status.device?.let { " · $it" } ?: ""),
                        color = if (status.link == TpmsLink.CONNECTED) J.dim else J.warn,
                        fontSize = 13.sp,
                    )
                }
                Text(
                    "Soll vorn ${fmtBar(settings.sollFront)} · hinten ${fmtBar(settings.sollRear)} bar",
                    color = J.dim,
                    fontSize = 13.sp,
                )
                TextButton(onClick = onClose) { Text("FERTIG", color = J.text, fontSize = 15.sp) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceAround) {
                    TireCard(Wheel.FL, tires[Wheel.FL], vm, settings, alignEnd = true)
                    TireCard(Wheel.RL, tires[Wheel.RL], vm, settings, alignEnd = true)
                }
                // Auto von oben
                BoxWithConstraints(Modifier.weight(0.8f).fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width * 0.46f
                        val h = size.height * 0.92f
                        val l = (size.width - w) / 2f
                        val t = (size.height - h) / 2f
                        // Räder
                        val tw = w * 0.16f
                        val th = h * 0.17f
                        listOf(
                            Wheel.FL to Offset(l - tw * 0.55f, t + h * 0.13f),
                            Wheel.FR to Offset(l + w - tw * 0.45f, t + h * 0.13f),
                            Wheel.RL to Offset(l - tw * 0.55f, t + h * 0.70f),
                            Wheel.RR to Offset(l + w - tw * 0.45f, t + h * 0.70f),
                        ).forEach { (wh, o) ->
                            val st = tires[wh]
                            val c = if (st == null || st.noSignal) J.track else tireColor(vm.tireLevel(st, settings))
                            drawRoundRect(c.copy(alpha = 0.25f), o - Offset(4f, 4f), Size(tw + 8f, th + 8f), CornerRadius(tw * 0.4f))
                            drawRoundRect(c, o, Size(tw, th), CornerRadius(tw * 0.3f))
                        }
                        // Karosserie
                        drawRoundRect(
                            Brush.verticalGradient(listOf(Color(0xFF2B323A), Color(0xFF111418), Color(0xFF1C2228)), startY = t, endY = t + h),
                            Offset(l, t), Size(w, h), CornerRadius(w * 0.32f),
                        )
                        drawRoundRect(pal.glow.copy(alpha = 0.7f), Offset(l, t), Size(w, h), CornerRadius(w * 0.32f), style = Stroke(3f))
                        // Scheiben
                        drawRoundRect(Color(0xFF0A0D10), Offset(l + w * 0.12f, t + h * 0.26f), Size(w * 0.76f, h * 0.14f), CornerRadius(w * 0.12f))
                        drawRoundRect(Color(0xFF0A0D10), Offset(l + w * 0.14f, t + h * 0.68f), Size(w * 0.72f, h * 0.10f), CornerRadius(w * 0.12f))
                        drawRoundRect(Color.White.copy(alpha = 0.06f), Offset(l + w * 0.16f, t + h * 0.42f), Size(w * 0.68f, h * 0.24f), CornerRadius(w * 0.1f))
                        // Licht vorn / hinten
                        drawLine(Color.White.copy(alpha = 0.8f), Offset(l + w * 0.18f, t + h * 0.02f), Offset(l + w * 0.82f, t + h * 0.02f), strokeWidth = 4f)
                        drawLine(Color(0xFFFF2D3A), Offset(l + w * 0.14f, t + h * 0.985f), Offset(l + w * 0.86f, t + h * 0.985f), strokeWidth = 5f)
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceAround) {
                    TireCard(Wheel.FR, tires[Wheel.FR], vm, settings, alignEnd = false)
                    TireCard(Wheel.RR, tires[Wheel.RR], vm, settings, alignEnd = false)
                }
            }
            tires[Wheel.SPARE]?.let {
                Text("Reserverad: ${fmtBar(it.bar.toFloat())} bar · ${it.tempC} °C", color = J.dim, fontSize = 13.sp)
            }
            Text(
                "Sensoren melden sich meist erst während der Fahrt (oder alle paar Minuten im Stand). " +
                    "Den Solldruck findest du auf dem Aufkleber im Tankdeckel – einstellbar in den Einstellungen.",
                color = J.dim,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun TireCard(wheel: Wheel, t: TireState?, vm: MainViewModel, s: de.jarvis.w204.Settings, alignEnd: Boolean) {
    val level = if (t == null || t.noSignal) -1 else vm.tireLevel(t, s)
    val color = if (level < 0) J.dim else tireColor(level)
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).background(J.panel).padding(16.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(wheel.label.replaceFirstChar { it.uppercase() }, color = J.dim, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(t?.takeIf { !it.noSignal }?.let { fmtBar(it.bar.toFloat()) } ?: "–", color = color, fontSize = 44.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.width(4.dp))
            Text("bar", color = J.dim, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        Text(
            when {
                t == null -> "noch keine Daten"
                t.noSignal -> "kein Signal"
                t.leak -> "Luftverlust!"
                level == 2 -> "viel zu niedrig"
                level == 1 && t.tempC >= 85 -> "sehr warm"
                level == 1 -> "zu niedrig"
                else -> "in Ordnung"
            } + (t?.takeIf { !it.noSignal }?.let { " · ${it.tempC} °C" } ?: "") + (if (t?.lowBattery == true) " · Batterie schwach" else ""),
            color = if (level >= 1) color else J.dim,
            fontSize = 13.sp,
        )
    }
}

fun fmtBar(v: Float): String = String.format(Locale.GERMANY, "%.1f", v)

/** Kurzinfo für die Statusleiste: Text + Stufe (-1 = keine Daten). */
fun tireSummary(vm: MainViewModel, tires: Map<Wheel, TireState>, s: de.jarvis.w204.Settings): Pair<String, Int> {
    val live = tires.values.filter { it.wheel != Wheel.SPARE && !it.noSignal }
    if (live.isEmpty()) return "Reifen –" to -1
    val worst = live.maxByOrNull { vm.tireLevel(it, s) }!!
    val lvl = vm.tireLevel(worst, s)
    return if (lvl == 0) "Reifen ok" to 0 else "${worst.wheel.short} ${fmtBar(worst.bar.toFloat())} bar" to lvl
}
