package de.jarvis.w204.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer

/** Warme Kupfer-Füllung wie bei modernen Premium-Cockpits. */
object Copper {
    val light = Color(0xFFF7E8DA)
    val mid = Color(0xFFDDA88A)
    val deep = Color(0xFF9E5D63)
    val empty = Color(0xFF10151B)
}

/**
 * Pfeil-Instrument: ein breites Band in Form eines "<" (bzw. ">" gespiegelt),
 * das sich von unten über die Spitze nach oben füllt.
 * [apexValue] liegt an der Spitze – damit lässt sich der untere Bereich feiner auflösen.
 */
@Composable
fun ChevronGauge(
    value: Float?,
    min: Float,
    apexValue: Float,
    max: Float,
    ticks: List<Float>,
    label: (Float) -> String?,
    unit: String,
    mirrored: Boolean,
    modifier: Modifier = Modifier,
    redFrom: Float? = null,
) {
    val target = value?.coerceIn(min, max) ?: min
    val animated by animateFloatAsState(target, tween(180), label = "chevron")
    val measurer = rememberTextMeasurer()

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val topY = h * 0.10f
        val botY = h * 0.90f
        val apexY = (topY + botY) / 2f
        val t = w * 0.21f                 // Bandbreite (horizontal)
        val xEnd = w * 0.66f              // äußere Kante oben/unten
        val xApex = w * 0.30f             // äußere Kante an der Spitze

        fun mx(x: Float) = if (mirrored) w - x else x
        fun outerX(y: Float): Float =
            if (y >= apexY) xApex + (xEnd - xApex) * (y - apexY) / (botY - apexY)
            else xApex + (xEnd - xApex) * (apexY - y) / (apexY - topY)
        fun frac(v: Float): Float =
            if (v <= apexValue) 0.5f * (v - min) / (apexValue - min)
            else 0.5f + 0.5f * (v - apexValue) / (max - apexValue)
        fun yOf(v: Float) = botY - frac(v) * (botY - topY)

        val band = Path().apply {
            moveTo(mx(xEnd), topY)
            lineTo(mx(xApex), apexY)
            lineTo(mx(xEnd), botY)
            lineTo(mx(xEnd + t), botY)
            lineTo(mx(xApex + t), apexY)
            lineTo(mx(xEnd + t), topY)
            close()
        }
        val outerEdge = Path().apply {
            moveTo(mx(xEnd), topY); lineTo(mx(xApex), apexY); lineTo(mx(xEnd), botY)
        }
        val innerEdge = Path().apply {
            moveTo(mx(xEnd + t), topY); lineTo(mx(xApex + t), apexY); lineTo(mx(xEnd + t), botY)
        }

        val yv = if (value == null) botY else yOf(animated)
        val hot = redFrom != null && value != null && value >= redFrom

        // Leerer Teil: dunkel mit feiner Schraffur
        clipRect(bottom = yv) {
            drawPath(band, Copper.empty)
            clipPath(band) {
                val step = w * 0.025f
                var x = -h
                while (x < w + h) {
                    drawLine(Color.White.copy(alpha = 0.05f), Offset(x, botY), Offset(x + (botY - topY), topY), strokeWidth = 1f)
                    x += step
                }
            }
        }
        // Gefüllter Teil
        if (value != null && yv < botY) {
            clipRect(top = yv) {
                val colors = if (hot) listOf(Color(0xFFFFC2B8), J.danger, Color(0xFF7A1E24))
                else listOf(Copper.light, Copper.mid, Copper.deep)
                drawPath(band, Brush.verticalGradient(colors, startY = yv, endY = botY))
            }
        }

        // Skala im Band + Beschriftung außen
        val labelStyle = TextStyle(color = J.dim, fontSize = (h * 0.045f).toSp())
        val redLabelStyle = labelStyle.copy(color = J.danger)
        for (v in ticks) {
            val y = yOf(v)
            val ox = outerX(y)
            val text = label(v)
            val major = text != null
            val inRed = redFrom != null && v >= redFrom
            val len = if (major) t * 0.42f else t * 0.24f
            val tickColor = when {
                inRed -> J.danger.copy(alpha = 0.9f)
                y >= yv && value != null -> Color.Black.copy(alpha = 0.35f)
                else -> Color.White.copy(alpha = if (major) 0.45f else 0.25f)
            }
            drawLine(tickColor, Offset(mx(ox + 2f), y), Offset(mx(ox + len), y), strokeWidth = if (major) 2.2f else 1.4f)
            if (text != null) {
                val layout = measurer.measure(text, if (inRed) redLabelStyle else labelStyle)
                val gap = w * 0.05f
                val lx = if (mirrored) mx(ox) + gap else ox - gap - layout.size.width
                drawText(layout, topLeft = Offset(lx, y - layout.size.height / 2f))
            }
        }

        // Kanten: außen hell, innen zart
        drawPath(outerEdge, Color.White.copy(alpha = 0.85f), style = Stroke(w * 0.012f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(innerEdge, Color.White.copy(alpha = 0.18f), style = Stroke(w * 0.006f, join = StrokeJoin.Round))

        // Wertmarke: helle Kante mit Leuchten
        if (value != null) {
            val a = Offset(mx(outerX(yv)), yv)
            val b = Offset(mx(outerX(yv) + t), yv)
            drawLine(Color.White.copy(alpha = 0.18f), a, b, strokeWidth = h * 0.028f, cap = StrokeCap.Round)
            drawLine(Color.White, a, b, strokeWidth = h * 0.007f, cap = StrokeCap.Round)
        }

        // Linien oben (mit Einheit) und unten (Richtung Mitte)
        val lineColor = Color.White.copy(alpha = 0.55f)
        drawLine(lineColor, Offset(mx(xEnd), topY), Offset(mx(xEnd + t + w * 0.12f), topY), strokeWidth = 1.6f)
        val unitLayout = measurer.measure(unit, labelStyle)
        val ux = if (mirrored) mx(xEnd + t + w * 0.12f) else xEnd + t + w * 0.12f - unitLayout.size.width
        drawText(unitLayout, topLeft = Offset(ux, topY - unitLayout.size.height - h * 0.008f))
        drawLine(lineColor, Offset(mx(xEnd), botY), Offset(mx(w), botY), strokeWidth = 1.6f)
    }
}
