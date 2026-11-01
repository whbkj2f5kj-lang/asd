package de.jarvis.w204.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jarvis.w204.MainViewModel
import de.jarvis.w204.car.VehicleState
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Mitte des Cockpits zum Wischen: Assistent (Spur/Kamera) · 3D-Karte · Werte.
 * Alle Seiten bleiben aktiv, damit Kamera und Schildererkennung weiterlaufen, auch wenn man die Karte ansieht.
 */
@Composable
fun CenterPager(vm: MainViewModel, modifier: Modifier = Modifier, assistant: @Composable BoxScope.() -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pager = rememberPagerState(initialPage = settings.centerPage.coerceIn(0, 2)) { 3 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { p -> if (p != vm.settings.value.centerPage) vm.update { it.copy(centerPage = p) } }
    }
    val inset = LocalCenterInset.current
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        // Anteil der Breite, ab dem die Mitte beginnt; bis dahin wird die Karte weich eingeblendet
        val f = if (maxWidth.value > 0f) (inset / maxWidth).coerceIn(0f, 0.45f) else 0f
        val fadeFrom = (f - 0.24f).coerceAtLeast(0f)
        val fadeTo = (f - 0.02f).coerceAtLeast(0.12f)
        HorizontalPager(state = pager, beyondViewportPageCount = 2, modifier = Modifier.fillMaxSize()) { page ->
            // Tiefeneffekt beim Wischen: die gehende Seite tritt zurück und blendet aus
            Box(Modifier.fillMaxSize().graphicsLayer {
                val off = kotlin.math.abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                alpha = 1f - off * 0.65f
                val sc = 1f - off * 0.1f
                scaleX = sc; scaleY = sc
            }) {
                when (page) {
                    0 -> Box(Modifier.fillMaxSize(), content = assistant)
                    1 -> MapView3D(
                        vm, active = pager.currentPage == 1,
                        modifier = Modifier.fillMaxSize().softEdges(fadeFrom, fadeTo, top = 0.02f, bottom = 0.10f),
                        viewFraction = 1f - 2f * f,
                    )
                    else -> ValuesPage(vm, Modifier.fillMaxSize().padding(horizontal = inset))
                }
            }
        }
        // Reiter oben mittig (antippen oder wischen)
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(50))
                .background(Color(0xB30B111A)).glass(RoundedCornerShape(50)).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf("Assistent", "Karte", "Werte").forEachIndexed { i, label ->
                val sel = pager.currentPage == i
                val bg by animateColorAsState(if (sel) Color(0x33FFFFFF) else Color.Transparent, label = "tab")
                Text(
                    label,
                    color = if (sel) J.text else J.dim,
                    fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(bg)
                        .clickable { scope.launch { pager.animateScrollToPage(i) } }
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                )
            }
        }
        // Seitenpunkte unten
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i ->
                val wDp by animateDpAsState(if (pager.currentPage == i) 18.dp else 6.dp, label = "dot")
                Box(Modifier.width(wDp).height(6.dp).clip(RoundedCornerShape(50)).background(if (pager.currentPage == i) J.accent else J.track))
            }
        }
    }
}

// ------------------------------------------------------------------------------- Werte

/** Ein Messwert für die Werte-Seite */
data class MetricDef(
    val key: String,
    val label: String,
    val unit: String,
    val read: (VehicleState) -> Double?,
    val decimals: Int = 0,
    /** ab hier gelb */
    val warnAbove: Double? = null,
    val warnBelow: Double? = null,
)

val METRICS = listOf(
    MetricDef("kmh", "Geschwindigkeit", "km/h", { it.speed?.toDouble() }),
    MetricDef("rpm", "Drehzahl", "U/min", { it.rpm?.toDouble() }, warnAbove = 4200.0),
    MetricDef("boost", "Ladedruck", "bar", { it.boostBar }, 2),
    MetricDef("load", "Motorlast", "%", { it.load?.toDouble() }),
    MetricDef("coolant", "Kühlmittel", "°C", { it.coolant?.toDouble() }, warnAbove = 105.0, warnBelow = 60.0),
    MetricDef("oil", "Motoröl", "°C", { it.oilTemp?.toDouble() }, warnAbove = 130.0),
    MetricDef("iat", "Ladeluft", "°C", { it.intakeTemp?.toDouble() }, warnAbove = 70.0),
    MetricDef("exhaust", "Abgas / DPF", "°C", { it.exhaustTemp?.toDouble() }, warnAbove = 480.0),
    MetricDef("dpfdp", "DPF-Gegendruck", "kPa", { it.dpfDeltaKpa }, 1),
    MetricDef("dpfin", "DPF Eingang", "°C", { it.dpfInletTemp?.toDouble() }),
    MetricDef("dpfout", "DPF Ausgang", "°C", { it.dpfOutletTemp?.toDouble() }),
    MetricDef("regentrig", "DPF-Füllgrad", "%", { it.regenTrigger?.toDouble() }),
    MetricDef("regenkm", "Ø km je Regeneration", "km", { it.regenAvgKm?.toDouble() }),
    MetricDef("maf", "Luftmasse", "g/s", { it.maf }, 1),
    MetricDef("lambda", "Lambda", "λ", { it.lambda }, 2),
    MetricDef("rail", "Raildruck", "bar", { it.railBar?.toDouble() }),
    MetricDef("cons", "Verbrauch", "l/100", { it.consumption }, 1),
    MetricDef("fuelh", "Verbrauch", "l/h", { it.fuelRateEff }, 1),
    MetricDef("fuel", "Tank", "%", { it.fuelLevel?.toDouble() }, warnBelow = 12.0),
    MetricDef("volt", "Bordnetz", "V", { it.voltage }, 1, warnAbove = 14.9, warnBelow = 12.2),
    MetricDef("ambient", "Außen", "°C", { it.ambient?.toDouble() }),
    MetricDef("baro", "Luftdruck", "kPa", { it.baroKpa?.toDouble() }),
)

