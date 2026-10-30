package de.jarvis.w204.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

internal fun rad(deg: Float) = (deg * PI / 180.0).toFloat()

/** Schlankes Rundinstrument: feiner Bogen mit weichem Leuchten, Skala innen, Leuchtpunkt an der Spitze. */
@Composable
fun ThinGauge(
    value: Float?,
    lo: Float,
    hi: Float,
    redFrom: Float?,
    major: Float,
    minor: Float,
    label: (Float) -> String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val target = if (value == null) 0f else ((value - lo) / (hi - lo)).coerceIn(0f, 1f)
    val frac by animateFloatAsState(target, tween(200), label = "gauge")
    val hot = redFrom != null && value != null && value >= redFrom
    val color by animateColorAsState(if (hot) J.danger else J.accent, label = "gaugeColor")
    val measurer = rememberTextMeasurer()

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val dim = size.minDimension
            val stroke = dim * 0.022f
            val d = dim * 0.88f
            val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            val arc = Size(d, d)
            val c = center
            val r = d / 2f

            drawArc(J.track, 135f, 270f, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (value != null && frac > 0.002f) {
                val sweep = 270f * frac
                drawArc(color.copy(alpha = 0.08f), 135f, sweep, false, tl, arc, style = Stroke(stroke * 5f, cap = StrokeCap.Round))
                drawArc(color.copy(alpha = 0.20f), 135f, sweep, false, tl, arc, style = Stroke(stroke * 2.4f, cap = StrokeCap.Round))
                drawArc(color, 135f, sweep, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                val a = rad(135f + sweep)
                drawCircle(Color.White, stroke * 1.1f, c + Offset(cos(a), sin(a)) * r)
            }

            val labelStyle = TextStyle(color = J.dim, fontSize = (dim * 0.045f).toSp())
            var v = lo
            while (v <= hi + minor / 1000f) {
                val a = rad(135f + 270f * (v - lo) / (hi - lo))
                val dir = Offset(cos(a), sin(a))
                val steps = (v - lo) / major
                val isMajor = abs(steps - round(steps)) < 0.001f
                val inRed = redFrom != null && v >= redFrom
                val tickColor = when {
                    inRed -> J.danger.copy(alpha = 0.8f)
                    isMajor -> J.text.copy(alpha = 0.5f)
                    else -> J.dim.copy(alpha = 0.4f)
                }
                val len = if (isMajor) stroke * 3f else stroke * 1.5f
                val start = r - stroke * 2.2f
                drawLine(
                    tickColor, c + dir * start, c + dir * (start - len),
                    strokeWidth = if (isMajor) stroke * 0.45f else stroke * 0.3f, cap = StrokeCap.Round,
                )
                if (isMajor) {
                    val layout = measurer.measure(label(v), labelStyle)
                    val p = c + dir * (r - stroke * 7.5f)
                    drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
                }
                v += minor
            }
        }
        content()
    }
}

/** Messwertzeile: Statuspunkt, Bezeichnung, Wert, Einheit. */
@Composable
fun ValueRow(label: String, value: String?, unit: String, state: Color?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(state ?: J.track))
        Spacer(Modifier.width(10.dp))
        Text(label, color = J.dim, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(
            value ?: "—",
            color = if (value == null) J.dim else J.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
        )
        Spacer(Modifier.width(5.dp))
        Text(unit, color = J.dim, fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(30.dp))
    }
}

/** Deutsches Tempolimit-Schild (Zeichen 274); pulsiert rot bei Überschreitung. */
@Composable
fun SpeedLimitSign(limit: Int?, over: Boolean, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
        label = "alpha",
    )
    BoxWithConstraints(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        val sizeDp = maxWidth.value
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            if (limit == null) {
                drawCircle(J.panel2, r)
                drawCircle(J.track, r * 0.9f, style = Stroke(r * 0.12f))
            } else {
                drawCircle(Color.White, r)
                drawCircle(Color(0xFFD7161E), r * 0.89f, style = Stroke(r * 0.22f))
                if (over) drawCircle(J.danger.copy(alpha = alpha), r, style = Stroke(r * 0.14f))
            }
        }
        val factor = if ((limit ?: 0) >= 100) 0.34f else 0.42f
        Text(
            limit?.toString() ?: "—",
            color = if (limit == null) J.dim else Color(0xFF111111),
            fontWeight = FontWeight.Bold,
            fontSize = (sizeDp * factor).sp,
        )
    }
}