@Composable
fun ValuesPage(vm: MainViewModel, modifier: Modifier = Modifier) {
    val v by vm.obd.vehicle.collectAsStateWithLifecycle()
    val hist by vm.history.collectAsStateWithLifecycle()
    val tires by vm.tpms.tires.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val extras = ArrayList<@Composable () -> Unit>()
    if (tires.isNotEmpty()) extras += {
            val t = tires.values.filter { it.wheel != de.jarvis.w204.tpms.Wheel.SPARE }.sortedBy { it.wheel.ordinal }
            Tile("Reifendruck", null, null, emptyList(), J.accent) {
                Column {
                    t.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEach { tire ->
                                val lvl = vm.tireLevel(tire, settings)
                                Text(
                                    "${tire.wheel.short} " + String.format(Locale.GERMANY, "%.1f", tire.bar),
                                    color = if (lvl > 0) tireColor(lvl) else J.text, fontSize = 20.sp, fontWeight = FontWeight.Light,
                                )
                            }
                        }
                    }
                }
            }
    }
    extras += {
        val regen = (v.exhaustTemp ?: 0) >= 480 && v.engineRunning
        Tile("Partikelfilter", null, null, emptyList(), if (regen) J.warn else J.ok) {
            Text(if (regen) "Regeneriert" else "Normal", color = if (regen) J.warn else J.text, fontSize = 24.sp, fontWeight = FontWeight.Light)
        }
    }
    ValuesGrid(v, hist, modifier, extras)
}

/** Reine Darstellung (auch für Vorschaubilder) */
@Composable
fun ValuesGrid(v: VehicleState, hist: Map<String, List<Float>>, modifier: Modifier = Modifier, extras: List<@Composable () -> Unit> = emptyList()) {
    val shown = METRICS.filter { it.read(v) != null || (hist[it.key]?.isNotEmpty() == true) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 48.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        items(shown, key = { it.key }) { m ->
            val raw = m.read(v)
            // Zahlen gleiten weich zum neuen Wert
            val anim by androidx.compose.animation.core.animateFloatAsState((raw ?: 0.0).toFloat(), androidx.compose.animation.core.tween(450), label = m.key)
            val value = raw?.let { anim.toDouble() }
            val warn = value != null && ((m.warnAbove != null && value > m.warnAbove) || (m.warnBelow != null && value < m.warnBelow && (m.key != "volt" || (v.rpm ?: 0) < 400) && (m.key != "coolant" || v.engineRunning)))
            Tile(
                m.label,
                value?.let { String.format(Locale.GERMANY, "%.${m.decimals}f", it) } ?: "–",
                m.unit,
                hist[m.key].orEmpty(),
                if (warn) J.warn else J.accent,
            )
        }
        items(extras.size) { i -> extras[i]() }
    }
}

@Composable
internal fun Tile(label: String, value: String?, unit: String?, history: List<Float>, color: Color, content: (@Composable () -> Unit)? = null) {
    Box(
        Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF0F151D), Color(0xFF080C11))))
            .glass(RoundedCornerShape(16.dp)),
    ) {
        if (history.size >= 2) Sparkline(history, color, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(label.uppercase(Locale.GERMANY), color = J.dim, fontSize = 11.sp, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            if (content != null) content() else Row(verticalAlignment = Alignment.Bottom) {
                Text(value ?: "–", color = if (color == J.warn) J.warn else J.text, fontSize = 30.sp, fontWeight = FontWeight.Light, maxLines = 1)
                if (unit != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(unit, color = J.dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 1e-6f } ?: 1f
        val stepX = size.width / (values.size - 1)
        fun y(v: Float) = size.height - 4f - (v - min) / span * (size.height - 10f)
        val line = Path()
        values.forEachIndexed { i, v -> if (i == 0) line.moveTo(0f, y(v)) else line.lineTo(i * stepX, y(v)) }
        val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent)))
        drawPath(line, color.copy(alpha = 0.8f), style = Stroke(width = 2f, cap = StrokeCap.Round))
        drawCircle(color, 3f, Offset(size.width, y(values.last())))
    }
}
